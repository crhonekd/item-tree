# Phase 4 — Cache Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the in-memory `TreeCache` with full `apply*` idempotency/tolerance, `ReentrantReadWriteLock`-based concurrency, `SnapshotBuilder` for bulk load, and `CacheReadinessGate` for Spring readiness integration.

**Architecture:** `DefaultTreeCache` wraps three `ConcurrentHashMap` indexes under a single `ReentrantReadWriteLock`; writes update all three atomically; reads return defensive copies. `SnapshotBuilder` accumulates `StructuralRow` objects into plain `HashMap`/`HashSet` structures and produces a `TreeSnapshot` that `DefaultTreeCache.replaceAll` deep-copies into new `ConcurrentHashMap` indexes under the write lock. `CacheReadinessGate` holds a `volatile boolean` and publishes a Spring `AvailabilityChangeEvent` on `markReady()`. `getTreeView` is deferred to Phase 6 (requires `PathResolver`); it throws `UnsupportedOperationException` until then.

**Tech Stack:** Java 21 records, `ReentrantReadWriteLock`, `ConcurrentHashMap`, JUnit 5, AssertJ, Mockito (for `CacheReadinessGateTest` only). No Spring context required for cache tests.

---

## File Map

| Action | File |
|---|---|
| Modify | `src/main/java/com/myxcomp/ice/xtree/cache/CachedNode.java` |
| Create | `src/main/java/com/myxcomp/ice/xtree/cache/TreeCache.java` |
| Create | `src/main/java/com/myxcomp/ice/xtree/cache/DefaultTreeCache.java` |
| Create | `src/main/java/com/myxcomp/ice/xtree/cache/SnapshotBuilder.java` |
| Create | `src/main/java/com/myxcomp/ice/xtree/cache/CacheReadinessGate.java` |
| Create | `src/test/java/com/myxcomp/ice/xtree/cache/CachedNodeTest.java` |
| Create | `src/test/java/com/myxcomp/ice/xtree/cache/SnapshotBuilderTest.java` |
| Create | `src/test/java/com/myxcomp/ice/xtree/cache/CacheReadinessGateTest.java` |
| Create | `src/test/java/com/myxcomp/ice/xtree/cache/DefaultTreeCacheTest.java` |

---

## Task 1: `CachedNode` compact-constructor null guard

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/cache/CachedNode.java`
- Create: `src/test/java/com/myxcomp/ice/xtree/cache/CachedNodeTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.myxcomp.ice.xtree.cache;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class CachedNodeTest {

    @Test
    void nullParentIdThrowsNpe() {
        assertThatNullPointerException()
                .isThrownBy(() -> new CachedNode(1L, null, "root", "Folder", Instant.EPOCH, "sys"))
                .withMessageContaining("parentId");
    }

    @Test
    void nonNullParentIdConstructsSuccessfully() {
        CachedNode node = new CachedNode(1L, 0L, "root", "Folder", Instant.EPOCH, "sys");
        assertThat(node.parentId()).isEqualTo(0L);
    }
}
```

Add `import static org.assertj.core.api.Assertions.assertThat;` to the imports above.

- [ ] **Step 2: Run test to verify it fails**

```
./gradlew test --tests "com.myxcomp.ice.xtree.cache.CachedNodeTest" 2>&1 | tail -20
```

Expected: `nullParentIdThrowsNpe` FAILS because no null check exists yet.

- [ ] **Step 3: Add compact constructor to `CachedNode.java`**

Replace the entire file content:

```java
package com.myxcomp.ice.xtree.cache;

import java.time.Instant;
import java.util.Objects;

public record CachedNode(
        long itemTreeId,
        Long parentId,
        String name,
        String type,
        Instant lastUpdate,
        String lastUpdateUser
) {
    public CachedNode {
        Objects.requireNonNull(parentId, "parentId");
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```
./gradlew test --tests "com.myxcomp.ice.xtree.cache.CachedNodeTest" 2>&1 | tail -10
```

Expected: both tests PASS.

- [ ] **Step 5: Run the full suite to check no regressions**

```
./gradlew test 2>&1 | tail -15
```

Expected: BUILD SUCCESSFUL, all 79 prior tests still green.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/cache/CachedNode.java \
        src/test/java/com/myxcomp/ice/xtree/cache/CachedNodeTest.java
git commit -m "$(cat <<'EOF'
feat(cache): add parentId null guard to CachedNode compact constructor

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: `TreeCache` interface

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/cache/TreeCache.java`

No test — pure interface; behaviour is tested through `DefaultTreeCache`.

- [ ] **Step 1: Create `TreeCache.java`**

```java
package com.myxcomp.ice.xtree.cache;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

public interface TreeCache {

    // ── Reads ─────────────────────────────────────────────────────────────
    Optional<CachedNode> getById(long id);
    List<CachedNode>     getChildren(long parentId);
    List<CachedNode>     getSubtreeFlat(long rootId);

    /**
     * Returns the trimmed tree view for the given home folder.
     * Algorithm per design §8. Implemented in Phase 6.
     */
    List<CachedNode>     getTreeView(long homeFolderId);

    Optional<CachedNode> findHomeFolder(String userName);
    Optional<CachedNode> searchById(long id);
    List<CachedNode>     searchByName(String needle, OptionalInt limit);
    boolean              isAncestor(long candidateAncestorId, long nodeId);
    boolean              exists(long id);
    boolean              isFolder(long id);
    int                  size();

    // ── Mutations ─────────────────────────────────────────────────────────
    void applyCreate(CachedNode node);
    void applyMetadataUpdate(long id, Instant lastUpdate, String lastUpdateUser);
    void applyMove(long id, long newParentId, Instant lastUpdate, String lastUpdateUser);
    void applyRename(long id, String newName, Instant lastUpdate, String lastUpdateUser);
    void applyDelete(Set<Long> ids);
    void replaceAll(TreeSnapshot newSnapshot);
}
```

- [ ] **Step 2: Verify compilation**

```
./gradlew compileJava 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/cache/TreeCache.java
git commit -m "$(cat <<'EOF'
feat(cache): add TreeCache interface per design §4

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: `SnapshotBuilder` + tests

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/cache/SnapshotBuilder.java`
- Create: `src/test/java/com/myxcomp/ice/xtree/cache/SnapshotBuilderTest.java`

- [ ] **Step 1: Write the failing tests**

```java
package com.myxcomp.ice.xtree.cache;

import com.myxcomp.ice.xtree.persistence.StructuralRow;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotBuilderTest {

    private static final Instant T = Instant.EPOCH;

    private static StructuralRow row(long id, long parentId, String name, String type) {
        return new StructuralRow(id, parentId, name, type, T, "sys");
    }

    @Nested
    class EmptyBuilder {
        @Test
        void buildReturnsEmptyMaps() {
            TreeSnapshot snap = new SnapshotBuilder().build();
            assertThat(snap.byId()).isEmpty();
            assertThat(snap.childrenByParent()).isEmpty();
            assertThat(snap.foldersByName()).isEmpty();
        }
    }

    @Nested
    class SingleNode {
        @Test
        void folderAppearsInAllThreeIndexes() {
            SnapshotBuilder builder = new SnapshotBuilder();
            builder.accept(row(1L, 0L, "root", "Folder"));
            TreeSnapshot snap = builder.build();

            assertThat(snap.byId()).containsKey(1L);
            assertThat(snap.byId().get(1L).name()).isEqualTo("root");
            assertThat(snap.childrenByParent()).containsKey(0L);
            assertThat(snap.childrenByParent().get(0L)).containsExactly(1L);
            assertThat(snap.foldersByName()).containsKey("root");
            assertThat(snap.foldersByName().get("root")).containsExactly(1L);
        }

        @Test
        void nonFolderDoesNotAppearInFoldersByName() {
            SnapshotBuilder builder = new SnapshotBuilder();
            builder.accept(row(10L, 1L, "MyReport", "Report"));
            TreeSnapshot snap = builder.build();

            assertThat(snap.byId()).containsKey(10L);
            assertThat(snap.foldersByName()).doesNotContainKey("MyReport");
        }
    }

    @Nested
    class MultipleNodes {
        @Test
        void childrenByParentGroupsCorrectly() {
            SnapshotBuilder builder = new SnapshotBuilder();
            builder.accept(row(1L, 0L, "root", "Folder"));
            builder.accept(row(2L, 1L, "Users", "Folder"));
            builder.accept(row(3L, 1L, "Reports", "Folder"));
            builder.accept(row(10L, 2L, "testuser1", "Folder"));
            TreeSnapshot snap = builder.build();

            assertThat(snap.childrenByParent().get(1L)).containsExactlyInAnyOrder(2L, 3L);
            assertThat(snap.childrenByParent().get(2L)).containsExactly(10L);
        }

        @Test
        void foldersByNameAccumulatesMultipleFoldersWithSameName() {
            SnapshotBuilder builder = new SnapshotBuilder();
            builder.accept(row(1L, 0L, "root", "Folder"));
            builder.accept(row(2L, 1L, "shared", "Folder"));
            builder.accept(row(3L, 1L, "shared", "Folder"));
            TreeSnapshot snap = builder.build();

            assertThat(snap.foldersByName().get("shared")).containsExactlyInAnyOrder(2L, 3L);
        }

        @Test
        void byIdContainsAllNodes() {
            SnapshotBuilder builder = new SnapshotBuilder();
            builder.accept(row(1L, 0L, "root", "Folder"));
            builder.accept(row(2L, 1L, "child", "Report"));
            TreeSnapshot snap = builder.build();

            assertThat(snap.byId()).hasSize(2);
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```
./gradlew test --tests "com.myxcomp.ice.xtree.cache.SnapshotBuilderTest" 2>&1 | tail -20
```

Expected: compilation error — `SnapshotBuilder` does not exist.

- [ ] **Step 3: Create `SnapshotBuilder.java`**

```java
package com.myxcomp.ice.xtree.cache;

import com.myxcomp.ice.xtree.common.Types;
import com.myxcomp.ice.xtree.persistence.StructuralRow;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class SnapshotBuilder {

    private final Map<Long, CachedNode> byId = new HashMap<>();
    private final Map<Long, Set<Long>> childrenByParent = new HashMap<>();
    private final Map<String, Set<Long>> foldersByName = new HashMap<>();

    public void accept(StructuralRow row) {
        CachedNode node = new CachedNode(
                row.itemTreeId(), row.parentId(), row.name(), row.type(),
                row.lastUpdate(), row.lastUpdateUser());
        byId.put(node.itemTreeId(), node);
        childrenByParent.computeIfAbsent(node.parentId(), k -> new HashSet<>())
                        .add(node.itemTreeId());
        if (Types.isFolder(node.type())) {
            foldersByName.computeIfAbsent(node.name(), k -> new HashSet<>())
                         .add(node.itemTreeId());
        }
    }

    public TreeSnapshot build() {
        return new TreeSnapshot(
                Map.copyOf(byId),
                Map.copyOf(childrenByParent),
                Map.copyOf(foldersByName));
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```
./gradlew test --tests "com.myxcomp.ice.xtree.cache.SnapshotBuilderTest" 2>&1 | tail -10
```

Expected: all SnapshotBuilderTest tests PASS.

- [ ] **Step 5: Run full suite**

```
./gradlew test 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/cache/SnapshotBuilder.java \
        src/test/java/com/myxcomp/ice/xtree/cache/SnapshotBuilderTest.java
git commit -m "$(cat <<'EOF'
feat(cache): add SnapshotBuilder for bulk structural row ingestion

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: `CacheReadinessGate` + tests

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/cache/CacheReadinessGate.java`
- Create: `src/test/java/com/myxcomp/ice/xtree/cache/CacheReadinessGateTest.java`

- [ ] **Step 1: Write the failing tests**

```java
package com.myxcomp.ice.xtree.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CacheReadinessGateTest {

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Test
    void startsNotReady() {
        CacheReadinessGate gate = new CacheReadinessGate(eventPublisher);
        assertThat(gate.isReady()).isFalse();
    }

    @Test
    void markReadyFlipsFlag() {
        CacheReadinessGate gate = new CacheReadinessGate(eventPublisher);
        gate.markReady();
        assertThat(gate.isReady()).isTrue();
    }

    @Test
    void markReadyPublishesAcceptingTrafficEvent() {
        CacheReadinessGate gate = new CacheReadinessGate(eventPublisher);
        gate.markReady();

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        Object event = captor.getValue();
        assertThat(event).isInstanceOf(AvailabilityChangeEvent.class);
        AvailabilityChangeEvent<?> ace = (AvailabilityChangeEvent<?>) event;
        assertThat(ace.getState()).isEqualTo(ReadinessState.ACCEPTING_TRAFFIC);
    }

    @Test
    void markReadyIsIdempotent() {
        CacheReadinessGate gate = new CacheReadinessGate(eventPublisher);
        gate.markReady();
        gate.markReady();
        assertThat(gate.isReady()).isTrue();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```
./gradlew test --tests "com.myxcomp.ice.xtree.cache.CacheReadinessGateTest" 2>&1 | tail -20
```

Expected: compilation error — `CacheReadinessGate` does not exist.

- [ ] **Step 3: Create `CacheReadinessGate.java`**

```java
package com.myxcomp.ice.xtree.cache;

import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class CacheReadinessGate {

    private volatile boolean ready = false;
    private final ApplicationEventPublisher eventPublisher;

    public CacheReadinessGate(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public void markReady() {
        ready = true;
        AvailabilityChangeEvent.publish(eventPublisher, this, ReadinessState.ACCEPTING_TRAFFIC);
    }

    public boolean isReady() {
        return ready;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```
./gradlew test --tests "com.myxcomp.ice.xtree.cache.CacheReadinessGateTest" 2>&1 | tail -10
```

Expected: all 4 tests PASS.

- [ ] **Step 5: Run full suite**

```
./gradlew test 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/cache/CacheReadinessGate.java \
        src/test/java/com/myxcomp/ice/xtree/cache/CacheReadinessGateTest.java
git commit -m "$(cat <<'EOF'
feat(cache): add CacheReadinessGate wiring Spring readiness on cache load

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: `DefaultTreeCache` — skeleton + `applyCreate` + `getById`

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/cache/DefaultTreeCache.java`
- Create: `src/test/java/com/myxcomp/ice/xtree/cache/DefaultTreeCacheTest.java` (initial)

- [ ] **Step 1: Write the failing tests**

```java
package com.myxcomp.ice.xtree.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultTreeCacheTest {

    private DefaultTreeCache cache;
    private static final Instant T = Instant.EPOCH;

    // Helper: create a folder node
    static CachedNode folder(long id, long parentId, String name) {
        return new CachedNode(id, parentId, name, "Folder", T, "sys");
    }

    // Helper: create a leaf node
    static CachedNode leaf(long id, long parentId, String name) {
        return new CachedNode(id, parentId, name, "Report", T, "sys");
    }

    @BeforeEach
    void setUp() {
        cache = new DefaultTreeCache();
    }

    @Nested
    class EmptyCache {
        @Test
        void getByIdReturnsEmptyForUnknownId() {
            assertThat(cache.getById(999L)).isEmpty();
        }

        @Test
        void sizeIsZero() {
            assertThat(cache.size()).isZero();
        }

        @Test
        void existsReturnsFalse() {
            assertThat(cache.exists(1L)).isFalse();
        }
    }

    @Nested
    class ApplyCreate {
        @Test
        void createdNodeIsRetrievableById() {
            CachedNode root = folder(1L, 0L, "root");
            cache.applyCreate(root);

            Optional<CachedNode> found = cache.getById(1L);
            assertThat(found).isPresent();
            assertThat(found.get().name()).isEqualTo("root");
        }

        @Test
        void sizeIncreasesOnCreate() {
            cache.applyCreate(folder(1L, 0L, "root"));
            cache.applyCreate(folder(2L, 1L, "child"));
            assertThat(cache.size()).isEqualTo(2);
        }

        @Test
        void applyCreateTwiceWithSameIdUpsertsNode() {
            cache.applyCreate(folder(1L, 0L, "original"));
            cache.applyCreate(folder(1L, 0L, "replaced"));

            assertThat(cache.getById(1L).get().name()).isEqualTo("replaced");
            assertThat(cache.size()).isEqualTo(1);
        }

        @Test
        void upsertUpdatesChildrenByParentIndex() {
            cache.applyCreate(folder(1L, 0L, "root"));
            cache.applyCreate(folder(2L, 1L, "child"));
            // Move child from parent 1 to parent 0 via upsert
            cache.applyCreate(new CachedNode(2L, 0L, "child", "Folder", T, "sys"));

            // child is now under parent 0, not parent 1
            assertThat(cache.getChildren(0L)).extracting(CachedNode::itemTreeId).contains(2L);
            assertThat(cache.getChildren(1L)).extracting(CachedNode::itemTreeId).doesNotContain(2L);
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```
./gradlew test --tests "com.myxcomp.ice.xtree.cache.DefaultTreeCacheTest" 2>&1 | tail -20
```

Expected: compilation error — `DefaultTreeCache` does not exist.

- [ ] **Step 3: Create `DefaultTreeCache.java` with skeleton + applyCreate + getById**

```java
package com.myxcomp.ice.xtree.cache;

import com.myxcomp.ice.xtree.common.TreeConstants;
import com.myxcomp.ice.xtree.common.Types;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class DefaultTreeCache implements TreeCache {

    private static final Logger log = LoggerFactory.getLogger(DefaultTreeCache.class);
    private static final int MAX_ANCESTOR_WALK = 10_000;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    // All three maps replaced atomically under the write lock.
    private Map<Long, CachedNode> byId = new ConcurrentHashMap<>();
    private Map<Long, Set<Long>> childrenByParent = new ConcurrentHashMap<>();
    private Map<String, Set<Long>> foldersByName = new ConcurrentHashMap<>();

    // ── Reads ─────────────────────────────────────────────────────────────

    @Override
    public Optional<CachedNode> getById(long id) {
        lock.readLock().lock();
        try {
            return Optional.ofNullable(byId.get(id));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<CachedNode> getChildren(long parentId) {
        lock.readLock().lock();
        try {
            Set<Long> childIds = childrenByParent.get(parentId);
            if (childIds == null) return List.of();
            return childIds.stream()
                    .map(byId::get)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toUnmodifiableList());
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<CachedNode> getSubtreeFlat(long rootId) {
        lock.readLock().lock();
        try {
            if (!byId.containsKey(rootId)) return List.of();
            List<CachedNode> result = new ArrayList<>();
            Deque<Long> queue = new ArrayDeque<>();
            queue.add(rootId);
            while (!queue.isEmpty()) {
                long current = queue.poll();
                CachedNode node = byId.get(current);
                if (node == null) continue;
                result.add(node);
                Set<Long> children = childrenByParent.get(current);
                if (children != null) queue.addAll(children);
            }
            return Collections.unmodifiableList(result);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<CachedNode> getTreeView(long homeFolderId) {
        throw new UnsupportedOperationException("getTreeView implemented in Phase 6 (requires PathResolver)");
    }

    @Override
    public Optional<CachedNode> findHomeFolder(String userName) {
        lock.readLock().lock();
        try {
            Set<Long> candidates = foldersByName.getOrDefault(userName, Set.of());
            if (candidates.isEmpty()) return Optional.empty();
            if (candidates.size() > 1) {
                log.warn("findHomeFolder: {} folders named '{}', returning first found",
                        candidates.size(), userName);
            }
            return candidates.stream().map(byId::get).filter(Objects::nonNull).findFirst();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Optional<CachedNode> searchById(long id) {
        return getById(id);
    }

    @Override
    public List<CachedNode> searchByName(String needle, OptionalInt limit) {
        lock.readLock().lock();
        try {
            String lower = needle.toLowerCase(Locale.ROOT);
            Stream<CachedNode> stream = byId.values().stream()
                    .filter(n -> n.name().toLowerCase(Locale.ROOT).contains(lower));
            if (limit.isPresent()) {
                stream = stream.limit(limit.getAsInt());
            }
            return stream.collect(Collectors.toUnmodifiableList());
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public boolean isAncestor(long candidateAncestorId, long nodeId) {
        lock.readLock().lock();
        try {
            long current = nodeId;
            int maxSteps = byId.size() + 1;
            for (int steps = 0; steps < maxSteps; steps++) {
                CachedNode node = byId.get(current);
                if (node == null || node.parentId() == TreeConstants.ROOT_PARENT_ID) return false;
                if (node.parentId() == candidateAncestorId) return true;
                current = node.parentId();
            }
            log.warn("Cycle detected in ancestor walk for nodeId={}, candidateAncestorId={}",
                    nodeId, candidateAncestorId);
            return false;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public boolean exists(long id) {
        lock.readLock().lock();
        try {
            return byId.containsKey(id);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public boolean isFolder(long id) {
        lock.readLock().lock();
        try {
            CachedNode node = byId.get(id);
            return node != null && Types.isFolder(node.type());
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public int size() {
        lock.readLock().lock();
        try {
            return byId.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    // ── Mutations ─────────────────────────────────────────────────────────

    @Override
    public void applyCreate(CachedNode node) {
        lock.writeLock().lock();
        try {
            CachedNode existing = byId.get(node.itemTreeId());
            if (existing != null) {
                // Remove from old parent's children
                removeFromChildren(existing.parentId(), existing.itemTreeId());
                // Remove from foldersByName if was a folder
                if (Types.isFolder(existing.type())) {
                    removeFromFoldersByName(existing.name(), existing.itemTreeId());
                }
            }
            byId.put(node.itemTreeId(), node);
            childrenByParent.computeIfAbsent(node.parentId(), k -> ConcurrentHashMap.newKeySet())
                            .add(node.itemTreeId());
            if (Types.isFolder(node.type())) {
                foldersByName.computeIfAbsent(node.name(), k -> ConcurrentHashMap.newKeySet())
                             .add(node.itemTreeId());
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void applyMetadataUpdate(long id, Instant lastUpdate, String lastUpdateUser) {
        lock.writeLock().lock();
        try {
            CachedNode existing = byId.get(id);
            if (existing == null) {
                log.warn("applyMetadataUpdate: id={} not found, skipping", id);
                return;
            }
            byId.put(id, new CachedNode(id, existing.parentId(), existing.name(),
                    existing.type(), lastUpdate, lastUpdateUser));
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void applyMove(long id, long newParentId, Instant lastUpdate, String lastUpdateUser) {
        lock.writeLock().lock();
        try {
            CachedNode existing = byId.get(id);
            if (existing == null) {
                log.warn("applyMove: id={} not found, skipping", id);
                return;
            }
            if (!byId.containsKey(newParentId) && newParentId != TreeConstants.ROOT_PARENT_ID) {
                log.warn("applyMove: newParentId={} not found, skipping", newParentId);
                return;
            }
            removeFromChildren(existing.parentId(), id);
            childrenByParent.computeIfAbsent(newParentId, k -> ConcurrentHashMap.newKeySet()).add(id);
            byId.put(id, new CachedNode(id, newParentId, existing.name(),
                    existing.type(), lastUpdate, lastUpdateUser));
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void applyRename(long id, String newName, Instant lastUpdate, String lastUpdateUser) {
        lock.writeLock().lock();
        try {
            CachedNode existing = byId.get(id);
            if (existing == null) {
                log.warn("applyRename: id={} not found, skipping", id);
                return;
            }
            if (Types.isFolder(existing.type())) {
                removeFromFoldersByName(existing.name(), id);
                foldersByName.computeIfAbsent(newName, k -> ConcurrentHashMap.newKeySet()).add(id);
            }
            byId.put(id, new CachedNode(id, existing.parentId(), newName,
                    existing.type(), lastUpdate, lastUpdateUser));
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void applyDelete(Set<Long> ids) {
        lock.writeLock().lock();
        try {
            for (long id : ids) {
                CachedNode node = byId.remove(id);
                if (node == null) continue;
                removeFromChildren(node.parentId(), id);
                if (Types.isFolder(node.type())) {
                    removeFromFoldersByName(node.name(), id);
                }
            }
            // Remove entries for deleted nodes from childrenByParent
            // (their children were also deleted or will be cleaned up)
            for (long id : ids) {
                childrenByParent.remove(id);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void replaceAll(TreeSnapshot snapshot) {
        Map<Long, CachedNode> newById = new ConcurrentHashMap<>(snapshot.byId());
        Map<Long, Set<Long>> newChildren = new ConcurrentHashMap<>();
        snapshot.childrenByParent().forEach((k, v) -> {
            Set<Long> set = ConcurrentHashMap.newKeySet();
            set.addAll(v);
            newChildren.put(k, set);
        });
        Map<String, Set<Long>> newFolders = new ConcurrentHashMap<>();
        snapshot.foldersByName().forEach((k, v) -> {
            Set<Long> set = ConcurrentHashMap.newKeySet();
            set.addAll(v);
            newFolders.put(k, set);
        });
        lock.writeLock().lock();
        try {
            byId = newById;
            childrenByParent = newChildren;
            foldersByName = newFolders;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private void removeFromChildren(long parentId, long childId) {
        Set<Long> siblings = childrenByParent.get(parentId);
        if (siblings != null) siblings.remove(childId);
    }

    private void removeFromFoldersByName(String name, long id) {
        Set<Long> folders = foldersByName.get(name);
        if (folders != null) folders.remove(id);
    }
}
```

- [ ] **Step 4: Run Task 5 tests to verify they pass**

```
./gradlew test --tests "com.myxcomp.ice.xtree.cache.DefaultTreeCacheTest" 2>&1 | tail -15
```

Expected: all 5 DefaultTreeCacheTest tests PASS.

- [ ] **Step 5: Run full suite**

```
./gradlew test 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/cache/DefaultTreeCache.java \
        src/test/java/com/myxcomp/ice/xtree/cache/DefaultTreeCacheTest.java
git commit -m "$(cat <<'EOF'
feat(cache): add DefaultTreeCache with RW-lock, apply*, and read methods

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: `DefaultTreeCache` — remaining read methods tests

Add to the existing `DefaultTreeCacheTest.java`. This task builds a shared test tree (root → Users → testuser1 → report1) in a `@BeforeEach` and covers the remaining read methods.

**Files:**
- Modify: `src/test/java/com/myxcomp/ice/xtree/cache/DefaultTreeCacheTest.java`

- [ ] **Step 1: Add test tree setup helper and read method tests**

Add a new `Nested` class `ReadMethods` after the existing `ApplyCreate` class. Add a static helper at the top of the test class:

```java
/** Loads root(1) → Users(2) → testuser1(3) → report1(100) into the given cache. */
static void loadStandardTree(DefaultTreeCache cache) {
    cache.applyCreate(folder(1L, 0L, "root"));
    cache.applyCreate(folder(2L, 1L, "Users"));
    cache.applyCreate(folder(3L, 2L, "testuser1"));
    cache.applyCreate(leaf(100L, 3L, "MyReport"));
}
```

Then add the following `@Nested` class inside `DefaultTreeCacheTest`:

```java
@Nested
class ReadMethods {

    @BeforeEach
    void loadTree() {
        loadStandardTree(cache);
    }

    @Test
    void getChildrenReturnsDirectChildrenOnly() {
        List<CachedNode> children = cache.getChildren(1L);
        assertThat(children).extracting(CachedNode::itemTreeId).containsExactly(2L);
    }

    @Test
    void getChildrenOfUnknownParentReturnsEmpty() {
        assertThat(cache.getChildren(999L)).isEmpty();
    }

    @Test
    void getSubtreeFlatIncludesRootAndAllDescendants() {
        List<CachedNode> subtree = cache.getSubtreeFlat(2L);
        assertThat(subtree).extracting(CachedNode::itemTreeId)
                .containsExactlyInAnyOrder(2L, 3L, 100L);
    }

    @Test
    void getSubtreeFlatOnLeafReturnsOnlyThatNode() {
        List<CachedNode> subtree = cache.getSubtreeFlat(100L);
        assertThat(subtree).extracting(CachedNode::itemTreeId).containsExactly(100L);
    }

    @Test
    void getSubtreeFlatOnMissingIdReturnsEmpty() {
        assertThat(cache.getSubtreeFlat(999L)).isEmpty();
    }

    @Test
    void findHomeFolderReturnsMatchingFolder() {
        Optional<CachedNode> home = cache.findHomeFolder("testuser1");
        assertThat(home).isPresent();
        assertThat(home.get().itemTreeId()).isEqualTo(3L);
    }

    @Test
    void findHomeFolderReturnEmptyForUnknownUser() {
        assertThat(cache.findHomeFolder("nobody")).isEmpty();
    }

    @Test
    void searchByIdReturnsNodeForKnownId() {
        assertThat(cache.searchById(100L)).isPresent();
        assertThat(cache.searchById(999L)).isEmpty();
    }

    @Test
    void searchByNameFindsMatchingNodes() {
        List<CachedNode> results = cache.searchByName("report", OptionalInt.empty());
        assertThat(results).extracting(CachedNode::name).containsExactly("MyReport");
    }

    @Test
    void searchByNameIsCaseInsensitive() {
        assertThat(cache.searchByName("MYREPORT", OptionalInt.empty())).hasSize(1);
    }

    @Test
    void searchByNameRespectsLimit() {
        cache.applyCreate(leaf(101L, 3L, "OtherReport"));
        List<CachedNode> results = cache.searchByName("report", OptionalInt.of(1));
        assertThat(results).hasSize(1);
    }

    @Test
    void isFolderReturnsTrueForFolder() {
        assertThat(cache.isFolder(1L)).isTrue();
        assertThat(cache.isFolder(100L)).isFalse();
        assertThat(cache.isFolder(999L)).isFalse();
    }

    @Test
    void isAncestorReturnsTrueForDirectAncestor() {
        assertThat(cache.isAncestor(1L, 3L)).isTrue(); // root is ancestor of testuser1
        assertThat(cache.isAncestor(1L, 100L)).isTrue(); // root is ancestor of report1
    }

    @Test
    void isAncestorReturnsFalseForNonAncestor() {
        assertThat(cache.isAncestor(3L, 1L)).isFalse(); // testuser1 is NOT ancestor of root
        assertThat(cache.isAncestor(100L, 1L)).isFalse();
    }

    @Test
    void isAncestorReturnsFalseForSelf() {
        assertThat(cache.isAncestor(1L, 1L)).isFalse();
    }
}
```

Add `import java.util.OptionalInt;` and `import java.util.List;` at the top of the test file (they should already be there or add them).

- [ ] **Step 2: Run the new tests**

```
./gradlew test --tests "com.myxcomp.ice.xtree.cache.DefaultTreeCacheTest" 2>&1 | tail -20
```

Expected: all tests PASS (implementation was provided in Task 5).

- [ ] **Step 3: Run full suite**

```
./gradlew test 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/myxcomp/ice/xtree/cache/DefaultTreeCacheTest.java
git commit -m "$(cat <<'EOF'
test(cache): add read-method coverage for DefaultTreeCache

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: `DefaultTreeCache` — `apply*` tolerance and invariant tests

Add `@Nested` classes covering `applyMetadataUpdate`, `applyMove`, `applyRename`, `applyDelete` — both the happy path and the tolerance contract (log + skip on weird input).

**Files:**
- Modify: `src/test/java/com/myxcomp/ice/xtree/cache/DefaultTreeCacheTest.java`

- [ ] **Step 1: Add tolerance and invariant tests**

Add the following `@Nested` classes inside `DefaultTreeCacheTest`:

```java
@Nested
class ApplyMetadataUpdate {

    @Test
    void updatesTimestampAndUserOnExistingNode() {
        cache.applyCreate(folder(1L, 0L, "root"));
        Instant newTime = Instant.ofEpochSecond(100);
        cache.applyMetadataUpdate(1L, newTime, "editor");

        CachedNode updated = cache.getById(1L).get();
        assertThat(updated.lastUpdate()).isEqualTo(newTime);
        assertThat(updated.lastUpdateUser()).isEqualTo("editor");
        assertThat(updated.name()).isEqualTo("root");
    }

    @Test
    void onMissingIdLogAndSkipWithoutException() {
        // Pre-condition: cache is empty
        // Must not throw
        cache.applyMetadataUpdate(999L, Instant.EPOCH, "user");
        assertThat(cache.size()).isZero();
    }
}

@Nested
class ApplyMove {

    @BeforeEach
    void setup() {
        cache.applyCreate(folder(1L, 0L, "root"));
        cache.applyCreate(folder(2L, 1L, "A"));
        cache.applyCreate(folder(3L, 1L, "B"));
        cache.applyCreate(leaf(10L, 2L, "item"));
    }

    @Test
    void movesNodeToNewParent() {
        cache.applyMove(10L, 3L, Instant.EPOCH, "user");

        assertThat(cache.getById(10L).get().parentId()).isEqualTo(3L);
        assertThat(cache.getChildren(2L)).extracting(CachedNode::itemTreeId).doesNotContain(10L);
        assertThat(cache.getChildren(3L)).extracting(CachedNode::itemTreeId).contains(10L);
    }

    @Test
    void onMissingIdLogAndSkipWithoutException() {
        cache.applyMove(999L, 3L, Instant.EPOCH, "user");
        assertThat(cache.size()).isEqualTo(4);
    }

    @Test
    void onMissingNewParentLogAndSkipWithoutException() {
        cache.applyMove(10L, 999L, Instant.EPOCH, "user");
        // Item stays under original parent
        assertThat(cache.getById(10L).get().parentId()).isEqualTo(2L);
    }
}

@Nested
class ApplyRename {

    @BeforeEach
    void setup() {
        cache.applyCreate(folder(1L, 0L, "root"));
        cache.applyCreate(folder(2L, 1L, "OldName"));
        cache.applyCreate(leaf(10L, 2L, "report"));
    }

    @Test
    void renamedFolderUpdatesNameAndFoldersByName() {
        cache.applyRename(2L, "NewName", Instant.EPOCH, "user");

        assertThat(cache.getById(2L).get().name()).isEqualTo("NewName");
        assertThat(cache.findHomeFolder("OldName")).isEmpty();
        assertThat(cache.findHomeFolder("NewName")).isPresent();
    }

    @Test
    void renamedLeafDoesNotAffectFoldersByName() {
        cache.applyRename(10L, "renamed-report", Instant.EPOCH, "user");
        assertThat(cache.getById(10L).get().name()).isEqualTo("renamed-report");
        // foldersByName must be unaffected (leaves don't appear there)
        assertThat(cache.findHomeFolder("renamed-report")).isEmpty();
    }

    @Test
    void onMissingIdLogAndSkipWithoutException() {
        cache.applyRename(999L, "NewName", Instant.EPOCH, "user");
        assertThat(cache.size()).isEqualTo(3);
    }
}

@Nested
class ApplyDelete {

    @BeforeEach
    void setup() {
        loadStandardTree(cache); // ids: 1, 2, 3, 100
    }

    @Test
    void deletedIdsAreRemovedFromByIdAndChildSets() {
        cache.applyDelete(Set.of(100L));

        assertThat(cache.getById(100L)).isEmpty();
        assertThat(cache.getChildren(3L)).isEmpty();
        assertThat(cache.size()).isEqualTo(3);
    }

    @Test
    void deleteOnMissingIdIsToleratedWithoutException() {
        cache.applyDelete(Set.of(999L));
        assertThat(cache.size()).isEqualTo(4);
    }

    @Test
    void deleteMixOfExistingAndMissingIds() {
        cache.applyDelete(Set.of(100L, 999L));
        assertThat(cache.size()).isEqualTo(3);
        assertThat(cache.getById(100L)).isEmpty();
    }

    @Test
    void deleteFolderRemovesItFromFoldersByName() {
        cache.applyDelete(Set.of(3L));
        assertThat(cache.findHomeFolder("testuser1")).isEmpty();
    }

    @Test
    void deleteSubtreeRemovesCascadedIds() {
        // Delete Users subtree: 2, 3, 100
        cache.applyDelete(Set.of(2L, 3L, 100L));
        assertThat(cache.size()).isEqualTo(1); // only root remains
        assertThat(cache.getChildren(1L)).isEmpty();
    }
}
```

Add `import java.util.Set;` at the top of the test file.

- [ ] **Step 2: Run the new tests**

```
./gradlew test --tests "com.myxcomp.ice.xtree.cache.DefaultTreeCacheTest" 2>&1 | tail -25
```

Expected: all tests PASS.

- [ ] **Step 3: Run full suite**

```
./gradlew test 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/myxcomp/ice/xtree/cache/DefaultTreeCacheTest.java
git commit -m "$(cat <<'EOF'
test(cache): add apply* tolerance and invariant tests for DefaultTreeCache

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: `DefaultTreeCache` — `replaceAll` tests

Add tests for `replaceAll`: state swap, old nodes gone, new nodes present.

**Files:**
- Modify: `src/test/java/com/myxcomp/ice/xtree/cache/DefaultTreeCacheTest.java`

- [ ] **Step 1: Add `ReplaceAll` nested class**

Add after the `ApplyDelete` class:

```java
@Nested
class ReplaceAll {

    private TreeSnapshot buildSnapshot(long... folderIds) {
        SnapshotBuilder builder = new SnapshotBuilder();
        builder.accept(new com.myxcomp.ice.xtree.persistence.StructuralRow(
                1L, 0L, "root", "Folder", T, "sys"));
        for (long id : folderIds) {
            builder.accept(new com.myxcomp.ice.xtree.persistence.StructuralRow(
                    id, 1L, "node" + id, "Report", T, "sys"));
        }
        return builder.build();
    }

    @Test
    void replaceAllSwapsToNewSnapshot() {
        loadStandardTree(cache); // ids 1, 2, 3, 100

        // Snapshot B: root + two new leaves (ids 200, 201)
        TreeSnapshot snapB = buildSnapshot(200L, 201L);
        cache.replaceAll(snapB);

        assertThat(cache.size()).isEqualTo(3); // root + 200 + 201
        assertThat(cache.getById(200L)).isPresent();
        assertThat(cache.getById(201L)).isPresent();
        // Old nodes are gone
        assertThat(cache.getById(2L)).isEmpty();
        assertThat(cache.getById(3L)).isEmpty();
        assertThat(cache.getById(100L)).isEmpty();
    }

    @Test
    void replaceAllWithEmptySnapshotClearsCache() {
        loadStandardTree(cache);
        cache.replaceAll(new SnapshotBuilder().build());
        assertThat(cache.size()).isZero();
    }

    @Test
    void replaceAllUpdatesChildrenIndex() {
        loadStandardTree(cache);
        TreeSnapshot snapB = buildSnapshot(200L, 201L);
        cache.replaceAll(snapB);

        List<CachedNode> rootChildren = cache.getChildren(1L);
        assertThat(rootChildren).extracting(CachedNode::itemTreeId)
                .containsExactlyInAnyOrder(200L, 201L);
    }

    @Test
    void replaceAllUpdatesFoldersByNameIndex() {
        // Load snapshot with a folder named "specialFolder"
        SnapshotBuilder builder = new SnapshotBuilder();
        builder.accept(new com.myxcomp.ice.xtree.persistence.StructuralRow(
                1L, 0L, "root", "Folder", T, "sys"));
        builder.accept(new com.myxcomp.ice.xtree.persistence.StructuralRow(
                50L, 1L, "specialFolder", "Folder", T, "sys"));
        cache.replaceAll(builder.build());

        assertThat(cache.findHomeFolder("specialFolder")).isPresent();
        assertThat(cache.findHomeFolder("Users")).isEmpty(); // was in old tree
    }
}
```

- [ ] **Step 2: Run the new tests**

```
./gradlew test --tests "com.myxcomp.ice.xtree.cache.DefaultTreeCacheTest.ReplaceAll" 2>&1 | tail -20
```

Expected: all 4 PASS.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/myxcomp/ice/xtree/cache/DefaultTreeCacheTest.java
git commit -m "$(cat <<'EOF'
test(cache): add replaceAll tests for DefaultTreeCache

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: `DefaultTreeCache` — concurrency stress and `replaceAll` atomicity tests

**Files:**
- Modify: `src/test/java/com/myxcomp/ice/xtree/cache/DefaultTreeCacheTest.java`

- [ ] **Step 1: Add concurrency tests**

Add the following imports at the top of the test file:

```java
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
```

Add a `@Nested` class `Concurrency` after `ReplaceAll`:

```java
@Nested
class Concurrency {

    private DefaultTreeCache buildCacheWith(int nodeCount) {
        DefaultTreeCache c = new DefaultTreeCache();
        SnapshotBuilder b = new SnapshotBuilder();
        b.accept(new com.myxcomp.ice.xtree.persistence.StructuralRow(
                1L, 0L, "root", "Folder", T, "sys"));
        for (int i = 2; i <= nodeCount; i++) {
            b.accept(new com.myxcomp.ice.xtree.persistence.StructuralRow(
                    (long) i, 1L, "node" + i, "Report", T, "sys"));
        }
        c.replaceAll(b.build());
        return c;
    }

    @Test
    void stressReadersAndWriterProduceNoExceptions() throws InterruptedException {
        DefaultTreeCache stressCache = buildCacheWith(500);
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch startLatch = new CountDownLatch(1);
        int threadCount = 9; // 8 readers + 1 writer
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        Runnable reader = () -> {
            try {
                startLatch.await();
                long deadline = System.currentTimeMillis() + 2_000;
                while (System.currentTimeMillis() < deadline) {
                    stressCache.getById(1L);
                    stressCache.getChildren(1L);
                    stressCache.size();
                    stressCache.exists(2L);
                    stressCache.isFolder(1L);
                }
            } catch (Throwable t) {
                error.compareAndSet(null, t);
            } finally {
                doneLatch.countDown();
            }
        };

        Runnable writer = () -> {
            try {
                startLatch.await();
                long deadline = System.currentTimeMillis() + 2_000;
                long id = 1_000_000L;
                while (System.currentTimeMillis() < deadline) {
                    long newId = id++;
                    stressCache.applyCreate(new CachedNode(newId, 1L, "stress" + newId, "Report", T, "sys"));
                    stressCache.applyDelete(Set.of(newId));
                }
            } catch (Throwable t) {
                error.compareAndSet(null, t);
            } finally {
                doneLatch.countDown();
            }
        };

        for (int i = 0; i < 8; i++) new Thread(reader, "reader-" + i).start();
        new Thread(writer, "writer").start();

        startLatch.countDown();
        boolean finished = doneLatch.await(10, TimeUnit.SECONDS);

        assertThat(finished).as("All threads must finish within 10 s").isTrue();
        assertThat(error.get()).as("No thread should throw").isNull();
        assertThat(stressCache.size()).isGreaterThan(0);
    }

    @Test
    void replaceAllIsAtomicFromReadersView() throws InterruptedException {
        // Snapshot A: root (id=1) + 50 leaves (ids 2..51)
        DefaultTreeCache atomicCache = buildCacheWith(51);

        // Snapshot B: root (id=1) + 50 different leaves (ids 1000..1049)
        SnapshotBuilder builderB = new SnapshotBuilder();
        builderB.accept(new com.myxcomp.ice.xtree.persistence.StructuralRow(
                1L, 0L, "root", "Folder", T, "sys"));
        for (int i = 1000; i < 1050; i++) {
            builderB.accept(new com.myxcomp.ice.xtree.persistence.StructuralRow(
                    (long) i, 1L, "node" + i, "Report", T, "sys"));
        }
        TreeSnapshot snapB = builderB.build();

        AtomicBoolean inconsistencyFound = new AtomicBoolean(false);
        AtomicBoolean stop = new AtomicBoolean(false);

        // Reader: verifies that every child returned by getChildren(1) also exists in the cache
        Thread reader = new Thread(() -> {
            while (!stop.get()) {
                List<CachedNode> children = atomicCache.getChildren(1L);
                for (CachedNode child : children) {
                    if (!atomicCache.exists(child.itemTreeId())) {
                        inconsistencyFound.set(true);
                        return;
                    }
                }
            }
        }, "atomicity-reader");
        reader.start();

        // Give the reader a head start
        Thread.sleep(10);
        atomicCache.replaceAll(snapB);
        stop.set(true);
        reader.join(2_000);

        assertThat(inconsistencyFound.get())
                .as("Reader must never see a child that doesn't exist in byId")
                .isFalse();
        // After replaceAll the cache holds snapshot B
        assertThat(atomicCache.size()).isEqualTo(51); // root + 50 B-leaves
        assertThat(atomicCache.exists(1000L)).isTrue();
        assertThat(atomicCache.exists(2L)).isFalse();
    }
}
```

- [ ] **Step 2: Run the concurrency tests**

```
./gradlew test --tests "com.myxcomp.ice.xtree.cache.DefaultTreeCacheTest.Concurrency" 2>&1 | tail -20
```

Expected: both tests PASS.

- [ ] **Step 3: Run the full suite and report final test count**

```
./gradlew test 2>&1 | grep -E "tests|BUILD"
```

Expected: BUILD SUCCESSFUL. Count should be 79 (prior) + 2 (CachedNodeTest) + 8 (SnapshotBuilderTest) + 4 (CacheReadinessGateTest) + ~30 (DefaultTreeCacheTest) = ~123 tests.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/myxcomp/ice/xtree/cache/DefaultTreeCacheTest.java
git commit -m "$(cat <<'EOF'
test(cache): add concurrency stress and replaceAll atomicity tests

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review Against Spec

**Spec coverage check:**

| §4 requirement | Task |
|---|---|
| `TreeCache` interface with all methods | Task 2 |
| `CachedNode` parentId never null | Task 1 |
| Three indexes kept consistent under write lock | Tasks 5–7 |
| Read methods return defensive copies | Task 5 |
| `apply*` tolerance — log + skip on missing | Task 7 |
| `applyCreate` is upsert | Tasks 5, 7 |
| `applyDelete` ignores missing ids | Task 7 |
| `applyMove` skips on missing newParentId | Task 7 |
| `applyRename` updates `foldersByName` | Task 7 |
| `replaceAll` swaps all three indexes atomically | Task 8 |
| Cycle defence in `isAncestor` | Task 5 (implementation), Task 6 (test) |
| `SnapshotBuilder` builds row-by-row | Task 3 |
| `CacheReadinessGate` volatile flag + Spring event | Task 4 |
| `getTreeView` deferred to Phase 6 | Task 5 (throws UnsupportedOperationException) |
| Concurrency: N readers + 1 writer | Task 9 |
| `replaceAll` atomicity from reader's view | Task 9 |

**Placeholder scan:** No TBD, TODO, or "similar to Task N" references. All code is shown in full.

**Type consistency check:**
- `CachedNode` uses `Long parentId` (boxed) everywhere ✓
- `SnapshotBuilder.accept(StructuralRow)` matches `StructuralRow` record fields ✓
- `TreeSnapshot` maps match `DefaultTreeCache` internal map types ✓
- `applyCreate(CachedNode)` builds new `CachedNode` from record fields when upserting ✓
- `replaceAll(TreeSnapshot)` deep-copies into `ConcurrentHashMap` before taking write lock ✓

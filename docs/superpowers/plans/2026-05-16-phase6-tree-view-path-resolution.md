# Phase 6 — `getTreeView` Algorithm + Path Resolution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement `DefaultTreeCache.getTreeView(long homeFolderId)` per design §8 and a `PathResolver` (interface + default implementation) per design §9, both with full edge-case and drift-defence coverage.

**Architecture:** `getTreeView` lives on the cache itself and acquires the cache's read lock once for the whole computation: it builds (skeleton ∪ chain ∪ home-children) into a `LinkedHashSet<Long>` and materialises in iteration order. `PathResolver` lives in `service/` and is the thin lazy-path-builder of design §9 — `pathOf(id)` does a single root-ward walk via `TreeCache#getById`, and `pathsOf(Collection<Long>)` reuses a per-call `Map<Long, String>` memo so a shared ancestor chain is walked once regardless of how many input ids share it. Both walks are bounded by an explicit max-depth cap and degrade gracefully on missing ancestors / cycles (log + partial result; no throw, except for the documented "missing home folder" case in `getTreeView`).

**Tech Stack:** Java 21, Spring (`@Component` for `DefaultPathResolver`), JUnit 5 + AssertJ (no Mockito needed — both implementations exercise against a real `DefaultTreeCache`).

---

## File Structure

### New production files
| Path | Responsibility |
|---|---|
| `src/main/java/com/myxcomp/ice/xtree/service/PathResolver.java` | Public interface with `String pathOf(long itemTreeId)` and `Map<Long, String> pathsOf(Collection<Long> ids)`. Documents the partial-result contract (missing id → empty string; missing-ancestor → partial path without root prefix; cycle → capped + logged). |
| `src/main/java/com/myxcomp/ice/xtree/service/DefaultPathResolver.java` | `@Component` impl backed by a `TreeCache` field. Walks parent chain via `cache.getById`; `pathsOf` builds a per-call `Map<Long, String>` memo so shared ancestors are walked once. Cap + log on cycle / missing ancestor. |

### Modified production files
| Path | Change |
|---|---|
| `src/main/java/com/myxcomp/ice/xtree/cache/DefaultTreeCache.java` | Replace the `UnsupportedOperationException` stub of `getTreeView` with the §8 algorithm under the read lock. Add a `MAX_TREE_DEPTH` private constant (= 10_000) used by the chain walk. Logs WARN on missing-ancestor and cycle. |
| `IMPLEMENTATION_NOTES.md` | Mark Phase 6 ✅ COMPLETE; record any deviations. Move the ⬅ NEXT marker to Phase 7. |

### Test files
| Path | Coverage |
|---|---|
| `src/test/java/com/myxcomp/ice/xtree/cache/DefaultTreeCacheGetTreeViewTest.java` | New test class focused on `getTreeView`. `@Nested` groups: `HappyPath` (depth-0/1/2/deep home folders), `Composition` (skeleton membership / non-folder filtering / dedup with overlapping sources / mixed-type home children / empty home), `Drift` (missing home throws, missing ancestor mid-chain logs + partial, cycle defence). |
| `src/test/java/com/myxcomp/ice/xtree/service/DefaultPathResolverTest.java` | New test class. `@Nested` groups: `PathOf` (root, depth 1, depth 2, deep, missing id, orphan-parent, cycle), `PathsOf` (empty input, single id, batch, duplicate ids, memoisation proven via shared-ancestor batch). |

`DefaultTreeCacheGetTreeViewTest` is split out from `DefaultTreeCacheTest` because the `getTreeView` setup is substantial (multi-level fixture trees) and the existing test class is already large.

---

## Conventions used by the rest of this plan

- **Test fixture:** the standard tree built by `loadStandardTree(cache)` already exists in `DefaultTreeCacheTest`; we replicate the same helpers (`folder`, `leaf`) inline in the new test classes (do not import — keep classes self-contained).
- **Path format:** `"root/Folder1/Item"` — no leading `/`, segments joined by `/`.
- **Constants reused:** `TreeConstants.ROOT_PARENT_ID` (= `0L`), `TreeConstants.ROOT_ID` (= `1L`), `Types.isFolder(String)`.
- **Logger:** SLF4J `LoggerFactory.getLogger(<owning class>.class)`; same pattern as elsewhere.
- **No metric wiring in this phase.** Phase 12 wires Micrometer counters where the design (§8) calls for them. WARN log lines stand in for now and are the hook Phase 12 will instrument alongside.

---

## Task 1: `getTreeView` happy path — depth-2 home folder

**Why first:** Establishes the full result-set composition (skeleton ∪ chain ∪ home-children) against the most representative case. All later refinements layer onto this.

**Files:**
- Create: `src/test/java/com/myxcomp/ice/xtree/cache/DefaultTreeCacheGetTreeViewTest.java`
- Modify: `src/main/java/com/myxcomp/ice/xtree/cache/DefaultTreeCache.java`

- [ ] **Step 1: Write the failing test class with the depth-2 happy-path case**

Create `src/test/java/com/myxcomp/ice/xtree/cache/DefaultTreeCacheGetTreeViewTest.java`:

```java
package com.myxcomp.ice.xtree.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class DefaultTreeCacheGetTreeViewTest {

    DefaultTreeCache cache;
    static final Instant T = Instant.EPOCH;

    static CachedNode folder(long id, long parentId, String name) {
        return new CachedNode(id, parentId, name, "Folder", T, "sys");
    }

    static CachedNode leaf(long id, long parentId, String name) {
        return new CachedNode(id, parentId, name, "Report", T, "sys");
    }

    /**
     * Standard fixture matching dev profile data.sql (subset relevant to getTreeView):
     *   root(1)
     *     ├─ Users(2)
     *     │    ├─ testuser1(10)
     *     │    │    └─ MyReport(110)            // home-child of testuser1
     *     │    └─ deepuser(12)
     *     │         └─ L2(20)
     *     │              └─ L3(21)
     *     │                   └─ L4(22)
     *     │                        └─ L5(23)
     *     │                             └─ L6(24)
     *     │                                  └─ leafItem(25) // home-child of L6
     *     ├─ Reports(3)
     *     │    ├─ ReportSubA(30)               // depth-2 folder, in skeleton
     *     │    └─ Report1(41)                  // non-folder, must NOT be in skeleton
     *     ├─ Filters(4)
     *     └─ Datasets(6)
     *          └─ DrillDownSet1(40)            // non-folder under Datasets
     */
    static void loadFixture(DefaultTreeCache c) {
        c.applyCreate(folder(1L,  0L, "root"));
        c.applyCreate(folder(2L,  1L, "Users"));
        c.applyCreate(folder(3L,  1L, "Reports"));
        c.applyCreate(folder(4L,  1L, "Filters"));
        c.applyCreate(folder(6L,  1L, "Datasets"));
        c.applyCreate(folder(10L, 2L, "testuser1"));
        c.applyCreate(folder(12L, 2L, "deepuser"));
        c.applyCreate(folder(20L, 12L, "L2"));
        c.applyCreate(folder(21L, 20L, "L3"));
        c.applyCreate(folder(22L, 21L, "L4"));
        c.applyCreate(folder(23L, 22L, "L5"));
        c.applyCreate(folder(24L, 23L, "L6"));
        c.applyCreate(folder(30L, 3L, "ReportSubA"));
        c.applyCreate(leaf  (41L, 3L, "Report1"));
        c.applyCreate(leaf  (40L, 6L, "DrillDownSet1"));
        c.applyCreate(leaf  (110L, 10L, "MyReport"));
        c.applyCreate(leaf  (25L, 24L, "leafItem"));
    }

    @BeforeEach
    void setUp() {
        cache = new DefaultTreeCache();
    }

    @Nested
    class HappyPath {

        @Test
        void depth2HomeFolderReturnsSkeletonChainAndHomeChildren() {
            loadFixture(cache);

            // testuser1 (id=10) is at depth 2 (root → Users → testuser1).
            List<CachedNode> view = cache.getTreeView(10L);

            // Expected membership:
            //   skeleton (depths 0,1,2 folders): 1, 2, 3, 4, 6, 10, 12, 30
            //   chain (root → home):             1, 2, 10              [all already in skeleton]
            //   home children of testuser1:      110
            assertThat(view).extracting(CachedNode::itemTreeId)
                    .containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 6L, 10L, 12L, 30L, 110L);
        }
    }
}
```

- [ ] **Step 2: Run the test and verify it fails on the stub**

Run: `./gradlew test --tests "com.myxcomp.ice.xtree.cache.DefaultTreeCacheGetTreeViewTest.HappyPath.depth2HomeFolderReturnsSkeletonChainAndHomeChildren"`
Expected: FAIL with `UnsupportedOperationException: getTreeView implemented in Phase 6`.

- [ ] **Step 3: Implement `getTreeView` in `DefaultTreeCache`**

In `src/main/java/com/myxcomp/ice/xtree/cache/DefaultTreeCache.java`:

Add the new constant near the existing `MAX_ANCESTOR_WALK`:

```java
    /** Defensive cap for the chain walk in {@link #getTreeView}; effective cap is min(cache-size+1, this). */
    private static final int MAX_TREE_DEPTH = 10_000;
```

Replace the existing `getTreeView` stub:

```java
    @Override
    public List<CachedNode> getTreeView(long homeFolderId) {
        throw new UnsupportedOperationException("getTreeView implemented in Phase 6");
    }
```

with:

```java
    @Override
    public List<CachedNode> getTreeView(long homeFolderId) {
        lock.readLock().lock();
        try {
            CachedNode home = byId.get(homeFolderId);
            if (home == null) {
                throw new IllegalArgumentException("Home folder not found in cache: " + homeFolderId);
            }

            LinkedHashSet<Long> resultIds = new LinkedHashSet<>();
            addSkeletonFolders(resultIds);
            addChainRootToHome(resultIds, home, homeFolderId);
            resultIds.addAll(childrenByParent.getOrDefault(homeFolderId, Set.of()));

            return resultIds.stream()
                    .map(byId::get)
                    .filter(Objects::nonNull)
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Adds folder ids at depths 0, 1, 2 (root, its folder children, their folder grandchildren). */
    private void addSkeletonFolders(LinkedHashSet<Long> sink) {
        for (Long depth0Id : childrenByParent.getOrDefault(TreeConstants.ROOT_PARENT_ID, Set.of())) {
            CachedNode depth0 = byId.get(depth0Id);
            if (depth0 == null || !Types.isFolder(depth0.type())) continue;
            sink.add(depth0Id);
            for (Long depth1Id : childrenByParent.getOrDefault(depth0Id, Set.of())) {
                CachedNode depth1 = byId.get(depth1Id);
                if (depth1 == null || !Types.isFolder(depth1.type())) continue;
                sink.add(depth1Id);
                for (Long depth2Id : childrenByParent.getOrDefault(depth1Id, Set.of())) {
                    CachedNode depth2 = byId.get(depth2Id);
                    if (depth2 == null || !Types.isFolder(depth2.type())) continue;
                    sink.add(depth2Id);
                }
            }
        }
    }

    /** Walks home → root, then inserts the chain into {@code sink} in root → home order. */
    private void addChainRootToHome(LinkedHashSet<Long> sink, CachedNode home, long homeFolderId) {
        List<Long> chain = new ArrayList<>();
        int maxWalk = Math.min(byId.size() + 1, MAX_TREE_DEPTH);
        CachedNode cursor = home;
        int steps = 0;
        while (cursor != null) {
            chain.add(cursor.itemTreeId());
            if (cursor.parentId() == TreeConstants.ROOT_PARENT_ID) {
                break;
            }
            if (++steps > maxWalk) {
                log.warn("getTreeView: ancestor-walk cap reached at homeFolderId={}, possible cycle",
                        homeFolderId);
                break;
            }
            CachedNode parent = byId.get(cursor.parentId());
            if (parent == null) {
                log.warn("getTreeView: missing ancestor parentId={} for nodeId={} (homeFolderId={})",
                        cursor.parentId(), cursor.itemTreeId(), homeFolderId);
                break;
            }
            cursor = parent;
        }
        Collections.reverse(chain);
        sink.addAll(chain);
    }
```

The existing `import java.util.*;` already covers `LinkedHashSet`, `ArrayList`, `Collections`, `Set`, `Objects`. No new imports are needed.

- [ ] **Step 4: Run the test and verify it passes**

Run: `./gradlew test --tests "com.myxcomp.ice.xtree.cache.DefaultTreeCacheGetTreeViewTest.HappyPath.depth2HomeFolderReturnsSkeletonChainAndHomeChildren"`
Expected: PASS.

- [ ] **Step 5: Run the full cache test suite to confirm no regressions**

Run: `./gradlew test --tests "com.myxcomp.ice.xtree.cache.*"`
Expected: PASS — all existing cache tests + the new one.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/cache/DefaultTreeCache.java \
        src/test/java/com/myxcomp/ice/xtree/cache/DefaultTreeCacheGetTreeViewTest.java
git commit -m "feat(cache): implement getTreeView per design §8"
```

---

## Task 2: `getTreeView` depth coverage and ordering

**Why second:** The §8 spec calls out depth 0, depth 1, and "deep" (e.g., 7) as distinct cases. Each exercises a different chain-walk length and overlap pattern with the skeleton. We also pin the documented ordering invariants from the design's "Output ordering" note.

**Files:**
- Modify: `src/test/java/com/myxcomp/ice/xtree/cache/DefaultTreeCacheGetTreeViewTest.java`

- [ ] **Step 1: Add depth-0 / depth-1 / deep tests to the `HappyPath` nested class**

Add the following methods inside the existing `@Nested class HappyPath { ... }` block (alongside the depth-2 test from Task 1):

```java
        @Test
        void depth0HomeFolderIsTheRoot() {
            loadFixture(cache);

            List<CachedNode> view = cache.getTreeView(1L);

            // Skeleton already contains root + depth-1 folders + depth-2 folders.
            // Chain is just [root]; home children are root's direct children.
            // Root's direct children: 2 (Users), 3 (Reports), 4 (Filters), 6 (Datasets) — all in skeleton already.
            assertThat(view).extracting(CachedNode::itemTreeId)
                    .containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 6L, 10L, 12L, 30L);
        }

        @Test
        void depth1HomeFolderReportsAddsNonFolderChild() {
            loadFixture(cache);

            // Reports (id=3) is at depth 1. Its direct children are ReportSubA(30, folder)
            // and Report1(41, leaf). Both must appear in the result.
            List<CachedNode> view = cache.getTreeView(3L);

            assertThat(view).extracting(CachedNode::itemTreeId)
                    .containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 6L, 10L, 12L, 30L, 41L);
        }

        @Test
        void deepHomeFolderL6IncludesFullChainAndItsChildren() {
            loadFixture(cache);

            // L6 (id=24) is at depth 7: root(1) → Users(2) → deepuser(12) → L2(20)
            //   → L3(21) → L4(22) → L5(23) → L6(24). Its child is leafItem(25).
            List<CachedNode> view = cache.getTreeView(24L);

            assertThat(view).extracting(CachedNode::itemTreeId)
                    .containsExactlyInAnyOrder(
                            1L, 2L, 3L, 4L, 6L, 10L, 12L, 30L,   // skeleton (depths 0-2)
                            20L, 21L, 22L, 23L, 24L,              // chain segment beyond skeleton
                            25L                                    // L6's child
                    );
        }

        @Test
        void chainAppearsInRootToHomeOrder() {
            loadFixture(cache);

            List<Long> ids = cache.getTreeView(24L).stream().map(CachedNode::itemTreeId).toList();

            // Chain root→L6: 1, 2, 12, 20, 21, 22, 23, 24. They must appear in that relative order
            // in the result. (Not necessarily contiguous: 3, 4, 6, 10, 30 from the skeleton may
            // be interleaved between 1, 2, and 12 — only relative order of the chain matters.)
            List<Long> chainOrder = List.of(1L, 2L, 12L, 20L, 21L, 22L, 23L, 24L);
            List<Integer> positions = chainOrder.stream().map(ids::indexOf).toList();
            assertThat(positions).doesNotContain(-1); // every chain id is present
            for (int i = 1; i < positions.size(); i++) {
                assertThat(positions.get(i))
                        .as("chain element %s must appear after %s", chainOrder.get(i), chainOrder.get(i - 1))
                        .isGreaterThan(positions.get(i - 1));
            }
        }
```

- [ ] **Step 2: Run the new tests**

Run: `./gradlew test --tests "com.myxcomp.ice.xtree.cache.DefaultTreeCacheGetTreeViewTest.HappyPath.*"`
Expected: PASS — four new tests plus the one from Task 1.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/myxcomp/ice/xtree/cache/DefaultTreeCacheGetTreeViewTest.java
git commit -m "test(cache): cover getTreeView at depth 0/1/deep and chain ordering"
```

---

## Task 3: `getTreeView` composition rules — non-folder filter, dedup, empty / mixed home children

**Why third:** These pin the §8 invariants that aren't captured by the depth tests: skeleton excludes non-folders, the same id never appears twice even when sources overlap, mixed-type home children all flow through, and an empty home folder degrades to skeleton ∪ chain.

**Files:**
- Modify: `src/test/java/com/myxcomp/ice/xtree/cache/DefaultTreeCacheGetTreeViewTest.java`

- [ ] **Step 1: Add a `Composition` `@Nested` class with the four invariants**

Add this `@Nested` class inside `DefaultTreeCacheGetTreeViewTest` (alongside `HappyPath`):

```java
    @Nested
    class Composition {

        @Test
        void skeletonExcludesNonFolderChildrenOfRoot() {
            // Pure first-level layout: root + a folder + a non-folder leaf.
            cache.applyCreate(folder(1L, 0L, "root"));
            cache.applyCreate(folder(2L, 1L, "FolderA"));
            cache.applyCreate(leaf  (3L, 1L, "LeafA"));

            // Home is FolderA (depth 1).
            List<CachedNode> view = cache.getTreeView(2L);

            // Skeleton must include root and FolderA but NOT LeafA (LeafA is a non-folder
            // depth-1 node). FolderA is the home; it has no children.
            assertThat(view).extracting(CachedNode::itemTreeId)
                    .containsExactlyInAnyOrder(1L, 2L);
        }

        @Test
        void skeletonExcludesNonFolderGrandchildren() {
            // root → Outer (folder) → InnerLeaf (non-folder).
            cache.applyCreate(folder(1L, 0L, "root"));
            cache.applyCreate(folder(2L, 1L, "Outer"));
            cache.applyCreate(leaf  (3L, 2L, "InnerLeaf"));

            // Home = Outer (depth 1). InnerLeaf is a depth-2 non-folder; it must NOT be in
            // the skeleton, but it IS a direct child of the home folder, so it must appear
            // via the home-children source.
            List<CachedNode> view = cache.getTreeView(2L);

            assertThat(view).extracting(CachedNode::itemTreeId)
                    .containsExactlyInAnyOrder(1L, 2L, 3L);

            // Now move the home one level up so InnerLeaf is no longer a direct child of home —
            // then InnerLeaf must drop out (still not in skeleton, not in home-children).
            List<CachedNode> rootView = cache.getTreeView(1L);
            assertThat(rootView).extracting(CachedNode::itemTreeId)
                    .containsExactlyInAnyOrder(1L, 2L);
        }

        @Test
        void duplicateIdsAcrossSourcesAppearOnlyOnce() {
            loadFixture(cache);

            // Users (id=2) is at depth 1: it is in the skeleton AND in the chain
            // when the home folder is its descendant. The result must contain id=2 exactly once.
            List<CachedNode> view = cache.getTreeView(10L); // testuser1

            long usersCount = view.stream().filter(n -> n.itemTreeId() == 2L).count();
            assertThat(usersCount).isEqualTo(1);
        }

        @Test
        void mixedTypeHomeChildrenAllAppear() {
            cache.applyCreate(folder(1L, 0L, "root"));
            cache.applyCreate(folder(2L, 1L, "Home"));
            cache.applyCreate(folder(10L, 2L, "SubFolder")); // child folder
            cache.applyCreate(leaf  (11L, 2L, "Report"));    // child leaf
            cache.applyCreate(leaf  (12L, 2L, "Filter"));    // child leaf

            List<CachedNode> view = cache.getTreeView(2L);

            assertThat(view).extracting(CachedNode::itemTreeId)
                    .containsExactlyInAnyOrder(1L, 2L, 10L, 11L, 12L);
        }

        @Test
        void emptyHomeFolderReturnsSkeletonAndChainOnly() {
            cache.applyCreate(folder(1L, 0L, "root"));
            cache.applyCreate(folder(2L, 1L, "Home")); // no children

            List<CachedNode> view = cache.getTreeView(2L);

            assertThat(view).extracting(CachedNode::itemTreeId)
                    .containsExactlyInAnyOrder(1L, 2L);
        }
    }
```

- [ ] **Step 2: Run the new tests**

Run: `./gradlew test --tests "com.myxcomp.ice.xtree.cache.DefaultTreeCacheGetTreeViewTest.Composition.*"`
Expected: PASS — five new tests.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/myxcomp/ice/xtree/cache/DefaultTreeCacheGetTreeViewTest.java
git commit -m "test(cache): cover getTreeView composition rules (filter, dedup, mixed children)"
```

---

## Task 4: `getTreeView` drift defence — missing home, missing ancestor, cycle

**Why fourth:** The "missing home throws" branch and the two "warn + partial" branches are explicit design contracts. Without tests, a future refactor could silently swap them.

**Files:**
- Modify: `src/test/java/com/myxcomp/ice/xtree/cache/DefaultTreeCacheGetTreeViewTest.java`

- [ ] **Step 1: Add a `Drift` `@Nested` class**

Add this `@Nested` class inside `DefaultTreeCacheGetTreeViewTest`:

```java
    @Nested
    class Drift {

        @Test
        void missingHomeFolderThrowsIllegalArgumentException() {
            cache.applyCreate(folder(1L, 0L, "root"));

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> cache.getTreeView(999L))
                    .withMessageContaining("999");
        }

        @Test
        void missingAncestorMidWalkReturnsPartialResultWithoutThrowing() {
            cache.applyCreate(folder(1L, 0L, "root"));
            cache.applyCreate(folder(2L, 1L, "Users"));
            // Construct an orphan: parentId=999 doesn't exist. The walk hits a missing
            // ancestor on its first step and stops cleanly.
            cache.applyCreate(folder(50L, 999L, "Orphan"));
            cache.applyCreate(leaf  (51L, 50L, "OrphanChild"));

            List<CachedNode> view = cache.getTreeView(50L);

            // Skeleton: 1, 2 (root + Users). Chain stops after Orphan (id=50) — partial.
            // Home children: 51.
            assertThat(view).extracting(CachedNode::itemTreeId)
                    .containsExactlyInAnyOrder(1L, 2L, 50L, 51L);
        }

        @Test
        void cycleInParentChainIsCappedAndReturnsPartial() {
            // Two-node cycle injected via direct upsert: A.parent=B and B.parent=A.
            // We bypass normal mutation paths (which don't enforce graph shape) — applyCreate
            // is upsert and accepts any parentId.
            cache.applyCreate(folder(1L, 0L, "root"));
            cache.applyCreate(folder(100L, 200L, "A")); // A.parent = B
            cache.applyCreate(folder(200L, 100L, "B")); // B.parent = A — cycle established

            // getTreeView on A: walk goes A → B → A → B ... and must terminate at the cap.
            // No throw; result is the skeleton portion (root only at depth 0; A and B are not
            // in skeleton because their parents are not in {root}'s subtree at the right depth)
            // plus whatever the chain walk collected before the cap fired plus A's children.
            List<CachedNode> view = cache.getTreeView(100L);

            // Just assert no throw and that the result contains at least the skeleton (root)
            // and the home folder itself (always added via the chain's first step).
            assertThat(view).extracting(CachedNode::itemTreeId).contains(1L, 100L);
        }
    }
```

- [ ] **Step 2: Run the new tests**

Run: `./gradlew test --tests "com.myxcomp.ice.xtree.cache.DefaultTreeCacheGetTreeViewTest.Drift.*"`
Expected: PASS — three tests.

- [ ] **Step 3: Run the full cache test suite to verify no regressions**

Run: `./gradlew test --tests "com.myxcomp.ice.xtree.cache.*"`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/myxcomp/ice/xtree/cache/DefaultTreeCacheGetTreeViewTest.java
git commit -m "test(cache): cover getTreeView drift defence (missing home, missing ancestor, cycle)"
```

---

## Task 5: `PathResolver` interface

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/service/PathResolver.java`

- [ ] **Step 1: Create the interface**

Create `src/main/java/com/myxcomp/ice/xtree/service/PathResolver.java`:

```java
package com.myxcomp.ice.xtree.service;

import java.util.Collection;
import java.util.Map;

/**
 * Lazily computes root-anchored, slash-separated paths for cache nodes (e.g. {@code "root/Users/testuser1"}).
 *
 * <p>Per design §9, paths are not stored on {@link com.myxcomp.ice.xtree.cache.CachedNode} — they are
 * recomputed from the parent chain at response-time on the {@code /tree} and {@code /tree/{rootId}/subtree}
 * endpoints. Walks are bounded; cycles and missing ancestors degrade to a partial path with a WARN log
 * rather than throwing.
 *
 * <p>Behaviour for unknown / orphan inputs:
 * <ul>
 *   <li>If the input id is not in the cache, {@link #pathOf(long)} returns the empty string and
 *       {@link #pathsOf(Collection)} maps it to the empty string.</li>
 *   <li>If a parent in the chain disappears mid-walk, the partial path collected so far (without the
 *       missing root prefix) is returned and a WARN line is logged.</li>
 *   <li>If the walk reaches an internal depth cap (suspected cycle), the partial path is returned
 *       and a WARN line is logged.</li>
 * </ul>
 */
public interface PathResolver {

    /** Returns the path for {@code itemTreeId}. See class Javadoc for partial-result semantics. */
    String pathOf(long itemTreeId);

    /**
     * Returns the path for each id in {@code ids}. Implementations memoise ancestor walks within a
     * single call so that a shared ancestor chain is walked once regardless of how many input ids
     * share it. Each input id appears as a key in the returned map; duplicate ids are collapsed.
     */
    Map<Long, String> pathsOf(Collection<Long> ids);
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/service/PathResolver.java
git commit -m "feat(service): add PathResolver interface (design §9)"
```

---

## Task 6: `DefaultPathResolver.pathOf` — single-id walk

**Why first within PathResolver:** `pathsOf` is implemented in terms of (or co-implemented with) `pathOf`'s walk logic. Get the single-id case correct first, then layer the memo on top.

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/service/DefaultPathResolver.java`
- Create: `src/test/java/com/myxcomp/ice/xtree/service/DefaultPathResolverTest.java`

- [ ] **Step 1: Write the failing test class with `pathOf` happy-path cases**

Create `src/test/java/com/myxcomp/ice/xtree/service/DefaultPathResolverTest.java`:

```java
package com.myxcomp.ice.xtree.service;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.DefaultTreeCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultPathResolverTest {

    DefaultTreeCache cache;
    DefaultPathResolver resolver;
    static final Instant T = Instant.EPOCH;

    static CachedNode folder(long id, long parentId, String name) {
        return new CachedNode(id, parentId, name, "Folder", T, "sys");
    }

    static CachedNode leaf(long id, long parentId, String name) {
        return new CachedNode(id, parentId, name, "Report", T, "sys");
    }

    /** Same fixture shape as the cache test, restricted to the chains the resolver tests touch. */
    static void loadFixture(DefaultTreeCache c) {
        c.applyCreate(folder(1L,  0L, "root"));
        c.applyCreate(folder(2L,  1L, "Users"));
        c.applyCreate(folder(10L, 2L, "testuser1"));
        c.applyCreate(folder(12L, 2L, "deepuser"));
        c.applyCreate(folder(20L, 12L, "L2"));
        c.applyCreate(folder(21L, 20L, "L3"));
        c.applyCreate(folder(22L, 21L, "L4"));
        c.applyCreate(leaf  (110L, 10L, "MyReport"));
        c.applyCreate(leaf  (210L, 22L, "DeepReport"));
    }

    @BeforeEach
    void setUp() {
        cache = new DefaultTreeCache();
        resolver = new DefaultPathResolver(cache);
    }

    @Nested
    class PathOf {

        @Test
        void rootReturnsItsName() {
            loadFixture(cache);
            assertThat(resolver.pathOf(1L)).isEqualTo("root");
        }

        @Test
        void depth1ReturnsRootSlashName() {
            loadFixture(cache);
            assertThat(resolver.pathOf(2L)).isEqualTo("root/Users");
        }

        @Test
        void depth2ReturnsThreeSegments() {
            loadFixture(cache);
            assertThat(resolver.pathOf(10L)).isEqualTo("root/Users/testuser1");
        }

        @Test
        void deepLeafReturnsFullPath() {
            loadFixture(cache);
            assertThat(resolver.pathOf(210L))
                    .isEqualTo("root/Users/deepuser/L2/L3/L4/DeepReport");
        }

        @Test
        void unknownIdReturnsEmptyString() {
            loadFixture(cache);
            assertThat(resolver.pathOf(999L)).isEmpty();
        }
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails on the missing class**

Run: `./gradlew test --tests "com.myxcomp.ice.xtree.service.DefaultPathResolverTest.PathOf.*"`
Expected: FAIL — `cannot find symbol class DefaultPathResolver`.

- [ ] **Step 3: Implement `DefaultPathResolver` with `pathOf` only (stub `pathsOf`)**

Create `src/main/java/com/myxcomp/ice/xtree/service/DefaultPathResolver.java`:

```java
package com.myxcomp.ice.xtree.service;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.TreeCache;
import com.myxcomp.ice.xtree.common.TreeConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class DefaultPathResolver implements PathResolver {

    private static final Logger log = LoggerFactory.getLogger(DefaultPathResolver.class);

    /** Defensive cap on the parent walk; a healthy tree is well under this. */
    private static final int MAX_TREE_DEPTH = 10_000;

    private static final String SEPARATOR = "/";

    private final TreeCache cache;

    public DefaultPathResolver(TreeCache cache) {
        this.cache = cache;
    }

    @Override
    public String pathOf(long itemTreeId) {
        List<String> namesRootFirst = walkToRoot(itemTreeId);
        if (namesRootFirst.isEmpty()) return "";
        return String.join(SEPARATOR, namesRootFirst);
    }

    @Override
    public Map<Long, String> pathsOf(Collection<Long> ids) {
        throw new UnsupportedOperationException("pathsOf implemented in next task");
    }

    /**
     * Walks the parent chain from {@code itemTreeId} up to (but not including) the conceptual
     * root-parent (id 0). Returns the collected names in root-first order. Returns an empty list
     * if {@code itemTreeId} is not in the cache.
     */
    private List<String> walkToRoot(long itemTreeId) {
        Optional<CachedNode> start = cache.getById(itemTreeId);
        if (start.isEmpty()) return List.of();

        List<String> namesLeafFirst = new ArrayList<>();
        CachedNode cursor = start.get();
        int steps = 0;
        while (cursor != null) {
            namesLeafFirst.add(cursor.name());
            if (cursor.parentId() == TreeConstants.ROOT_PARENT_ID) {
                break;
            }
            if (++steps > MAX_TREE_DEPTH) {
                log.warn("PathResolver: walk cap reached at id={}, possible cycle", itemTreeId);
                break;
            }
            CachedNode parent = cache.getById(cursor.parentId()).orElse(null);
            if (parent == null) {
                log.warn("PathResolver: missing ancestor parentId={} for id={} (originating id={})",
                        cursor.parentId(), cursor.itemTreeId(), itemTreeId);
                break;
            }
            cursor = parent;
        }
        Collections.reverse(namesLeafFirst);
        return namesLeafFirst;
    }
}
```

- [ ] **Step 4: Run the `PathOf` tests and confirm they pass**

Run: `./gradlew test --tests "com.myxcomp.ice.xtree.service.DefaultPathResolverTest.PathOf.*"`
Expected: PASS — five tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/service/DefaultPathResolver.java \
        src/test/java/com/myxcomp/ice/xtree/service/DefaultPathResolverTest.java
git commit -m "feat(service): implement PathResolver.pathOf with parent-chain walk"
```

---

## Task 7: `DefaultPathResolver.pathOf` — drift defence (orphan ancestor, cycle)

**Why:** Paths are computed at response time on a live cache that may have been mutated mid-request. The drift contracts (partial path, no throw, WARN logged) need explicit tests so they don't regress.

**Files:**
- Modify: `src/test/java/com/myxcomp/ice/xtree/service/DefaultPathResolverTest.java`

- [ ] **Step 1: Add the orphan + cycle cases to the existing `PathOf` nested class**

Append these methods to the existing `@Nested class PathOf { ... }` block:

```java
        @Test
        void orphanParentMidChainReturnsPartialPath() {
            // OrphanA's parent (999) is not in the cache, but OrphanA itself is.
            // pathOf(OrphanA) walks one step, finds the missing ancestor, and returns OrphanA's
            // name on its own — no root prefix because the chain never reached root.
            cache.applyCreate(folder(50L, 999L, "OrphanA"));

            assertThat(resolver.pathOf(50L)).isEqualTo("OrphanA");
        }

        @Test
        void cycleInParentChainTerminatesWithPartialPath() {
            // Two-node cycle: A.parent=B, B.parent=A (set up via upsert).
            cache.applyCreate(folder(100L, 200L, "A"));
            cache.applyCreate(folder(200L, 100L, "B"));

            // pathOf must terminate (cap hit) without throwing. The exact returned value
            // depends on which node we start from but must be non-null.
            assertThat(resolver.pathOf(100L)).isNotNull();
            assertThat(resolver.pathOf(200L)).isNotNull();
        }
```

- [ ] **Step 2: Run the tests**

Run: `./gradlew test --tests "com.myxcomp.ice.xtree.service.DefaultPathResolverTest.PathOf.*"`
Expected: PASS — seven tests now.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/myxcomp/ice/xtree/service/DefaultPathResolverTest.java
git commit -m "test(service): cover PathResolver.pathOf drift defence (orphan, cycle)"
```

---

## Task 8: `DefaultPathResolver.pathsOf` — memoised batch walk

**Why:** Per design §9, `pathsOf` "memoises ancestors within the call" so building paths for N nodes that share a chain costs one walk, not N. We test by walking a batch with a deeply shared ancestor chain and verifying the total `cache.getById` invocations is bounded by (walk length + N), not (walk length × N).

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/service/DefaultPathResolver.java`
- Modify: `src/test/java/com/myxcomp/ice/xtree/service/DefaultPathResolverTest.java`

- [ ] **Step 1: Replace the `pathsOf` stub with a memoised implementation**

In `DefaultPathResolver.java`, replace this stub:

```java
    @Override
    public Map<Long, String> pathsOf(Collection<Long> ids) {
        throw new UnsupportedOperationException("pathsOf implemented in next task");
    }
```

with:

```java
    @Override
    public Map<Long, String> pathsOf(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        Map<Long, String> memo = new HashMap<>();
        Map<Long, String> result = new HashMap<>();
        for (Long id : ids) {
            if (id == null) continue;
            result.put(id, pathFor(id, memo));
        }
        return result;
    }

    /**
     * Computes {@code pathOf(id)} but consults / fills {@code memo} keyed by node id so any prefix
     * already walked for an earlier input is reused. {@code memo} stores the full root-anchored
     * path for every id whose path has been resolved during this call.
     */
    private String pathFor(long itemTreeId, Map<Long, String> memo) {
        String cached = memo.get(itemTreeId);
        if (cached != null) return cached;

        Optional<CachedNode> startOpt = cache.getById(itemTreeId);
        if (startOpt.isEmpty()) {
            memo.put(itemTreeId, "");
            return "";
        }

        // Collect the chain leaf-first until we either reach root, hit a memoised ancestor,
        // hit a missing ancestor, or trip the cap.
        List<CachedNode> chainLeafFirst = new ArrayList<>();
        String anchorPath = null; // path for the ancestor we stopped at (if memoised)
        CachedNode cursor = startOpt.get();
        int steps = 0;
        while (cursor != null) {
            chainLeafFirst.add(cursor);
            if (cursor.parentId() == TreeConstants.ROOT_PARENT_ID) {
                break;
            }
            String memoForParent = memo.get(cursor.parentId());
            if (memoForParent != null) {
                anchorPath = memoForParent;
                break;
            }
            if (++steps > MAX_TREE_DEPTH) {
                log.warn("PathResolver: walk cap reached at id={}, possible cycle", itemTreeId);
                break;
            }
            CachedNode parent = cache.getById(cursor.parentId()).orElse(null);
            if (parent == null) {
                log.warn("PathResolver: missing ancestor parentId={} for id={} (originating id={})",
                        cursor.parentId(), cursor.itemTreeId(), itemTreeId);
                break;
            }
            cursor = parent;
        }

        // Build the path for each node in the collected chain, from the anchor outward,
        // populating memo as we go so siblings later in the batch can reuse it.
        StringBuilder accum = new StringBuilder(anchorPath == null ? "" : anchorPath);
        for (int i = chainLeafFirst.size() - 1; i >= 0; i--) {
            CachedNode node = chainLeafFirst.get(i);
            if (accum.length() > 0) accum.append(SEPARATOR);
            accum.append(node.name());
            memo.put(node.itemTreeId(), accum.toString());
        }

        return memo.getOrDefault(itemTreeId, "");
    }
```

- [ ] **Step 2: Add a `PathsOf` `@Nested` class to the test file**

Append this `@Nested` class inside `DefaultPathResolverTest`:

```java
    @Nested
    class PathsOf {

        @Test
        void emptyInputReturnsEmptyMap() {
            assertThat(resolver.pathsOf(List.of())).isEmpty();
        }

        @Test
        void nullInputReturnsEmptyMap() {
            assertThat(resolver.pathsOf(null)).isEmpty();
        }

        @Test
        void singleIdReturnsSingletonMap() {
            loadFixture(cache);
            Map<Long, String> result = resolver.pathsOf(List.of(10L));
            assertThat(result).containsExactly(Map.entry(10L, "root/Users/testuser1"));
        }

        @Test
        void batchReturnsCorrectPathPerId() {
            loadFixture(cache);

            Map<Long, String> result = resolver.pathsOf(List.of(1L, 2L, 10L, 110L, 210L));

            assertThat(result).containsOnly(
                    Map.entry(1L,   "root"),
                    Map.entry(2L,   "root/Users"),
                    Map.entry(10L,  "root/Users/testuser1"),
                    Map.entry(110L, "root/Users/testuser1/MyReport"),
                    Map.entry(210L, "root/Users/deepuser/L2/L3/L4/DeepReport"));
        }

        @Test
        void duplicateIdsCollapseToSingleEntry() {
            loadFixture(cache);
            Map<Long, String> result = resolver.pathsOf(List.of(10L, 10L, 10L));
            assertThat(result).containsExactly(Map.entry(10L, "root/Users/testuser1"));
        }

        @Test
        void unknownIdMapsToEmptyString() {
            loadFixture(cache);
            Map<Long, String> result = resolver.pathsOf(List.of(10L, 999L));
            assertThat(result).containsOnly(
                    Map.entry(10L,  "root/Users/testuser1"),
                    Map.entry(999L, ""));
        }

        @Test
        void memoisationLimitsGetByIdCallsForSharedAncestorChain() {
            // Build: root → A → B → C → D, with 50 leaves L1..L50 all parented by D.
            // Without memoisation, resolving 50 leaves would call getById ~5 times each
            // (D, C, B, A, root) for ancestors = 250 ancestor lookups.
            // With memoisation, the chain D→C→B→A→root is walked once for the first leaf
            // and reused thereafter.
            DefaultTreeCache realCache = new DefaultTreeCache();
            realCache.applyCreate(folder(1L, 0L, "root"));
            realCache.applyCreate(folder(2L, 1L, "A"));
            realCache.applyCreate(folder(3L, 2L, "B"));
            realCache.applyCreate(folder(4L, 3L, "C"));
            realCache.applyCreate(folder(5L, 4L, "D"));
            List<Long> leafIds = new java.util.ArrayList<>();
            for (long i = 100; i < 150; i++) {
                realCache.applyCreate(leaf(i, 5L, "leaf" + i));
                leafIds.add(i);
            }

            CountingTreeCache counting = new CountingTreeCache(realCache);
            DefaultPathResolver memoResolver = new DefaultPathResolver(counting);

            Map<Long, String> paths = memoResolver.pathsOf(leafIds);

            assertThat(paths).hasSize(50);
            assertThat(paths.get(100L)).isEqualTo("root/A/B/C/D/leaf100");
            assertThat(paths.get(149L)).isEqualTo("root/A/B/C/D/leaf149");

            // Upper bound: 50 leaf lookups + 4 ancestor lookups (A, B, C, D) on the first walk.
            // Root is reached when cursor.parentId() == 0, which short-circuits before another
            // getById, so root itself is NOT looked up via getById during the walk.
            // Allow a small slack for any incidental retrieval.
            assertThat(counting.getByIdCount())
                    .as("getById call count must be ~O(N + chain), not O(N * chain)")
                    .isLessThanOrEqualTo(60);
        }
    }

    /**
     * Test-only TreeCache decorator that counts getById invocations. Delegates everything else.
     * Implementing all of TreeCache verbosely is acceptable here — it's a one-off test asset.
     */
    static class CountingTreeCache implements com.myxcomp.ice.xtree.cache.TreeCache {
        private final com.myxcomp.ice.xtree.cache.TreeCache delegate;
        private int getByIdCount = 0;

        CountingTreeCache(com.myxcomp.ice.xtree.cache.TreeCache delegate) {
            this.delegate = delegate;
        }

        int getByIdCount() { return getByIdCount; }

        @Override public java.util.Optional<CachedNode> getById(long id) {
            getByIdCount++;
            return delegate.getById(id);
        }
        @Override public java.util.List<CachedNode> getChildren(long parentId) { return delegate.getChildren(parentId); }
        @Override public java.util.List<CachedNode> getSubtreeFlat(long rootId) { return delegate.getSubtreeFlat(rootId); }
        @Override public java.util.List<CachedNode> getTreeView(long homeFolderId) { return delegate.getTreeView(homeFolderId); }
        @Override public java.util.Optional<CachedNode> findHomeFolder(String userName) { return delegate.findHomeFolder(userName); }
        @Override public java.util.Optional<CachedNode> searchById(long id) { return delegate.searchById(id); }
        @Override public java.util.List<CachedNode> searchByName(String needle, java.util.OptionalInt limit) { return delegate.searchByName(needle, limit); }
        @Override public boolean isAncestor(long candidateAncestorId, long nodeId) { return delegate.isAncestor(candidateAncestorId, nodeId); }
        @Override public boolean exists(long id) { return delegate.exists(id); }
        @Override public boolean isFolder(long id) { return delegate.isFolder(id); }
        @Override public int size() { return delegate.size(); }
        @Override public void applyCreate(CachedNode node) { delegate.applyCreate(node); }
        @Override public void applyMetadataUpdate(long id, java.time.Instant lastUpdate, String lastUpdateUser) { delegate.applyMetadataUpdate(id, lastUpdate, lastUpdateUser); }
        @Override public void applyMove(long id, long newParentId, java.time.Instant lastUpdate, String lastUpdateUser) { delegate.applyMove(id, newParentId, lastUpdate, lastUpdateUser); }
        @Override public void applyRename(long id, String newName, java.time.Instant lastUpdate, String lastUpdateUser) { delegate.applyRename(id, newName, lastUpdate, lastUpdateUser); }
        @Override public void applyDelete(java.util.Set<Long> ids) { delegate.applyDelete(ids); }
        @Override public void replaceAll(com.myxcomp.ice.xtree.cache.TreeSnapshot newSnapshot) { delegate.replaceAll(newSnapshot); }
    }
```

- [ ] **Step 3: Run the `PathsOf` tests**

Run: `./gradlew test --tests "com.myxcomp.ice.xtree.service.DefaultPathResolverTest.PathsOf.*"`
Expected: PASS — seven tests.

- [ ] **Step 4: Run the full service + cache suites**

Run: `./gradlew test --tests "com.myxcomp.ice.xtree.service.*" --tests "com.myxcomp.ice.xtree.cache.*"`
Expected: PASS — all green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/service/DefaultPathResolver.java \
        src/test/java/com/myxcomp/ice/xtree/service/DefaultPathResolverTest.java
git commit -m "feat(service): implement PathResolver.pathsOf with shared-ancestor memoisation"
```

---

## Task 9: Run the full build and update `IMPLEMENTATION_NOTES.md`

**Files:**
- Modify: `IMPLEMENTATION_NOTES.md`

- [ ] **Step 1: Run the full build**

Run: `./gradlew clean build`
Expected: `BUILD SUCCESSFUL`. Capture the new test count from the report (Phase 5 ended at 192/202 tests; Phase 6 adds ~25).

- [ ] **Step 2: Update Phase 6 status in `IMPLEMENTATION_NOTES.md`**

In `IMPLEMENTATION_NOTES.md`:

Replace the line:

```
## Phase 6 — `getTreeView` algorithm + path resolution ⬅ NEXT
```

with:

```
## Phase 6 — `getTreeView` algorithm + path resolution ✅ COMPLETE (2026-05-16)
```

Move the `⬅ NEXT` marker onto the Phase 7 heading. Replace:

```
## Phase 7 — Services
```

with:

```
## Phase 7 — Services ⬅ NEXT
```

Add a `**Deviations from plan (reviewed and approved):**` block under the Phase 6 heading describing any deviations encountered while executing tasks 1–8. If none, write `**Deviations from plan:** none.`.

Add a final line under the Phase 6 section:

```
**Actual done state:** <N> tests green; `./gradlew clean build` → BUILD SUCCESSFUL.
```

Replace `<N>` with the test count reported by Step 1.

- [ ] **Step 3: Commit**

```bash
git add IMPLEMENTATION_NOTES.md
git commit -m "docs: mark Phase 6 complete; advance NEXT marker to Phase 7"
```

---

## Self-Review

Spec coverage (per `IMPLEMENTATION_NOTES.md` Phase 6 block):
- "DefaultTreeCache.getTreeView(long) per §8" — Tasks 1–4.
- "PathResolver per §9 with in-call memoisation" — Tasks 5–8.
- "Both functions execute under the read lock" — `getTreeView` acquires the cache's read lock once (Task 1); `PathResolver` uses `cache.getById` per call (each acquires the lock briefly), the same pattern the rest of the codebase already uses for cross-method-call walks. Documented in the interface Javadoc.
- "Home folder at depth 0, 1, 2, deep (e.g. 7)" — Tasks 1, 2.
- "Home folder empty" — Task 3.
- "Home folder with mixed-type children" — Task 3.
- "Home folder missing (throws)" — Task 4.
- "Missing ancestor mid-walk (warning + partial result)" — Task 4 (and Task 7 for PathResolver).
- "Cycle defence trigger" — Task 4 (and Task 7 for PathResolver).
- "Path resolution memoisation: ancestors walked once per pathsOf call regardless of input size" — Task 8 (`memoisationLimitsGetByIdCallsForSharedAncestorChain`).

Placeholder scan: no `TBD`, `TODO`, "implement later", or "similar to Task N" references; every step shows the actual code.

Type consistency:
- `MAX_TREE_DEPTH = 10_000` is the same constant name in both `DefaultTreeCache` and `DefaultPathResolver` (separate class-private declarations; no cross-package leak).
- `PathResolver.pathOf(long)` and `pathsOf(Collection<Long>)` signatures match the design §9 declaration verbatim.
- `IllegalArgumentException` (not a custom exception) is used by `getTreeView` for missing home folder, matching the existing TreeCache Javadoc.

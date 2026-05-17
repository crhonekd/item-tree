# Phase 7 — Services Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the four service-layer classes (`ItemService`, `TreeService`, `SearchService`, `HomeFolderService`) plus the small collaborators they need (event-publisher contract, sequence generator, async backfill executor, service-layer exception model) so that business logic is fully exercised against mocked persistence / cache / messaging — independent of the HTTP layer (Phase 8) and the JMS implementation (Phase 10).

**Architecture:**
- Each public service is a thin orchestrator around three already-built layers — `TreeCache` (Phase 4/6), `ItemTreeRepository` (Phase 3), and `TypePolicy` / `XmlJsonConverter` (Phase 5).
- `ItemService` enforces the §5 write order — **validate (cache) → persist (DB) → apply (cache) → publish (event)** — and never returns control before `apply*` has run. Validation failures throw typed exceptions (`ValidationException` 400, `NotFoundException` 404), each carrying an `ErrorCode` enum value the Phase 8 advisor will map to RFC 7807.
- `EventPublisher` is introduced here as a one-method interface in `messaging/`. `ItemService` constructs the full `TreeMutationEvent` envelope (eventId, instanceId, sequence, occurredAt, both users) and hands it to the publisher; Phase 10 supplies the production JMS-backed implementation, and Phase 7 tests inject a Mockito mock.
- Async backfill: a single dedicated `ThreadPoolTaskExecutor` (core/max=1, bounded queue) is wired in a new `AsyncConfig` and consumed only by `ItemService.getItemsWithData`.

**Tech Stack:** Java 21, Spring (`@Service` / `@Component` / `@Configuration`), Lombok permitted, JUnit 5, Mockito (`@ExtendWith(MockitoExtension.class)`), AssertJ. No Spring slice tests in this phase — services are POJOs and we mock all collaborators.

---

## File Structure

### New production files

| Path | Responsibility |
|---|---|
| `src/main/java/com/myxcomp/ice/xtree/service/exception/ErrorCode.java` | Enum of every service-layer error code the design lists (`PARENT_NOT_FOUND`, `PARENT_NOT_FOLDER`, `MOVE_INTO_DESCENDANT`, `NEW_PARENT_NOT_FOUND`, `NEW_PARENT_NOT_FOLDER`, `TYPE_CANNOT_HAVE_DATA`, `DATA_REQUIRED`, `FOLDER_CANNOT_HAVE_DATA`, `ITEM_NOT_FOUND`, `HOME_FOLDER_NOT_FOUND`). |
| `src/main/java/com/myxcomp/ice/xtree/service/exception/ItemTreeException.java` | Abstract base `RuntimeException` carrying `ErrorCode errorCode`. |
| `src/main/java/com/myxcomp/ice/xtree/service/exception/ValidationException.java` | 400-class concrete subclass. |
| `src/main/java/com/myxcomp/ice/xtree/service/exception/NotFoundException.java` | 404-class concrete subclass. |
| `src/main/java/com/myxcomp/ice/xtree/messaging/EventPublisher.java` | Interface — `void publish(TreeMutationEvent event)`. |
| `src/main/java/com/myxcomp/ice/xtree/messaging/SequenceGenerator.java` | `@Component` wrapping `AtomicLong`; `long next()`. |
| `src/main/java/com/myxcomp/ice/xtree/config/AsyncConfig.java` | `@Configuration` declaring a single `TaskExecutor` bean named `backfillExecutor` (core/max=1, queue=100, named threads). |
| `src/main/java/com/myxcomp/ice/xtree/service/HomeFolderService.java` | `@Service` — `CachedNode findHomeFolder(String userName)`; throws `NotFoundException(HOME_FOLDER_NOT_FOUND)` if absent. |
| `src/main/java/com/myxcomp/ice/xtree/service/SearchService.java` | `@Service` — `Optional<CachedNode> searchById(long)`; `List<CachedNode> searchByName(String, OptionalInt)`. |
| `src/main/java/com/myxcomp/ice/xtree/service/TreeNodeView.java` | Record `(CachedNode node, String path)` — service-layer pair for `/tree` and `/tree/{rootId}/subtree`. |
| `src/main/java/com/myxcomp/ice/xtree/service/TreeService.java` | `@Service` — `List<TreeNodeView> getTreeView(UserContext)`; `List<TreeNodeView> getSubtree(long rootId)`. |
| `src/main/java/com/myxcomp/ice/xtree/service/ItemWithData.java` | Record carrying the data-shaped node used by `/items/get`: `(itemTreeId, parentId, name, type, lastUpdate, lastUpdateUser, String dataJson, String dataXml, List<ItemWithData> children)`. |
| `src/main/java/com/myxcomp/ice/xtree/service/ItemService.java` | `@Service` — all write operations plus `getItemsWithData(List<Long>)`. |

### Modified production files

| Path | Change |
|---|---|
| `src/main/java/com/myxcomp/ice/xtree/common/TimeMapper.java` | Add `Instant now()` returning `Instant.now()`. Single application-wide source of "now"; tests mock it. |
| `IMPLEMENTATION_NOTES.md` | Mark Phase 7 ✅ COMPLETE; move the ⬅ NEXT marker to Phase 8; record any deviations. |

### New test files

| Path | Coverage |
|---|---|
| `src/test/java/com/myxcomp/ice/xtree/service/exception/ErrorCodeTest.java` | Pins the enum names; guards against rename drift. |
| `src/test/java/com/myxcomp/ice/xtree/messaging/SequenceGeneratorTest.java` | Strict monotonic increment from 1; thread-safe under 1k concurrent calls. |
| `src/test/java/com/myxcomp/ice/xtree/config/AsyncConfigTest.java` | Bean wiring: a `TaskExecutor` named `backfillExecutor` exists; rejection handler is `AbortPolicy`; core/max/queue are 1/1/100. |
| `src/test/java/com/myxcomp/ice/xtree/service/HomeFolderServiceTest.java` | Happy path, NotFoundException path. |
| `src/test/java/com/myxcomp/ice/xtree/service/SearchServiceTest.java` | searchById empty / present; searchByName limit propagation. |
| `src/test/java/com/myxcomp/ice/xtree/service/TreeServiceTest.java` | `getTreeView` aggregates cache + path-resolver output; `getSubtree` covers full subtree. |
| `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceCreateTest.java` | All create validation paths + DB/cache/event ordering. |
| `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceDeleteTest.java` | Cascade delete returns ids → cache `applyDelete` + DELETE event; missing id is a no-op. |
| `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceMoveTest.java` | All move validation paths (`ITEM_NOT_FOUND`, `NEW_PARENT_NOT_FOUND`, `NEW_PARENT_NOT_FOLDER`, `MOVE_INTO_DESCENDANT`, self-as-parent rejection). |
| `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceRenameTest.java` | Happy path + `ITEM_NOT_FOUND`. |
| `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceUpdateDataTest.java` | Happy path (JSON-only and JSON+XML), `ITEM_NOT_FOUND`, `FOLDER_CANNOT_HAVE_DATA`, `TYPE_CANNOT_HAVE_DATA`. |
| `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceGetItemsTest.java` | Folder children expansion, payload shaping per design §11 read matrix (JSON present / XML fallback w/ async backfill / both null / `types-without-data` → empty payload), missing ids silently omitted. |

**Why ItemService tests split per operation:** the create/move/getItems tests each grow >150 LOC because of validation matrices. Splitting beats one ~800-line `ItemServiceTest` with deeply nested classes.

---

## Conventions used by the rest of this plan

- **Logger:** `private static final Logger log = LoggerFactory.getLogger(<owning class>.class);` — SLF4J only, no Lombok `@Slf4j` (matches existing style in `DefaultPathResolver`, `DefaultTreeCache`).
- **Imports:** static `Mockito.*`, static `AssertJ.assertThat`, static `org.assertj.core.api.Assertions.assertThatThrownBy`.
- **`UserContext` always passed explicitly** to service methods — never read from a thread-local, scoped bean, or `RequestContextHolder`. Controllers in Phase 8 construct and pass it.
- **DB → cache → event ordering** is the §5 invariant. In every write operation, the `InOrder` Mockito verifier is used in tests to assert `repo` → `cache` → `publisher` ordering.
- **`Instant.now()` is forbidden** outside `TimeMapper.now()`. Tests stub `timeMapper.now()` to a fixed `Instant`.
- **`UUID.randomUUID().toString()`** is used inline for `eventId`. Tests assert the field is non-null / non-blank rather than pinning the exact value.
- **Constants:** repeat `TreeConstants.ROOT_PARENT_ID`, `Types.FOLDER`, `Types.isFolder(...)` from existing packages — do not duplicate.
- **No `@Disabled`, no `@Ignore`**: if a test fails, fix the code or the test, then commit.

---

## Task 1: Service-layer exception model

**Why first:** Every service method either returns success or throws one of these — they need to exist before any service compiles.

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/service/exception/ErrorCode.java`
- Create: `src/main/java/com/myxcomp/ice/xtree/service/exception/ItemTreeException.java`
- Create: `src/main/java/com/myxcomp/ice/xtree/service/exception/ValidationException.java`
- Create: `src/main/java/com/myxcomp/ice/xtree/service/exception/NotFoundException.java`
- Create: `src/test/java/com/myxcomp/ice/xtree/service/exception/ErrorCodeTest.java`

- [ ] **Step 1: Write the failing pinning test for the enum surface**

Create `src/test/java/com/myxcomp/ice/xtree/service/exception/ErrorCodeTest.java`:

```java
package com.myxcomp.ice.xtree.service.exception;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorCodeTest {

    private static final List<String> EXPECTED_NAMES = List.of(
            "PARENT_NOT_FOUND",
            "PARENT_NOT_FOLDER",
            "MOVE_INTO_DESCENDANT",
            "NEW_PARENT_NOT_FOUND",
            "NEW_PARENT_NOT_FOLDER",
            "TYPE_CANNOT_HAVE_DATA",
            "DATA_REQUIRED",
            "FOLDER_CANNOT_HAVE_DATA",
            "ITEM_NOT_FOUND",
            "HOME_FOLDER_NOT_FOUND"
    );

    @Test
    void enumExposesExactlyTheCodesTheDesignRequires() {
        List<String> actual = Arrays.stream(ErrorCode.values()).map(Enum::name).toList();
        assertThat(actual).containsExactlyInAnyOrderElementsOf(EXPECTED_NAMES);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "com.myxcomp.ice.xtree.service.exception.ErrorCodeTest"`
Expected: compilation failure — `ErrorCode` does not exist.

- [ ] **Step 3: Create the enum**

Create `src/main/java/com/myxcomp/ice/xtree/service/exception/ErrorCode.java`:

```java
package com.myxcomp.ice.xtree.service.exception;

/**
 * Service-layer error codes surfaced to clients via RFC 7807 (Phase 8 maps them).
 * Each value corresponds verbatim to a code listed in the design (§3, §10, §13).
 */
public enum ErrorCode {
    PARENT_NOT_FOUND,
    PARENT_NOT_FOLDER,
    MOVE_INTO_DESCENDANT,
    NEW_PARENT_NOT_FOUND,
    NEW_PARENT_NOT_FOLDER,
    TYPE_CANNOT_HAVE_DATA,
    DATA_REQUIRED,
    FOLDER_CANNOT_HAVE_DATA,
    ITEM_NOT_FOUND,
    HOME_FOLDER_NOT_FOUND
}
```

- [ ] **Step 4: Create the base exception**

Create `src/main/java/com/myxcomp/ice/xtree/service/exception/ItemTreeException.java`:

```java
package com.myxcomp.ice.xtree.service.exception;

import java.util.Objects;

/**
 * Base for all service-layer business exceptions. Phase 8's {@code GlobalExceptionHandler}
 * branches on the concrete subclass to set HTTP status and on {@link ErrorCode} for the
 * RFC 7807 {@code errorCode} extension.
 */
public abstract class ItemTreeException extends RuntimeException {

    private final ErrorCode errorCode;

    protected ItemTreeException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
```

- [ ] **Step 5: Create the two concrete subclasses**

Create `src/main/java/com/myxcomp/ice/xtree/service/exception/ValidationException.java`:

```java
package com.myxcomp.ice.xtree.service.exception;

/** Maps to HTTP 400 in Phase 8. */
public class ValidationException extends ItemTreeException {
    public ValidationException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
```

Create `src/main/java/com/myxcomp/ice/xtree/service/exception/NotFoundException.java`:

```java
package com.myxcomp.ice.xtree.service.exception;

/** Maps to HTTP 404 in Phase 8. */
public class NotFoundException extends ItemTreeException {
    public NotFoundException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew test --tests "com.myxcomp.ice.xtree.service.exception.ErrorCodeTest"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/service/exception/ \
        src/test/java/com/myxcomp/ice/xtree/service/exception/
git commit -m "feat(service): introduce ErrorCode + typed business exceptions"
```

---

## Task 2: `EventPublisher` interface and `SequenceGenerator`

**Why now:** `ItemService` (Task 7+) constructs the `TreeMutationEvent` envelope and calls `EventPublisher.publish`. Both the interface and the per-JVM sequence source are listed under Phase 10 in `IMPLEMENTATION_NOTES.md`; **deviation from plan:** they materialise in Phase 7 because the service layer is now the first caller. Phase 10 still owns the production implementation.

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/messaging/EventPublisher.java`
- Create: `src/main/java/com/myxcomp/ice/xtree/messaging/SequenceGenerator.java`
- Create: `src/test/java/com/myxcomp/ice/xtree/messaging/SequenceGeneratorTest.java`

- [ ] **Step 1: Write the failing test for `SequenceGenerator`**

Create `src/test/java/com/myxcomp/ice/xtree/messaging/SequenceGeneratorTest.java`:

```java
package com.myxcomp.ice.xtree.messaging;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class SequenceGeneratorTest {

    @Test
    void firstCallReturnsOne() {
        SequenceGenerator gen = new SequenceGenerator();
        assertThat(gen.next()).isEqualTo(1L);
    }

    @Test
    void sequentialCallsIncrementMonotonically() {
        SequenceGenerator gen = new SequenceGenerator();
        assertThat(gen.next()).isEqualTo(1L);
        assertThat(gen.next()).isEqualTo(2L);
        assertThat(gen.next()).isEqualTo(3L);
    }

    @Test
    void concurrentCallsProduceUniqueContiguousValues() throws InterruptedException {
        SequenceGenerator gen = new SequenceGenerator();
        int threads = 16;
        int perThread = 1_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<Long> values = new HashSet<>();
        Object guard = new Object();

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    long v = gen.next();
                    synchronized (guard) { values.add(v); }
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        int total = threads * perThread;
        assertThat(values).hasSize(total);
        assertThat(values).contains(1L);
        assertThat(values).contains((long) total);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "com.myxcomp.ice.xtree.messaging.SequenceGeneratorTest"`
Expected: compilation failure — `SequenceGenerator` does not exist.

- [ ] **Step 3: Create `SequenceGenerator`**

Create `src/main/java/com/myxcomp/ice/xtree/messaging/SequenceGenerator.java`:

```java
package com.myxcomp.ice.xtree.messaging;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-JVM monotonic sequence number stamped on every outgoing {@code TreeMutationEvent}.
 * First call returns {@code 1L}. Resets across JVM restarts — recovery happens via periodic
 * refresh (design §6).
 */
@Component
public class SequenceGenerator {

    private final AtomicLong counter = new AtomicLong(0L);

    public long next() {
        return counter.incrementAndGet();
    }
}
```

- [ ] **Step 4: Create `EventPublisher` interface**

Create `src/main/java/com/myxcomp/ice/xtree/messaging/EventPublisher.java`:

```java
package com.myxcomp.ice.xtree.messaging;

import com.myxcomp.ice.xtree.messaging.event.TreeMutationEvent;

/**
 * Outbound side of the Solace broadcast contract (design §6).
 *
 * <p>Phase 7 creates the interface so {@code ItemService} has something to call;
 * Phase 10 supplies the production implementation backed by {@code JMSPublisherService}.
 *
 * <p>Implementations are best-effort: failure is logged and counted but never propagates
 * to the caller. The DB commit and the local cache update have already happened — peer
 * instances reconcile via the next refresh if the broadcast was lost.
 */
public interface EventPublisher {

    /** Best-effort broadcast of {@code event}. Implementations MUST NOT throw. */
    void publish(TreeMutationEvent event);
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew test --tests "com.myxcomp.ice.xtree.messaging.SequenceGeneratorTest"`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/messaging/EventPublisher.java \
        src/main/java/com/myxcomp/ice/xtree/messaging/SequenceGenerator.java \
        src/test/java/com/myxcomp/ice/xtree/messaging/SequenceGeneratorTest.java
git commit -m "feat(messaging): introduce EventPublisher interface and SequenceGenerator"
```

---

## Task 3: Add `TimeMapper.now()`

**Why:** Every write operation stamps `lastUpdate = TimeMapper.now()`. Centralising the wall-clock call here keeps the CLAUDE.md invariant ("TimeMapper is the only thing that touches timezone conversion") and makes time mockable in tests.

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/common/TimeMapper.java`
- Modify: `src/test/java/com/myxcomp/ice/xtree/common/TimeMapperTest.java` (add one test)

- [ ] **Step 1: Add a failing test for `TimeMapper.now()`**

In `src/test/java/com/myxcomp/ice/xtree/common/TimeMapperTest.java`, add the following test method to the existing class (do not duplicate imports if already present):

```java
    @Test
    void nowReturnsCurrentInstantAndIsMonotonicNonDecreasing() throws InterruptedException {
        TimeMapper mapper = new TimeMapper();
        Instant before = Instant.now();
        Thread.sleep(2);
        Instant got = mapper.now();
        Thread.sleep(2);
        Instant after = Instant.now();

        assertThat(got).isNotNull();
        assertThat(got).isAfterOrEqualTo(before);
        assertThat(got).isBeforeOrEqualTo(after);
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "com.myxcomp.ice.xtree.common.TimeMapperTest"`
Expected: compilation failure — `TimeMapper.now()` does not exist.

- [ ] **Step 3: Add `now()` to `TimeMapper`**

In `src/main/java/com/myxcomp/ice/xtree/common/TimeMapper.java`, add the method below the existing `toInstant` method:

```java
    /**
     * Returns the current UTC instant. Sole entry-point for "now" across the application —
     * services and bootstrap collaborators MUST NOT call {@code Instant.now()} directly so
     * that time can be controlled in tests via Mockito.
     */
    public Instant now() {
        return Instant.now();
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "com.myxcomp.ice.xtree.common.TimeMapperTest"`
Expected: PASS (all existing tests + the new one).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/common/TimeMapper.java \
        src/test/java/com/myxcomp/ice/xtree/common/TimeMapperTest.java
git commit -m "feat(common): add TimeMapper.now() as application-wide clock entry point"
```

---

## Task 4: `HomeFolderService`

**Why before other services:** It's the smallest, and `TreeService.getTree` depends on it.

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/service/HomeFolderService.java`
- Create: `src/test/java/com/myxcomp/ice/xtree/service/HomeFolderServiceTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/myxcomp/ice/xtree/service/HomeFolderServiceTest.java`:

```java
package com.myxcomp.ice.xtree.service;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.TreeCache;
import com.myxcomp.ice.xtree.service.exception.ErrorCode;
import com.myxcomp.ice.xtree.service.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HomeFolderServiceTest {

    @Mock TreeCache cache;
    HomeFolderService service;

    @BeforeEach
    void setUp() {
        service = new HomeFolderService(cache);
    }

    @Test
    void returnsTheFolderWhenPresent() {
        CachedNode node = new CachedNode(42L, 2L, "alice", "Folder", Instant.EPOCH, "sys");
        when(cache.findHomeFolder("alice")).thenReturn(Optional.of(node));

        assertThat(service.findHomeFolder("alice")).isSameAs(node);
    }

    @Test
    void throwsNotFoundExceptionWhenAbsent() {
        when(cache.findHomeFolder("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findHomeFolder("ghost"))
                .isInstanceOf(NotFoundException.class)
                .satisfies(t -> assertThat(((NotFoundException) t).errorCode())
                        .isEqualTo(ErrorCode.HOME_FOLDER_NOT_FOUND))
                .hasMessageContaining("ghost");
    }

    @Test
    void rejectsNullUserName() {
        assertThatThrownBy(() -> service.findHomeFolder(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("userName");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "com.myxcomp.ice.xtree.service.HomeFolderServiceTest"`
Expected: compilation failure — `HomeFolderService` does not exist.

- [ ] **Step 3: Implement `HomeFolderService`**

Create `src/main/java/com/myxcomp/ice/xtree/service/HomeFolderService.java`:

```java
package com.myxcomp.ice.xtree.service;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.TreeCache;
import com.myxcomp.ice.xtree.service.exception.ErrorCode;
import com.myxcomp.ice.xtree.service.exception.NotFoundException;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public class HomeFolderService {

    private final TreeCache cache;

    public HomeFolderService(TreeCache cache) {
        this.cache = cache;
    }

    /**
     * Returns the home folder for {@code userName}.
     * @throws NotFoundException ({@link ErrorCode#HOME_FOLDER_NOT_FOUND}) if no folder matches.
     */
    public CachedNode findHomeFolder(String userName) {
        Objects.requireNonNull(userName, "userName");
        return cache.findHomeFolder(userName).orElseThrow(() -> new NotFoundException(
                ErrorCode.HOME_FOLDER_NOT_FOUND,
                "No home folder for user '" + userName + "'"));
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "com.myxcomp.ice.xtree.service.HomeFolderServiceTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/service/HomeFolderService.java \
        src/test/java/com/myxcomp/ice/xtree/service/HomeFolderServiceTest.java
git commit -m "feat(service): add HomeFolderService"
```

---

## Task 5: `SearchService`

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/service/SearchService.java`
- Create: `src/test/java/com/myxcomp/ice/xtree/service/SearchServiceTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/myxcomp/ice/xtree/service/SearchServiceTest.java`:

```java
package com.myxcomp.ice.xtree.service;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.TreeCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchServiceTest {

    @Mock TreeCache cache;
    SearchService service;

    @BeforeEach
    void setUp() {
        service = new SearchService(cache);
    }

    @Test
    void searchByIdReturnsCacheValue() {
        CachedNode node = new CachedNode(7L, 1L, "x", "Report", Instant.EPOCH, "sys");
        when(cache.searchById(7L)).thenReturn(Optional.of(node));

        assertThat(service.searchById(7L)).contains(node);
    }

    @Test
    void searchByIdReturnsEmptyWhenMissing() {
        when(cache.searchById(99L)).thenReturn(Optional.empty());
        assertThat(service.searchById(99L)).isEmpty();
    }

    @Test
    void searchByNamePropagatesLimit() {
        when(cache.searchByName("rep", OptionalInt.of(5))).thenReturn(List.of());
        service.searchByName("rep", OptionalInt.of(5));

        ArgumentCaptor<OptionalInt> limitCap = ArgumentCaptor.forClass(OptionalInt.class);
        verify(cache).searchByName(eq("rep"), limitCap.capture());
        assertThat(limitCap.getValue()).hasValue(5);
    }

    @Test
    void searchByNameDefaultsToEmptyLimit() {
        when(cache.searchByName("rep", OptionalInt.empty())).thenReturn(List.of());
        service.searchByName("rep", OptionalInt.empty());

        verify(cache).searchByName("rep", OptionalInt.empty());
    }

    @Test
    void searchByNameReturnsCacheHits() {
        CachedNode hit = new CachedNode(8L, 1L, "MyReport", "Report", Instant.EPOCH, "sys");
        when(cache.searchByName("report", OptionalInt.empty())).thenReturn(List.of(hit));

        assertThat(service.searchByName("report", OptionalInt.empty())).containsExactly(hit);
    }

    private static <T> T eq(T v) { return org.mockito.ArgumentMatchers.eq(v); }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "com.myxcomp.ice.xtree.service.SearchServiceTest"`
Expected: compilation failure — `SearchService` does not exist.

- [ ] **Step 3: Implement `SearchService`**

Create `src/main/java/com/myxcomp/ice/xtree/service/SearchService.java`:

```java
package com.myxcomp.ice.xtree.service;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.TreeCache;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

@Service
public class SearchService {

    private final TreeCache cache;

    public SearchService(TreeCache cache) {
        this.cache = cache;
    }

    public Optional<CachedNode> searchById(long id) {
        return cache.searchById(id);
    }

    public List<CachedNode> searchByName(String needle, OptionalInt limit) {
        Objects.requireNonNull(needle, "needle");
        Objects.requireNonNull(limit, "limit");
        return cache.searchByName(needle, limit);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "com.myxcomp.ice.xtree.service.SearchServiceTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/service/SearchService.java \
        src/test/java/com/myxcomp/ice/xtree/service/SearchServiceTest.java
git commit -m "feat(service): add SearchService"
```

---

## Task 6: `TreeService` and `TreeNodeView` record

**Why:** The HTTP layer (Phase 8) needs both `/tree` (per-user, trimmed view) and `/tree/{rootId}/subtree` (flat subtree). Both produce `(CachedNode, path)` pairs.

**Behavioural notes:**
- `getTree(UserContext)` uses the **effective user** (impersonated if set, else iceUser) to look up the home folder — per design §13.
- Path resolution happens via `PathResolver.pathsOf` (single batch call → memoised walks).
- `getSubtree(rootId)` returns the same pair list for the whole subtree; if `rootId` is unknown the cache returns an empty list and the service returns an empty list (no throw — matches `getSubtreeFlat` semantics).

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/service/TreeNodeView.java`
- Create: `src/main/java/com/myxcomp/ice/xtree/service/TreeService.java`
- Create: `src/test/java/com/myxcomp/ice/xtree/service/TreeServiceTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/myxcomp/ice/xtree/service/TreeServiceTest.java`:

```java
package com.myxcomp.ice.xtree.service;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.TreeCache;
import com.myxcomp.ice.xtree.common.UserContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TreeServiceTest {

    @Mock TreeCache cache;
    @Mock PathResolver pathResolver;
    @Mock HomeFolderService homeFolderService;
    TreeService service;

    static final Instant T = Instant.EPOCH;

    @BeforeEach
    void setUp() {
        service = new TreeService(cache, pathResolver, homeFolderService);
    }

    private static CachedNode folder(long id, long parentId, String name) {
        return new CachedNode(id, parentId, name, "Folder", T, "sys");
    }

    @Test
    void getTreeUsesImpersonatedUserForHomeFolderLookup() {
        CachedNode home = folder(10L, 2L, "alice");
        when(homeFolderService.findHomeFolder("alice")).thenReturn(home);
        when(cache.getTreeView(10L)).thenReturn(List.of(home));
        when(pathResolver.pathsOf(List.of(10L))).thenReturn(Map.of(10L, "root/Users/alice"));

        List<TreeNodeView> result = service.getTree(new UserContext("bob", "alice"));

        assertThat(result).containsExactly(new TreeNodeView(home, "root/Users/alice"));
    }

    @Test
    void getTreeFallsBackToIceUserWhenNoImpersonation() {
        CachedNode home = folder(11L, 2L, "bob");
        when(homeFolderService.findHomeFolder("bob")).thenReturn(home);
        when(cache.getTreeView(11L)).thenReturn(List.of(home));
        when(pathResolver.pathsOf(List.of(11L))).thenReturn(Map.of(11L, "root/Users/bob"));

        List<TreeNodeView> result = service.getTree(new UserContext("bob", null));

        assertThat(result).extracting(TreeNodeView::path).containsExactly("root/Users/bob");
    }

    @Test
    void getTreePreservesCacheOrderingWhilePairingPaths() {
        CachedNode root = folder(1L, 0L, "root");
        CachedNode users = folder(2L, 1L, "Users");
        CachedNode alice = folder(10L, 2L, "alice");
        when(homeFolderService.findHomeFolder("alice")).thenReturn(alice);
        when(cache.getTreeView(10L)).thenReturn(List.of(root, users, alice));
        when(pathResolver.pathsOf(List.of(1L, 2L, 10L))).thenReturn(Map.of(
                1L, "root",
                2L, "root/Users",
                10L, "root/Users/alice"
        ));

        List<TreeNodeView> result = service.getTree(new UserContext("alice", null));

        assertThat(result).containsExactly(
                new TreeNodeView(root,  "root"),
                new TreeNodeView(users, "root/Users"),
                new TreeNodeView(alice, "root/Users/alice")
        );
    }

    @Test
    void getSubtreeReturnsPairsForEveryNodeInSubtree() {
        CachedNode parent = folder(20L, 1L, "Group");
        CachedNode child  = folder(21L, 20L, "Sub");
        when(cache.getSubtreeFlat(20L)).thenReturn(List.of(parent, child));
        when(pathResolver.pathsOf(List.of(20L, 21L))).thenReturn(Map.of(
                20L, "root/Group",
                21L, "root/Group/Sub"
        ));

        List<TreeNodeView> result = service.getSubtree(20L);

        assertThat(result).containsExactly(
                new TreeNodeView(parent, "root/Group"),
                new TreeNodeView(child,  "root/Group/Sub")
        );
    }

    @Test
    void getSubtreeReturnsEmptyListForUnknownRoot() {
        when(cache.getSubtreeFlat(999L)).thenReturn(List.of());

        List<TreeNodeView> result = service.getSubtree(999L);

        assertThat(result).isEmpty();
    }

    @Test
    void getSubtreeFallsBackToEmptyStringPathWhenResolverOmitsId() {
        CachedNode node = folder(30L, 1L, "X");
        when(cache.getSubtreeFlat(30L)).thenReturn(List.of(node));
        when(pathResolver.pathsOf(anyCollection())).thenReturn(Map.of());  // resolver dropped the id

        List<TreeNodeView> result = service.getSubtree(30L);

        assertThat(result).containsExactly(new TreeNodeView(node, ""));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "com.myxcomp.ice.xtree.service.TreeServiceTest"`
Expected: compilation failure — `TreeService` and `TreeNodeView` do not exist.

- [ ] **Step 3: Create the record**

Create `src/main/java/com/myxcomp/ice/xtree/service/TreeNodeView.java`:

```java
package com.myxcomp.ice.xtree.service;

import com.myxcomp.ice.xtree.cache.CachedNode;

/**
 * Service-layer pairing of a node and its lazily-resolved path used by {@code /tree}
 * and {@code /tree/{rootId}/subtree}. Phase 8 mappers will project this to the
 * generated {@code ItemNode} DTO with the {@code path} field populated.
 */
public record TreeNodeView(CachedNode node, String path) {}
```

- [ ] **Step 4: Implement `TreeService`**

Create `src/main/java/com/myxcomp/ice/xtree/service/TreeService.java`:

```java
package com.myxcomp.ice.xtree.service;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.TreeCache;
import com.myxcomp.ice.xtree.common.UserContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class TreeService {

    private final TreeCache cache;
    private final PathResolver pathResolver;
    private final HomeFolderService homeFolderService;

    public TreeService(TreeCache cache,
                       PathResolver pathResolver,
                       HomeFolderService homeFolderService) {
        this.cache = cache;
        this.pathResolver = pathResolver;
        this.homeFolderService = homeFolderService;
    }

    /**
     * Returns the trimmed tree view (§8) for the caller, anchored on the caller's home folder.
     * Uses the impersonated user when present, else the authenticated user — design §13.
     */
    public List<TreeNodeView> getTree(UserContext userContext) {
        Objects.requireNonNull(userContext, "userContext");
        CachedNode home = homeFolderService.findHomeFolder(userContext.effectiveUser());
        List<CachedNode> nodes = cache.getTreeView(home.itemTreeId());
        return pairWithPaths(nodes);
    }

    /** Returns every node in the subtree rooted at {@code rootId}, each paired with its path. */
    public List<TreeNodeView> getSubtree(long rootId) {
        List<CachedNode> nodes = cache.getSubtreeFlat(rootId);
        return pairWithPaths(nodes);
    }

    private List<TreeNodeView> pairWithPaths(List<CachedNode> nodes) {
        if (nodes.isEmpty()) return List.of();
        List<Long> ids = nodes.stream().map(CachedNode::itemTreeId).toList();
        Map<Long, String> paths = pathResolver.pathsOf(ids);
        List<TreeNodeView> out = new ArrayList<>(nodes.size());
        for (CachedNode n : nodes) {
            out.add(new TreeNodeView(n, paths.getOrDefault(n.itemTreeId(), "")));
        }
        return List.copyOf(out);
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew test --tests "com.myxcomp.ice.xtree.service.TreeServiceTest"`
Expected: PASS (6 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/service/TreeNodeView.java \
        src/main/java/com/myxcomp/ice/xtree/service/TreeService.java \
        src/test/java/com/myxcomp/ice/xtree/service/TreeServiceTest.java
git commit -m "feat(service): add TreeService + TreeNodeView record"
```

---

## Task 7: `ItemService` — create operation

**Why this is the first ItemService task:** create exercises every collaborator (cache for parent validation, type policy for data check, XML converter for fan-out, repository for insert, cache for `applyCreate`, publisher for CREATE event). Subsequent tasks (delete/move/rename/update) layer onto this scaffolding.

**Validation order (per design §3, §10):**
1. `Objects.requireNonNull` on `request`, `userContext`.
2. **Parent existence & folder-ness:** `cache.getById(parentId)` → if absent throw `NotFoundException(PARENT_NOT_FOUND)`. If present but `!Types.isFolder(parent.type())` → `ValidationException(PARENT_NOT_FOLDER)`.
   - **Root exception:** `parentId == ROOT_PARENT_ID` (0) is permitted when the caller is creating the root replacement (not expected in normal traffic but defensive). For Phase 7, treat `parentId == 0` exactly like any other: it would have to exist in the cache, which the root never is — so creating with parentId=0 produces `PARENT_NOT_FOUND`. This is intentional; the UI never creates with parentId=0.
3. **Type policy:** `policy.hasData(type)` vs `request.data` populated:
   - `!hasData && data != null` → `ValidationException(TYPE_CANNOT_HAVE_DATA)`.
   - `hasData && data == null` → `ValidationException(DATA_REQUIRED)`.
4. **XML fan-out:** if `policy.isAlsoPersistedAsXmlOnWrite(type)`, compute `xml = converter.jsonToXml(dataJson)`.
5. `now = timeMapper.now()`, `user = userContext.effectiveUser()`.
6. `id = repository.insert(parentId, name, type, dataJson, xmlOrNull, now, user)`.
7. `node = new CachedNode(id, parentId, name, type, now, user)`; `cache.applyCreate(node)`.
8. Build `TreeMutationEvent` with `CreatePayload(id, parentId, name, type, now, user)`, `iceUser`/`impersonatedUser` from `userContext`, sequence from `SequenceGenerator`, instanceId from `InstanceIdProvider`, occurredAt = now, eventId = `UUID.randomUUID().toString()`.
9. `publisher.publish(event)` (best-effort; never throws — but we still call it from inside the success path).
10. Return the cached node.

**Service input contract:**
Because Phase 7 has no controller layer, `ItemService.createItem(...)` takes parsed primitives (no generated DTO). The controller in Phase 8 will adapt the generated `CreateItemRequest` (which has `data` as `Object`) by serialising `data` to a JSON string before calling the service. Service param order: `(long parentId, String name, String type, String dataJson, UserContext userContext)`.

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/service/ItemService.java` (Phase 7 grows this class across Tasks 7–11)
- Create: `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceCreateTest.java`

- [ ] **Step 1: Write the failing test class with the happy-path JSON-only create**

Create `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceCreateTest.java`:

```java
package com.myxcomp.ice.xtree.service;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.TreeCache;
import com.myxcomp.ice.xtree.common.InstanceIdProvider;
import com.myxcomp.ice.xtree.common.TimeMapper;
import com.myxcomp.ice.xtree.common.UserContext;
import com.myxcomp.ice.xtree.conversion.XmlJsonConverter;
import com.myxcomp.ice.xtree.messaging.EventPublisher;
import com.myxcomp.ice.xtree.messaging.SequenceGenerator;
import com.myxcomp.ice.xtree.messaging.event.OperationType;
import com.myxcomp.ice.xtree.messaging.event.TreeMutationEvent;
import com.myxcomp.ice.xtree.messaging.event.payload.CreatePayload;
import com.myxcomp.ice.xtree.persistence.ItemTreeRepository;
import com.myxcomp.ice.xtree.policy.TypePolicy;
import com.myxcomp.ice.xtree.service.exception.ErrorCode;
import com.myxcomp.ice.xtree.service.exception.NotFoundException;
import com.myxcomp.ice.xtree.service.exception.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.SyncTaskExecutor;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ItemServiceCreateTest {

    @Mock TreeCache cache;
    @Mock ItemTreeRepository repository;
    @Mock TypePolicy policy;
    @Mock XmlJsonConverter converter;
    @Mock EventPublisher publisher;
    @Mock TimeMapper timeMapper;
    @Mock InstanceIdProvider instanceIdProvider;
    @Mock SequenceGenerator sequenceGenerator;

    ItemService service;

    static final Instant NOW = Instant.parse("2026-05-16T12:00:00Z");
    static final UserContext CTX_DIRECT     = new UserContext("alice", null);
    static final UserContext CTX_IMPERSONAT = new UserContext("alice", "bob");

    @BeforeEach
    void setUp() {
        service = new ItemService(
                cache, repository, policy, converter, publisher,
                timeMapper, instanceIdProvider, sequenceGenerator,
                new SyncTaskExecutor());
    }

    private CachedNode folder(long id, long parentId, String name) {
        return new CachedNode(id, parentId, name, "Folder", NOW, "sys");
    }

    @Test
    void createJsonOnlyHappyPath() {
        when(cache.getById(2L)).thenReturn(Optional.of(folder(2L, 1L, "Users")));
        when(policy.hasData("Report")).thenReturn(true);
        when(policy.isAlsoPersistedAsXmlOnWrite("Report")).thenReturn(false);
        when(timeMapper.now()).thenReturn(NOW);
        when(repository.insert(eq(2L), eq("MyReport"), eq("Report"),
                eq("{\"a\":1}"), eq(null), eq(NOW), eq("alice"))).thenReturn(500L);
        when(instanceIdProvider.getInstanceId()).thenReturn("inst-1");
        when(sequenceGenerator.next()).thenReturn(7L);

        CachedNode created = service.createItem(2L, "MyReport", "Report", "{\"a\":1}", CTX_DIRECT);

        assertThat(created.itemTreeId()).isEqualTo(500L);
        assertThat(created.parentId()).isEqualTo(2L);
        assertThat(created.name()).isEqualTo("MyReport");
        assertThat(created.type()).isEqualTo("Report");
        assertThat(created.lastUpdate()).isEqualTo(NOW);
        assertThat(created.lastUpdateUser()).isEqualTo("alice");

        InOrder order = inOrder(repository, cache, publisher);
        order.verify(repository).insert(2L, "MyReport", "Report", "{\"a\":1}", null, NOW, "alice");
        order.verify(cache).applyCreate(created);

        ArgumentCaptor<TreeMutationEvent> evCap = ArgumentCaptor.forClass(TreeMutationEvent.class);
        order.verify(publisher).publish(evCap.capture());
        TreeMutationEvent ev = evCap.getValue();
        assertThat(ev.getEventId()).isNotBlank();
        assertThat(ev.getInstanceId()).isEqualTo("inst-1");
        assertThat(ev.getSequence()).isEqualTo(7L);
        assertThat(ev.getOccurredAt()).isEqualTo(NOW);
        assertThat(ev.getIceUser()).isEqualTo("alice");
        assertThat(ev.getImpersonatedUser()).isNull();
        assertThat(ev.getOperationType()).isEqualTo(OperationType.CREATE);
        assertThat(ev.getPayload()).isInstanceOf(CreatePayload.class);
        CreatePayload p = (CreatePayload) ev.getPayload();
        assertThat(p.itemTreeId()).isEqualTo(500L);
        assertThat(p.parentId()).isEqualTo(2L);
        assertThat(p.name()).isEqualTo("MyReport");
        assertThat(p.type()).isEqualTo("Report");
        assertThat(p.lastUpdate()).isEqualTo(NOW);
        assertThat(p.lastUpdateUser()).isEqualTo("alice");
        verifyNoInteractions(converter);
    }

    @Test
    void impersonatedUserStampingUsesImpersonated() {
        when(cache.getById(2L)).thenReturn(Optional.of(folder(2L, 1L, "Users")));
        when(policy.hasData("Report")).thenReturn(true);
        when(timeMapper.now()).thenReturn(NOW);
        when(repository.insert(anyLong(), anyString(), anyString(),
                anyString(), eq(null), any(), anyString())).thenReturn(501L);
        when(instanceIdProvider.getInstanceId()).thenReturn("inst-1");
        when(sequenceGenerator.next()).thenReturn(1L);

        service.createItem(2L, "R", "Report", "{}", CTX_IMPERSONAT);

        verify(repository).insert(2L, "R", "Report", "{}", null, NOW, "bob");
        ArgumentCaptor<TreeMutationEvent> evCap = ArgumentCaptor.forClass(TreeMutationEvent.class);
        verify(publisher).publish(evCap.capture());
        assertThat(evCap.getValue().getIceUser()).isEqualTo("alice");
        assertThat(evCap.getValue().getImpersonatedUser()).isEqualTo("bob");
    }

    @Test
    void createWithXmlFanOutConvertsAndPersistsBoth() {
        when(cache.getById(2L)).thenReturn(Optional.of(folder(2L, 1L, "Users")));
        when(policy.hasData("Report")).thenReturn(true);
        when(policy.isAlsoPersistedAsXmlOnWrite("Report")).thenReturn(true);
        when(converter.jsonToXml("{\"a\":1}")).thenReturn("<a>1</a>");
        when(timeMapper.now()).thenReturn(NOW);
        when(repository.insert(2L, "R", "Report", "{\"a\":1}", "<a>1</a>", NOW, "alice"))
                .thenReturn(502L);
        when(instanceIdProvider.getInstanceId()).thenReturn("inst-1");
        when(sequenceGenerator.next()).thenReturn(1L);

        service.createItem(2L, "R", "Report", "{\"a\":1}", CTX_DIRECT);

        verify(converter).jsonToXml("{\"a\":1}");
        verify(repository).insert(2L, "R", "Report", "{\"a\":1}", "<a>1</a>", NOW, "alice");
    }

    @Test
    void createFolderHappyPath() {
        when(cache.getById(2L)).thenReturn(Optional.of(folder(2L, 1L, "Users")));
        when(policy.hasData("Folder")).thenReturn(false);
        when(timeMapper.now()).thenReturn(NOW);
        when(repository.insert(2L, "Sub", "Folder", null, null, NOW, "alice")).thenReturn(503L);
        when(instanceIdProvider.getInstanceId()).thenReturn("inst-1");
        when(sequenceGenerator.next()).thenReturn(1L);

        CachedNode out = service.createItem(2L, "Sub", "Folder", null, CTX_DIRECT);

        assertThat(out.type()).isEqualTo("Folder");
        verify(repository).insert(2L, "Sub", "Folder", null, null, NOW, "alice");
        verifyNoInteractions(converter);
    }

    @Test
    void rejectsParentNotFound() {
        when(cache.getById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createItem(999L, "X", "Report", "{}", CTX_DIRECT))
                .isInstanceOf(NotFoundException.class)
                .satisfies(t -> assertThat(((NotFoundException) t).errorCode())
                        .isEqualTo(ErrorCode.PARENT_NOT_FOUND));

        verify(repository, never()).insert(anyLong(), anyString(), anyString(),
                anyString(), anyString(), any(), anyString());
        verifyNoInteractions(publisher);
    }

    @Test
    void rejectsParentNotFolder() {
        CachedNode nonFolder = new CachedNode(2L, 1L, "Doc", "Report", NOW, "sys");
        when(cache.getById(2L)).thenReturn(Optional.of(nonFolder));

        assertThatThrownBy(() -> service.createItem(2L, "X", "Report", "{}", CTX_DIRECT))
                .isInstanceOf(ValidationException.class)
                .satisfies(t -> assertThat(((ValidationException) t).errorCode())
                        .isEqualTo(ErrorCode.PARENT_NOT_FOLDER));

        verifyNoInteractions(publisher);
    }

    @Test
    void rejectsTypeCannotHaveData() {
        when(cache.getById(2L)).thenReturn(Optional.of(folder(2L, 1L, "Users")));
        when(policy.hasData("Folder")).thenReturn(false);

        assertThatThrownBy(() -> service.createItem(2L, "X", "Folder", "{}", CTX_DIRECT))
                .isInstanceOf(ValidationException.class)
                .satisfies(t -> assertThat(((ValidationException) t).errorCode())
                        .isEqualTo(ErrorCode.TYPE_CANNOT_HAVE_DATA));

        verifyNoInteractions(publisher);
    }

    @Test
    void rejectsDataRequired() {
        when(cache.getById(2L)).thenReturn(Optional.of(folder(2L, 1L, "Users")));
        when(policy.hasData("Report")).thenReturn(true);

        assertThatThrownBy(() -> service.createItem(2L, "X", "Report", null, CTX_DIRECT))
                .isInstanceOf(ValidationException.class)
                .satisfies(t -> assertThat(((ValidationException) t).errorCode())
                        .isEqualTo(ErrorCode.DATA_REQUIRED));

        verifyNoInteractions(publisher);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "com.myxcomp.ice.xtree.service.ItemServiceCreateTest"`
Expected: compilation failure — `ItemService` does not exist.

- [ ] **Step 3: Create the `ItemService` class with `createItem`**

Create `src/main/java/com/myxcomp/ice/xtree/service/ItemService.java`:

```java
package com.myxcomp.ice.xtree.service;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.TreeCache;
import com.myxcomp.ice.xtree.common.InstanceIdProvider;
import com.myxcomp.ice.xtree.common.TimeMapper;
import com.myxcomp.ice.xtree.common.Types;
import com.myxcomp.ice.xtree.common.UserContext;
import com.myxcomp.ice.xtree.conversion.XmlJsonConverter;
import com.myxcomp.ice.xtree.messaging.EventPublisher;
import com.myxcomp.ice.xtree.messaging.SequenceGenerator;
import com.myxcomp.ice.xtree.messaging.event.OperationType;
import com.myxcomp.ice.xtree.messaging.event.TreeMutationEvent;
import com.myxcomp.ice.xtree.messaging.event.payload.CreatePayload;
import com.myxcomp.ice.xtree.messaging.event.payload.EventPayload;
import com.myxcomp.ice.xtree.persistence.ItemTreeRepository;
import com.myxcomp.ice.xtree.policy.TypePolicy;
import com.myxcomp.ice.xtree.service.exception.ErrorCode;
import com.myxcomp.ice.xtree.service.exception.NotFoundException;
import com.myxcomp.ice.xtree.service.exception.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class ItemService {

    private static final Logger log = LoggerFactory.getLogger(ItemService.class);

    private final TreeCache cache;
    private final ItemTreeRepository repository;
    private final TypePolicy policy;
    private final XmlJsonConverter converter;
    private final EventPublisher publisher;
    private final TimeMapper timeMapper;
    private final InstanceIdProvider instanceIdProvider;
    private final SequenceGenerator sequenceGenerator;
    private final TaskExecutor backfillExecutor;

    public ItemService(TreeCache cache,
                       ItemTreeRepository repository,
                       TypePolicy policy,
                       XmlJsonConverter converter,
                       EventPublisher publisher,
                       TimeMapper timeMapper,
                       InstanceIdProvider instanceIdProvider,
                       SequenceGenerator sequenceGenerator,
                       @Qualifier("backfillExecutor") TaskExecutor backfillExecutor) {
        this.cache = cache;
        this.repository = repository;
        this.policy = policy;
        this.converter = converter;
        this.publisher = publisher;
        this.timeMapper = timeMapper;
        this.instanceIdProvider = instanceIdProvider;
        this.sequenceGenerator = sequenceGenerator;
        this.backfillExecutor = backfillExecutor;
    }

    /**
     * Creates a new node under {@code parentId}. Order: validate → DB → cache → event.
     *
     * @throws NotFoundException   {@code PARENT_NOT_FOUND} when {@code parentId} is unknown to the cache
     * @throws ValidationException {@code PARENT_NOT_FOLDER} / {@code TYPE_CANNOT_HAVE_DATA} / {@code DATA_REQUIRED}
     */
    @Transactional
    public CachedNode createItem(long parentId, String name, String type, String dataJson,
                                 UserContext userContext) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(userContext, "userContext");

        CachedNode parent = cache.getById(parentId).orElseThrow(() -> new NotFoundException(
                ErrorCode.PARENT_NOT_FOUND, "Parent " + parentId + " not found"));
        if (!Types.isFolder(parent.type())) {
            throw new ValidationException(ErrorCode.PARENT_NOT_FOLDER,
                    "Parent " + parentId + " is not a folder (type=" + parent.type() + ")");
        }

        boolean hasData = policy.hasData(type);
        if (!hasData && dataJson != null) {
            throw new ValidationException(ErrorCode.TYPE_CANNOT_HAVE_DATA,
                    "Type '" + type + "' cannot carry data");
        }
        if (hasData && dataJson == null) {
            throw new ValidationException(ErrorCode.DATA_REQUIRED,
                    "Type '" + type + "' requires data");
        }

        String xmlOrNull = (hasData && policy.isAlsoPersistedAsXmlOnWrite(type))
                ? converter.jsonToXml(dataJson)
                : null;

        Instant now = timeMapper.now();
        String stampUser = userContext.effectiveUser();

        long id = repository.insert(parentId, name, type, dataJson, xmlOrNull, now, stampUser);

        CachedNode node = new CachedNode(id, parentId, name, type, now, stampUser);
        cache.applyCreate(node);

        publisher.publish(buildEvent(userContext, OperationType.CREATE,
                new CreatePayload(id, parentId, name, type, now, stampUser), now));

        return node;
    }

    private TreeMutationEvent buildEvent(UserContext ctx, OperationType op,
                                         EventPayload payload, Instant occurredAt) {
        return TreeMutationEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .instanceId(instanceIdProvider.getInstanceId())
                .sequence(sequenceGenerator.next())
                .occurredAt(occurredAt)
                .iceUser(ctx.iceUser())
                .impersonatedUser(ctx.impersonatedUser())
                .operationType(op)
                .payload(payload)
                .build();
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "com.myxcomp.ice.xtree.service.ItemServiceCreateTest"`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/service/ItemService.java \
        src/test/java/com/myxcomp/ice/xtree/service/ItemServiceCreateTest.java
git commit -m "feat(service): add ItemService.createItem with full validation matrix"
```

---

## Task 8: `ItemService` — delete operation

**Behaviour:**
- If `repository.cascadeDeleteSubtree(id)` returns empty (root absent in DB): no cache change, no event, no exception — silent no-op. (Mirrors the cache `applyDelete` tolerance contract; the design treats stale deletes as harmless.)
- Otherwise: `cache.applyDelete(new HashSet<>(deletedIds))` and publish a `DELETE` event with the full `deletedIds` list.

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/service/ItemService.java` — add `deleteItem`.
- Create: `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceDeleteTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceDeleteTest.java`:

```java
package com.myxcomp.ice.xtree.service;

import com.myxcomp.ice.xtree.cache.TreeCache;
import com.myxcomp.ice.xtree.common.InstanceIdProvider;
import com.myxcomp.ice.xtree.common.TimeMapper;
import com.myxcomp.ice.xtree.common.UserContext;
import com.myxcomp.ice.xtree.conversion.XmlJsonConverter;
import com.myxcomp.ice.xtree.messaging.EventPublisher;
import com.myxcomp.ice.xtree.messaging.SequenceGenerator;
import com.myxcomp.ice.xtree.messaging.event.OperationType;
import com.myxcomp.ice.xtree.messaging.event.TreeMutationEvent;
import com.myxcomp.ice.xtree.messaging.event.payload.DeletePayload;
import com.myxcomp.ice.xtree.persistence.ItemTreeRepository;
import com.myxcomp.ice.xtree.policy.TypePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.SyncTaskExecutor;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ItemServiceDeleteTest {

    @Mock TreeCache cache;
    @Mock ItemTreeRepository repository;
    @Mock TypePolicy policy;
    @Mock XmlJsonConverter converter;
    @Mock EventPublisher publisher;
    @Mock TimeMapper timeMapper;
    @Mock InstanceIdProvider instanceIdProvider;
    @Mock SequenceGenerator sequenceGenerator;

    ItemService service;
    static final UserContext CTX = new UserContext("alice", null);

    @BeforeEach
    void setUp() {
        service = new ItemService(
                cache, repository, policy, converter, publisher,
                timeMapper, instanceIdProvider, sequenceGenerator,
                new SyncTaskExecutor());
    }

    @Test
    void deleteCascadesAndBroadcasts() {
        when(repository.cascadeDeleteSubtree(50L)).thenReturn(List.of(50L, 51L, 52L));
        when(timeMapper.now()).thenReturn(java.time.Instant.parse("2026-05-16T12:00:00Z"));
        when(instanceIdProvider.getInstanceId()).thenReturn("inst-1");
        when(sequenceGenerator.next()).thenReturn(8L);

        service.deleteItem(50L, CTX);

        InOrder order = inOrder(repository, cache, publisher);
        order.verify(repository).cascadeDeleteSubtree(50L);
        order.verify(cache).applyDelete(Set.of(50L, 51L, 52L));

        ArgumentCaptor<TreeMutationEvent> cap = ArgumentCaptor.forClass(TreeMutationEvent.class);
        order.verify(publisher).publish(cap.capture());
        TreeMutationEvent ev = cap.getValue();
        assertThat(ev.getOperationType()).isEqualTo(OperationType.DELETE);
        assertThat(ev.getPayload()).isInstanceOf(DeletePayload.class);
        assertThat(((DeletePayload) ev.getPayload()).deletedIds())
                .containsExactly(50L, 51L, 52L);
    }

    @Test
    void deleteOfMissingIdIsSilentNoOp() {
        when(repository.cascadeDeleteSubtree(999L)).thenReturn(List.of());

        service.deleteItem(999L, CTX);

        verify(repository).cascadeDeleteSubtree(999L);
        verifyNoInteractions(publisher);
        verify(cache, org.mockito.Mockito.never()).applyDelete(org.mockito.ArgumentMatchers.any());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "com.myxcomp.ice.xtree.service.ItemServiceDeleteTest"`
Expected: compilation failure — `ItemService.deleteItem` does not exist.

- [ ] **Step 3: Add `deleteItem` to `ItemService`**

Append to `ItemService` (before the private `buildEvent`):

```java
    /**
     * Cascade-deletes {@code id} and all descendants. Silent no-op if {@code id} is unknown.
     * Order: DB cascade → cache.applyDelete → event.
     */
    @Transactional
    public void deleteItem(long id, UserContext userContext) {
        Objects.requireNonNull(userContext, "userContext");
        java.util.List<Long> deletedIds = repository.cascadeDeleteSubtree(id);
        if (deletedIds.isEmpty()) {
            log.info("deleteItem: id={} not present in DB; no-op", id);
            return;
        }
        cache.applyDelete(new java.util.HashSet<>(deletedIds));
        Instant now = timeMapper.now();
        publisher.publish(buildEvent(userContext, OperationType.DELETE,
                new com.myxcomp.ice.xtree.messaging.event.payload.DeletePayload(java.util.List.copyOf(deletedIds)),
                now));
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "com.myxcomp.ice.xtree.service.ItemServiceDeleteTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/service/ItemService.java \
        src/test/java/com/myxcomp/ice/xtree/service/ItemServiceDeleteTest.java
git commit -m "feat(service): add ItemService.deleteItem with cascade + DELETE broadcast"
```

---

## Task 9: `ItemService` — rename operation

**Behaviour:**
- Validate id exists; if not, `NotFoundException(ITEM_NOT_FOUND)`.
- `now = timeMapper.now()`; `stampUser = userContext.effectiveUser()`.
- `repository.updateName(id, newName, now, stampUser)`.
- `cache.applyRename(id, newName, now, stampUser)`.
- Publish `RENAME` event with `RenamePayload(id, newName, now, stampUser)`.
- Return `cache.getById(id).orElseThrow(...)` (defensive — should never throw here; we just applied).

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/service/ItemService.java`
- Create: `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceRenameTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceRenameTest.java`:

```java
package com.myxcomp.ice.xtree.service;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.TreeCache;
import com.myxcomp.ice.xtree.common.InstanceIdProvider;
import com.myxcomp.ice.xtree.common.TimeMapper;
import com.myxcomp.ice.xtree.common.UserContext;
import com.myxcomp.ice.xtree.conversion.XmlJsonConverter;
import com.myxcomp.ice.xtree.messaging.EventPublisher;
import com.myxcomp.ice.xtree.messaging.SequenceGenerator;
import com.myxcomp.ice.xtree.messaging.event.OperationType;
import com.myxcomp.ice.xtree.messaging.event.TreeMutationEvent;
import com.myxcomp.ice.xtree.messaging.event.payload.RenamePayload;
import com.myxcomp.ice.xtree.persistence.ItemTreeRepository;
import com.myxcomp.ice.xtree.policy.TypePolicy;
import com.myxcomp.ice.xtree.service.exception.ErrorCode;
import com.myxcomp.ice.xtree.service.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.SyncTaskExecutor;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ItemServiceRenameTest {

    @Mock TreeCache cache;
    @Mock ItemTreeRepository repository;
    @Mock TypePolicy policy;
    @Mock XmlJsonConverter converter;
    @Mock EventPublisher publisher;
    @Mock TimeMapper timeMapper;
    @Mock InstanceIdProvider instanceIdProvider;
    @Mock SequenceGenerator sequenceGenerator;

    ItemService service;
    static final Instant NOW = Instant.parse("2026-05-16T12:00:00Z");
    static final UserContext CTX = new UserContext("alice", null);

    @BeforeEach
    void setUp() {
        service = new ItemService(cache, repository, policy, converter, publisher,
                timeMapper, instanceIdProvider, sequenceGenerator, new SyncTaskExecutor());
    }

    @Test
    void renameHappyPath() {
        CachedNode before = new CachedNode(7L, 1L, "Old", "Report", Instant.EPOCH, "u");
        CachedNode after  = new CachedNode(7L, 1L, "New", "Report", NOW, "alice");
        when(cache.getById(7L)).thenReturn(Optional.of(before), Optional.of(after));
        when(timeMapper.now()).thenReturn(NOW);
        when(instanceIdProvider.getInstanceId()).thenReturn("inst-1");
        when(sequenceGenerator.next()).thenReturn(11L);

        CachedNode result = service.renameItem(7L, "New", CTX);

        assertThat(result).isEqualTo(after);
        InOrder order = inOrder(repository, cache, publisher);
        order.verify(repository).updateName(7L, "New", NOW, "alice");
        order.verify(cache).applyRename(7L, "New", NOW, "alice");

        ArgumentCaptor<TreeMutationEvent> cap = ArgumentCaptor.forClass(TreeMutationEvent.class);
        order.verify(publisher).publish(cap.capture());
        assertThat(cap.getValue().getOperationType()).isEqualTo(OperationType.RENAME);
        RenamePayload payload = (RenamePayload) cap.getValue().getPayload();
        assertThat(payload.itemTreeId()).isEqualTo(7L);
        assertThat(payload.newName()).isEqualTo("New");
        assertThat(payload.lastUpdate()).isEqualTo(NOW);
        assertThat(payload.lastUpdateUser()).isEqualTo("alice");
    }

    @Test
    void rejectsUnknownId() {
        when(cache.getById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.renameItem(99L, "X", CTX))
                .isInstanceOf(NotFoundException.class)
                .satisfies(t -> assertThat(((NotFoundException) t).errorCode())
                        .isEqualTo(ErrorCode.ITEM_NOT_FOUND));

        verifyNoInteractions(repository);
        verifyNoInteractions(publisher);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "com.myxcomp.ice.xtree.service.ItemServiceRenameTest"`
Expected: compilation failure — `ItemService.renameItem` does not exist.

- [ ] **Step 3: Add `renameItem` to `ItemService`**

Append to `ItemService` (before `buildEvent`):

```java
    /**
     * Renames {@code id} to {@code newName}.
     *
     * @throws NotFoundException {@code ITEM_NOT_FOUND} if {@code id} is unknown
     */
    @Transactional
    public CachedNode renameItem(long id, String newName, UserContext userContext) {
        Objects.requireNonNull(newName, "newName");
        Objects.requireNonNull(userContext, "userContext");

        if (cache.getById(id).isEmpty()) {
            throw new NotFoundException(ErrorCode.ITEM_NOT_FOUND, "Item " + id + " not found");
        }

        Instant now = timeMapper.now();
        String stampUser = userContext.effectiveUser();

        repository.updateName(id, newName, now, stampUser);
        cache.applyRename(id, newName, now, stampUser);

        publisher.publish(buildEvent(userContext, OperationType.RENAME,
                new com.myxcomp.ice.xtree.messaging.event.payload.RenamePayload(id, newName, now, stampUser),
                now));

        return cache.getById(id).orElseThrow(() -> new IllegalStateException(
                "Cache lost id " + id + " after applyRename"));
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "com.myxcomp.ice.xtree.service.ItemServiceRenameTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/service/ItemService.java \
        src/test/java/com/myxcomp/ice/xtree/service/ItemServiceRenameTest.java
git commit -m "feat(service): add ItemService.renameItem"
```

---

## Task 10: `ItemService` — move operation

**Validation order:**
1. Item exists → else `NotFoundException(ITEM_NOT_FOUND)`.
2. New parent exists → else `NotFoundException(NEW_PARENT_NOT_FOUND)`.
3. `id == newParentId` → `ValidationException(MOVE_INTO_DESCENDANT)` (moving into self is the trivial cycle case).
4. New parent is folder → else `ValidationException(NEW_PARENT_NOT_FOLDER)`.
5. New parent is not in subtree of `id`: `cache.isAncestor(id, newParentId) == false` → else `ValidationException(MOVE_INTO_DESCENDANT)`.
6. Read `oldParentId` from the existing cached node (needed for the `MovePayload`).
7. DB `updateParent` → cache `applyMove` → publish `MOVE` event.
8. Return refreshed `cache.getById(id)`.

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/service/ItemService.java`
- Create: `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceMoveTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceMoveTest.java`:

```java
package com.myxcomp.ice.xtree.service;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.TreeCache;
import com.myxcomp.ice.xtree.common.InstanceIdProvider;
import com.myxcomp.ice.xtree.common.TimeMapper;
import com.myxcomp.ice.xtree.common.UserContext;
import com.myxcomp.ice.xtree.conversion.XmlJsonConverter;
import com.myxcomp.ice.xtree.messaging.EventPublisher;
import com.myxcomp.ice.xtree.messaging.SequenceGenerator;
import com.myxcomp.ice.xtree.messaging.event.OperationType;
import com.myxcomp.ice.xtree.messaging.event.TreeMutationEvent;
import com.myxcomp.ice.xtree.messaging.event.payload.MovePayload;
import com.myxcomp.ice.xtree.persistence.ItemTreeRepository;
import com.myxcomp.ice.xtree.policy.TypePolicy;
import com.myxcomp.ice.xtree.service.exception.ErrorCode;
import com.myxcomp.ice.xtree.service.exception.NotFoundException;
import com.myxcomp.ice.xtree.service.exception.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.SyncTaskExecutor;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ItemServiceMoveTest {

    @Mock TreeCache cache;
    @Mock ItemTreeRepository repository;
    @Mock TypePolicy policy;
    @Mock XmlJsonConverter converter;
    @Mock EventPublisher publisher;
    @Mock TimeMapper timeMapper;
    @Mock InstanceIdProvider instanceIdProvider;
    @Mock SequenceGenerator sequenceGenerator;

    ItemService service;
    static final Instant NOW = Instant.parse("2026-05-16T12:00:00Z");
    static final UserContext CTX = new UserContext("alice", null);

    @BeforeEach
    void setUp() {
        service = new ItemService(cache, repository, policy, converter, publisher,
                timeMapper, instanceIdProvider, sequenceGenerator, new SyncTaskExecutor());
    }

    private CachedNode folder(long id, long parentId, String name) {
        return new CachedNode(id, parentId, name, "Folder", Instant.EPOCH, "sys");
    }

    @Test
    void moveHappyPath() {
        CachedNode item = new CachedNode(7L, 2L, "doc", "Report", Instant.EPOCH, "u");
        CachedNode oldNew = folder(3L, 1L, "Archive");
        CachedNode itemAfter = new CachedNode(7L, 3L, "doc", "Report", NOW, "alice");
        when(cache.getById(7L)).thenReturn(Optional.of(item), Optional.of(itemAfter));
        when(cache.getById(3L)).thenReturn(Optional.of(oldNew));
        when(cache.isAncestor(7L, 3L)).thenReturn(false);
        when(timeMapper.now()).thenReturn(NOW);
        when(instanceIdProvider.getInstanceId()).thenReturn("inst-1");
        when(sequenceGenerator.next()).thenReturn(2L);

        CachedNode result = service.moveItem(7L, 3L, CTX);

        assertThat(result).isEqualTo(itemAfter);
        InOrder order = inOrder(repository, cache, publisher);
        order.verify(repository).updateParent(7L, 3L, NOW, "alice");
        order.verify(cache).applyMove(7L, 3L, NOW, "alice");
        ArgumentCaptor<TreeMutationEvent> cap = ArgumentCaptor.forClass(TreeMutationEvent.class);
        order.verify(publisher).publish(cap.capture());
        MovePayload payload = (MovePayload) cap.getValue().getPayload();
        assertThat(cap.getValue().getOperationType()).isEqualTo(OperationType.MOVE);
        assertThat(payload.itemTreeId()).isEqualTo(7L);
        assertThat(payload.oldParentId()).isEqualTo(2L);
        assertThat(payload.newParentId()).isEqualTo(3L);
    }

    @Test
    void rejectsItemNotFound() {
        when(cache.getById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.moveItem(99L, 3L, CTX))
                .isInstanceOf(NotFoundException.class)
                .satisfies(t -> assertThat(((NotFoundException) t).errorCode())
                        .isEqualTo(ErrorCode.ITEM_NOT_FOUND));
        verifyNoInteractions(publisher);
    }

    @Test
    void rejectsNewParentNotFound() {
        CachedNode item = new CachedNode(7L, 2L, "doc", "Report", Instant.EPOCH, "u");
        when(cache.getById(7L)).thenReturn(Optional.of(item));
        when(cache.getById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.moveItem(7L, 999L, CTX))
                .isInstanceOf(NotFoundException.class)
                .satisfies(t -> assertThat(((NotFoundException) t).errorCode())
                        .isEqualTo(ErrorCode.NEW_PARENT_NOT_FOUND));
        verifyNoInteractions(publisher);
    }

    @Test
    void rejectsMoveIntoSelf() {
        CachedNode item = new CachedNode(7L, 2L, "doc", "Report", Instant.EPOCH, "u");
        when(cache.getById(7L)).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> service.moveItem(7L, 7L, CTX))
                .isInstanceOf(ValidationException.class)
                .satisfies(t -> assertThat(((ValidationException) t).errorCode())
                        .isEqualTo(ErrorCode.MOVE_INTO_DESCENDANT));
        verifyNoInteractions(publisher);
    }

    @Test
    void rejectsNewParentNotFolder() {
        CachedNode item = new CachedNode(7L, 2L, "doc", "Report", Instant.EPOCH, "u");
        CachedNode nonFolder = new CachedNode(3L, 1L, "Other", "Report", Instant.EPOCH, "u");
        when(cache.getById(7L)).thenReturn(Optional.of(item));
        when(cache.getById(3L)).thenReturn(Optional.of(nonFolder));

        assertThatThrownBy(() -> service.moveItem(7L, 3L, CTX))
                .isInstanceOf(ValidationException.class)
                .satisfies(t -> assertThat(((ValidationException) t).errorCode())
                        .isEqualTo(ErrorCode.NEW_PARENT_NOT_FOLDER));
        verifyNoInteractions(publisher);
    }

    @Test
    void rejectsMoveIntoDescendant() {
        CachedNode item = folder(7L, 2L, "doc");
        CachedNode child = folder(8L, 7L, "sub");
        when(cache.getById(7L)).thenReturn(Optional.of(item));
        when(cache.getById(8L)).thenReturn(Optional.of(child));
        when(cache.isAncestor(7L, 8L)).thenReturn(true);

        assertThatThrownBy(() -> service.moveItem(7L, 8L, CTX))
                .isInstanceOf(ValidationException.class)
                .satisfies(t -> assertThat(((ValidationException) t).errorCode())
                        .isEqualTo(ErrorCode.MOVE_INTO_DESCENDANT));
        verifyNoInteractions(publisher);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "com.myxcomp.ice.xtree.service.ItemServiceMoveTest"`
Expected: compilation failure — `ItemService.moveItem` does not exist.

- [ ] **Step 3: Add `moveItem` to `ItemService`**

Append to `ItemService` (before `buildEvent`):

```java
    /**
     * Moves {@code id} under {@code newParentId}. Validation order:
     * ITEM_NOT_FOUND, NEW_PARENT_NOT_FOUND, MOVE_INTO_DESCENDANT (self), NEW_PARENT_NOT_FOLDER, MOVE_INTO_DESCENDANT (ancestor walk).
     */
    @Transactional
    public CachedNode moveItem(long id, long newParentId, UserContext userContext) {
        Objects.requireNonNull(userContext, "userContext");

        CachedNode item = cache.getById(id).orElseThrow(() -> new NotFoundException(
                ErrorCode.ITEM_NOT_FOUND, "Item " + id + " not found"));

        if (id == newParentId) {
            throw new ValidationException(ErrorCode.MOVE_INTO_DESCENDANT,
                    "Cannot move item into itself (id=" + id + ")");
        }

        CachedNode newParent = cache.getById(newParentId).orElseThrow(() -> new NotFoundException(
                ErrorCode.NEW_PARENT_NOT_FOUND, "New parent " + newParentId + " not found"));

        if (!Types.isFolder(newParent.type())) {
            throw new ValidationException(ErrorCode.NEW_PARENT_NOT_FOLDER,
                    "New parent " + newParentId + " is not a folder (type=" + newParent.type() + ")");
        }

        if (cache.isAncestor(id, newParentId)) {
            throw new ValidationException(ErrorCode.MOVE_INTO_DESCENDANT,
                    "Cannot move id=" + id + " under its own descendant " + newParentId);
        }

        Instant now = timeMapper.now();
        String stampUser = userContext.effectiveUser();
        long oldParentId = item.parentId();

        repository.updateParent(id, newParentId, now, stampUser);
        cache.applyMove(id, newParentId, now, stampUser);

        publisher.publish(buildEvent(userContext, OperationType.MOVE,
                new com.myxcomp.ice.xtree.messaging.event.payload.MovePayload(
                        id, oldParentId, newParentId, now, stampUser),
                now));

        return cache.getById(id).orElseThrow(() -> new IllegalStateException(
                "Cache lost id " + id + " after applyMove"));
    }
```

**Note on validation order:** The test `rejectsMoveIntoSelf` asserts the self-as-parent check fires *before* the new-parent existence check (since `7L == 7L` and we already have `item` cached, we can short-circuit). The test for `NEW_PARENT_NOT_FOUND` uses different ids (7 vs 999) so it passes through the self-check first.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "com.myxcomp.ice.xtree.service.ItemServiceMoveTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/service/ItemService.java \
        src/test/java/com/myxcomp/ice/xtree/service/ItemServiceMoveTest.java
git commit -m "feat(service): add ItemService.moveItem with full validation matrix"
```

---

## Task 11: `ItemService` — updateData operation

**Behaviour:**
- Validate id exists → `NotFoundException(ITEM_NOT_FOUND)`.
- Validate type is not Folder → `ValidationException(FOLDER_CANNOT_HAVE_DATA)` (per design §3 wording).
- For other types-without-data (Shortcut etc): `ValidationException(TYPE_CANNOT_HAVE_DATA)`.
- `dataJson` must be non-null (the OpenAPI marks `data` required for `UpdateDataRequest` so the controller guarantees this; we still null-check defensively and throw `DATA_REQUIRED` if violated).
- XML fan-out: if `policy.isAlsoPersistedAsXmlOnWrite(type)`, also compute XML.
- `repository.updateJson(id, dataJson, xmlOrNull, now, stampUser)` (JSON column update bumps LASTUPDATE per §12).
- `cache.applyMetadataUpdate(id, now, stampUser)` (cache stores no payload, only metadata).
- Publish `UPDATE` event with `UpdatePayload(id, now, stampUser)` — metadata only, JSON is never broadcast per §6.

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/service/ItemService.java`
- Create: `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceUpdateDataTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceUpdateDataTest.java`:

```java
package com.myxcomp.ice.xtree.service;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.TreeCache;
import com.myxcomp.ice.xtree.common.InstanceIdProvider;
import com.myxcomp.ice.xtree.common.TimeMapper;
import com.myxcomp.ice.xtree.common.UserContext;
import com.myxcomp.ice.xtree.conversion.XmlJsonConverter;
import com.myxcomp.ice.xtree.messaging.EventPublisher;
import com.myxcomp.ice.xtree.messaging.SequenceGenerator;
import com.myxcomp.ice.xtree.messaging.event.OperationType;
import com.myxcomp.ice.xtree.messaging.event.TreeMutationEvent;
import com.myxcomp.ice.xtree.messaging.event.payload.UpdatePayload;
import com.myxcomp.ice.xtree.persistence.ItemTreeRepository;
import com.myxcomp.ice.xtree.policy.TypePolicy;
import com.myxcomp.ice.xtree.service.exception.ErrorCode;
import com.myxcomp.ice.xtree.service.exception.NotFoundException;
import com.myxcomp.ice.xtree.service.exception.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.SyncTaskExecutor;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ItemServiceUpdateDataTest {

    @Mock TreeCache cache;
    @Mock ItemTreeRepository repository;
    @Mock TypePolicy policy;
    @Mock XmlJsonConverter converter;
    @Mock EventPublisher publisher;
    @Mock TimeMapper timeMapper;
    @Mock InstanceIdProvider instanceIdProvider;
    @Mock SequenceGenerator sequenceGenerator;

    ItemService service;
    static final Instant NOW = Instant.parse("2026-05-16T12:00:00Z");
    static final UserContext CTX = new UserContext("alice", null);

    @BeforeEach
    void setUp() {
        service = new ItemService(cache, repository, policy, converter, publisher,
                timeMapper, instanceIdProvider, sequenceGenerator, new SyncTaskExecutor());
    }

    @Test
    void updateJsonOnlyHappyPath() {
        CachedNode before = new CachedNode(7L, 1L, "Doc", "Report", Instant.EPOCH, "u");
        CachedNode after  = new CachedNode(7L, 1L, "Doc", "Report", NOW, "alice");
        when(cache.getById(7L)).thenReturn(Optional.of(before), Optional.of(after));
        when(policy.isAlsoPersistedAsXmlOnWrite("Report")).thenReturn(false);
        when(timeMapper.now()).thenReturn(NOW);
        when(instanceIdProvider.getInstanceId()).thenReturn("inst-1");
        when(sequenceGenerator.next()).thenReturn(3L);

        CachedNode result = service.updateItemData(7L, "{\"a\":2}", CTX);

        assertThat(result).isEqualTo(after);
        InOrder order = inOrder(repository, cache, publisher);
        order.verify(repository).updateJson(7L, "{\"a\":2}", null, NOW, "alice");
        order.verify(cache).applyMetadataUpdate(7L, NOW, "alice");

        ArgumentCaptor<TreeMutationEvent> cap = ArgumentCaptor.forClass(TreeMutationEvent.class);
        order.verify(publisher).publish(cap.capture());
        assertThat(cap.getValue().getOperationType()).isEqualTo(OperationType.UPDATE);
        UpdatePayload payload = (UpdatePayload) cap.getValue().getPayload();
        assertThat(payload.itemTreeId()).isEqualTo(7L);
        assertThat(payload.lastUpdate()).isEqualTo(NOW);
        assertThat(payload.lastUpdateUser()).isEqualTo("alice");
        verifyNoInteractions(converter);
    }

    @Test
    void updateWithXmlFanOutConvertsAndPersistsBoth() {
        CachedNode before = new CachedNode(7L, 1L, "Doc", "Report", Instant.EPOCH, "u");
        when(cache.getById(7L)).thenReturn(Optional.of(before), Optional.of(before));
        when(policy.isAlsoPersistedAsXmlOnWrite("Report")).thenReturn(true);
        when(converter.jsonToXml("{\"a\":2}")).thenReturn("<a>2</a>");
        when(timeMapper.now()).thenReturn(NOW);
        when(instanceIdProvider.getInstanceId()).thenReturn("inst-1");
        when(sequenceGenerator.next()).thenReturn(3L);

        service.updateItemData(7L, "{\"a\":2}", CTX);

        verify(converter).jsonToXml("{\"a\":2}");
        verify(repository).updateJson(7L, "{\"a\":2}", "<a>2</a>", NOW, "alice");
    }

    @Test
    void rejectsItemNotFound() {
        when(cache.getById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateItemData(99L, "{}", CTX))
                .isInstanceOf(NotFoundException.class)
                .satisfies(t -> assertThat(((NotFoundException) t).errorCode())
                        .isEqualTo(ErrorCode.ITEM_NOT_FOUND));
        verifyNoInteractions(publisher);
    }

    @Test
    void rejectsFolderCannotHaveData() {
        CachedNode folder = new CachedNode(7L, 1L, "F", "Folder", Instant.EPOCH, "u");
        when(cache.getById(7L)).thenReturn(Optional.of(folder));

        assertThatThrownBy(() -> service.updateItemData(7L, "{}", CTX))
                .isInstanceOf(ValidationException.class)
                .satisfies(t -> assertThat(((ValidationException) t).errorCode())
                        .isEqualTo(ErrorCode.FOLDER_CANNOT_HAVE_DATA));
        verifyNoInteractions(publisher);
    }

    @Test
    void rejectsTypeCannotHaveDataForNonFolderTypesWithoutData() {
        CachedNode shortcut = new CachedNode(7L, 1L, "S", "Shortcut", Instant.EPOCH, "u");
        when(cache.getById(7L)).thenReturn(Optional.of(shortcut));
        when(policy.hasData("Shortcut")).thenReturn(false);

        assertThatThrownBy(() -> service.updateItemData(7L, "{}", CTX))
                .isInstanceOf(ValidationException.class)
                .satisfies(t -> assertThat(((ValidationException) t).errorCode())
                        .isEqualTo(ErrorCode.TYPE_CANNOT_HAVE_DATA));
        verifyNoInteractions(publisher);
    }

    @Test
    void rejectsNullDataJson() {
        CachedNode node = new CachedNode(7L, 1L, "Doc", "Report", Instant.EPOCH, "u");
        when(cache.getById(7L)).thenReturn(Optional.of(node));
        when(policy.hasData("Report")).thenReturn(true);

        assertThatThrownBy(() -> service.updateItemData(7L, null, CTX))
                .isInstanceOf(ValidationException.class)
                .satisfies(t -> assertThat(((ValidationException) t).errorCode())
                        .isEqualTo(ErrorCode.DATA_REQUIRED));
        verifyNoInteractions(publisher);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "com.myxcomp.ice.xtree.service.ItemServiceUpdateDataTest"`
Expected: compilation failure — `ItemService.updateItemData` does not exist.

- [ ] **Step 3: Add `updateItemData` to `ItemService`**

Append to `ItemService` (before `buildEvent`):

```java
    /**
     * Replaces the JSON payload on {@code id}. Cache stores no payload, only the metadata
     * stamp; the JSON is broadcast as metadata-only in the {@code UPDATE} event (§6).
     */
    @Transactional
    public CachedNode updateItemData(long id, String dataJson, UserContext userContext) {
        Objects.requireNonNull(userContext, "userContext");

        CachedNode existing = cache.getById(id).orElseThrow(() -> new NotFoundException(
                ErrorCode.ITEM_NOT_FOUND, "Item " + id + " not found"));

        if (Types.isFolder(existing.type())) {
            throw new ValidationException(ErrorCode.FOLDER_CANNOT_HAVE_DATA,
                    "Folder " + id + " cannot carry data");
        }
        if (!policy.hasData(existing.type())) {
            throw new ValidationException(ErrorCode.TYPE_CANNOT_HAVE_DATA,
                    "Type '" + existing.type() + "' cannot carry data");
        }
        if (dataJson == null) {
            throw new ValidationException(ErrorCode.DATA_REQUIRED,
                    "Update of id=" + id + " requires data");
        }

        String xmlOrNull = policy.isAlsoPersistedAsXmlOnWrite(existing.type())
                ? converter.jsonToXml(dataJson)
                : null;

        Instant now = timeMapper.now();
        String stampUser = userContext.effectiveUser();

        repository.updateJson(id, dataJson, xmlOrNull, now, stampUser);
        cache.applyMetadataUpdate(id, now, stampUser);

        publisher.publish(buildEvent(userContext, OperationType.UPDATE,
                new com.myxcomp.ice.xtree.messaging.event.payload.UpdatePayload(id, now, stampUser),
                now));

        return cache.getById(id).orElseThrow(() -> new IllegalStateException(
                "Cache lost id " + id + " after applyMetadataUpdate"));
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "com.myxcomp.ice.xtree.service.ItemServiceUpdateDataTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/service/ItemService.java \
        src/test/java/com/myxcomp/ice/xtree/service/ItemServiceUpdateDataTest.java
git commit -m "feat(service): add ItemService.updateItemData with type-policy validation"
```

---

## Task 12: `AsyncConfig` — backfill `TaskExecutor` bean

**Why now:** Task 13 (`getItemsWithData`) consumes this executor. Setting it up first keeps the next task focused on the read-shaping logic.

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/config/AsyncConfig.java`
- Create: `src/test/java/com/myxcomp/ice/xtree/config/AsyncConfigTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/myxcomp/ice/xtree/config/AsyncConfigTest.java`:

```java
package com.myxcomp.ice.xtree.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncConfigTest {

    @Test
    void backfillExecutorIsSingleThreadedWithBoundedQueueAndAbortPolicy() {
        AsyncConfig config = new AsyncConfig();
        ThreadPoolTaskExecutor exec = config.backfillExecutor();

        assertThat(exec.getCorePoolSize()).isEqualTo(1);
        assertThat(exec.getMaxPoolSize()).isEqualTo(1);
        assertThat(exec.getThreadNamePrefix()).isEqualTo("backfill-");
        assertThat(exec.getThreadPoolExecutor().getRejectedExecutionHandler())
                .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
        assertThat(exec.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(100);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "com.myxcomp.ice.xtree.config.AsyncConfigTest"`
Expected: compilation failure — `AsyncConfig` does not exist.

- [ ] **Step 3: Implement `AsyncConfig`**

Create `src/main/java/com/myxcomp/ice/xtree/config/AsyncConfig.java`:

```java
package com.myxcomp.ice.xtree.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Async machinery used by service-layer fire-and-forget work.
 *
 * <p>Only bean for Phase 7: {@code backfillExecutor}, a single-threaded bounded-queue executor
 * used by {@link com.myxcomp.ice.xtree.service.ItemService#getItemsWithData} to schedule the
 * silent JSON-column backfill (design §11). Bounded queue + AbortPolicy gives us bounded memory
 * and visible backpressure: a saturated executor throws {@code RejectedExecutionException}
 * rather than silently piling work up.
 */
@Configuration
public class AsyncConfig {

    @Bean("backfillExecutor")
    public ThreadPoolTaskExecutor backfillExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(1);
        exec.setMaxPoolSize(1);
        exec.setQueueCapacity(100);
        exec.setThreadNamePrefix("backfill-");
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        exec.initialize();
        return exec;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "com.myxcomp.ice.xtree.config.AsyncConfigTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/config/AsyncConfig.java \
        src/test/java/com/myxcomp/ice/xtree/config/AsyncConfigTest.java
git commit -m "feat(config): add backfillExecutor TaskExecutor bean"
```

---

## Task 13: `ItemService` — getItemsWithData (+ async backfill)

**The most intricate task in Phase 7. Behaviour per design §3 / §10 / §11:**

1. For each requested id, fetch the structural node from the cache. Missing ids → silently omit.
2. For folder nodes: response carries no payload but **includes one level of children** (each child also shaped per the data matrix below). Child-of-child is empty list (one level only).
3. For non-folder, `hasData(type)` nodes: must fetch payload from DB. Collect *every* such id (the requested non-folder nodes plus the non-folder children of requested folders) into a single `findPayloadByIds` call.
4. Shape per type and per row state — ICEX matrix only (`types-sent-as-xml-to-ui` is empty):
   - JSON present → `dataJson = json`, no backfill.
   - JSON null, XML present → `dataJson = converter.xmlToJson(xml)`, schedule **async backfill** of the JSON column.
   - Both null → both fields null.
5. For `!hasData(type)` (Folder, Shortcut, …) — payload columns are not consulted; both `dataJson` and `dataXml` are null.
6. Async backfill: collect `(id, convertedJson)` pairs; on response-completion side, submit one `Runnable` to `backfillExecutor` that calls `repository.backfillJsonWhereNull(rows)` once (batched per request, design §11).
7. Empty input list → return `List.of()` immediately.

**Implementation note — when to fire backfill:** the task is submitted *before* returning, but using `TaskExecutor.execute`, so the executor's bounded queue absorbs the work. From the caller's perspective the response is built synchronously; from the system's perspective the backfill happens off the request thread.

**Test strategy:** `SyncTaskExecutor` from Spring is used in tests so the backfill runs inline — making assertions on `repository.backfillJsonWhereNull` deterministic without `Awaitility`.

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/service/ItemWithData.java`
- Modify: `src/main/java/com/myxcomp/ice/xtree/service/ItemService.java` — add `getItemsWithData`.
- Create: `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceGetItemsTest.java`

- [ ] **Step 1: Create the `ItemWithData` record**

Create `src/main/java/com/myxcomp/ice/xtree/service/ItemWithData.java`:

```java
package com.myxcomp.ice.xtree.service;

import java.time.Instant;
import java.util.List;

/**
 * Service-layer shape for {@code POST /items/get} response items.
 * For folder nodes: {@code dataJson} and {@code dataXml} are null, and {@code children} is
 * populated one level deep (each child's {@code children} is empty list).
 * For non-folder, data-bearing nodes: at most one of {@code dataJson}/{@code dataXml} is
 * populated, and {@code children} is empty list.
 */
public record ItemWithData(
        long itemTreeId,
        Long parentId,
        String name,
        String type,
        Instant lastUpdate,
        String lastUpdateUser,
        String dataJson,
        String dataXml,
        List<ItemWithData> children
) {}
```

- [ ] **Step 2: Write the failing test**

Create `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceGetItemsTest.java`:

```java
package com.myxcomp.ice.xtree.service;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.TreeCache;
import com.myxcomp.ice.xtree.common.InstanceIdProvider;
import com.myxcomp.ice.xtree.common.TimeMapper;
import com.myxcomp.ice.xtree.common.UserContext;
import com.myxcomp.ice.xtree.conversion.XmlJsonConverter;
import com.myxcomp.ice.xtree.messaging.EventPublisher;
import com.myxcomp.ice.xtree.messaging.SequenceGenerator;
import com.myxcomp.ice.xtree.persistence.ItemTreeRepository;
import com.myxcomp.ice.xtree.persistence.JsonBackfillRow;
import com.myxcomp.ice.xtree.persistence.PayloadRow;
import com.myxcomp.ice.xtree.policy.TypePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.SyncTaskExecutor;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ItemServiceGetItemsTest {

    @Mock TreeCache cache;
    @Mock ItemTreeRepository repository;
    @Mock TypePolicy policy;
    @Mock XmlJsonConverter converter;
    @Mock EventPublisher publisher;
    @Mock TimeMapper timeMapper;
    @Mock InstanceIdProvider instanceIdProvider;
    @Mock SequenceGenerator sequenceGenerator;

    ItemService service;
    static final Instant T = Instant.EPOCH;

    @BeforeEach
    void setUp() {
        service = new ItemService(cache, repository, policy, converter, publisher,
                timeMapper, instanceIdProvider, sequenceGenerator, new SyncTaskExecutor());
    }

    private CachedNode folder(long id, long parentId, String name) {
        return new CachedNode(id, parentId, name, "Folder", T, "sys");
    }
    private CachedNode leaf(long id, long parentId, String name, String type) {
        return new CachedNode(id, parentId, name, type, T, "sys");
    }

    @Test
    void emptyInputReturnsEmptyList() {
        assertThat(service.getItemsWithData(List.of())).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    void missingIdsSilentlyOmitted() {
        when(cache.getById(7L)).thenReturn(Optional.empty());

        List<ItemWithData> result = service.getItemsWithData(List.of(7L));

        assertThat(result).isEmpty();
        verify(repository, never()).findPayloadByIds(anyCollection());
    }

    @Test
    void leafWithJsonPresentReturnsDataJsonNoBackfill() {
        CachedNode node = leaf(7L, 1L, "Doc", "Report");
        when(cache.getById(7L)).thenReturn(Optional.of(node));
        when(policy.hasData("Report")).thenReturn(true);
        when(repository.findPayloadByIds(List.of(7L)))
                .thenReturn(List.of(new PayloadRow(7L, "{\"a\":1}", null)));

        List<ItemWithData> result = service.getItemsWithData(List.of(7L));

        assertThat(result).hasSize(1);
        ItemWithData out = result.get(0);
        assertThat(out.itemTreeId()).isEqualTo(7L);
        assertThat(out.dataJson()).isEqualTo("{\"a\":1}");
        assertThat(out.dataXml()).isNull();
        assertThat(out.children()).isEmpty();
        verify(repository, never()).backfillJsonWhereNull(anyCollection());
    }

    @Test
    void leafWithJsonNullAndXmlPresentConvertsAndSchedulesBackfill() {
        CachedNode node = leaf(7L, 1L, "Doc", "Report");
        when(cache.getById(7L)).thenReturn(Optional.of(node));
        when(policy.hasData("Report")).thenReturn(true);
        when(policy.isSentAsXmlToUi("Report")).thenReturn(false);
        when(repository.findPayloadByIds(List.of(7L)))
                .thenReturn(List.of(new PayloadRow(7L, null, "<a>1</a>")));
        when(converter.xmlToJson("<a>1</a>")).thenReturn("{\"a\":1}");

        List<ItemWithData> result = service.getItemsWithData(List.of(7L));

        assertThat(result).hasSize(1);
        ItemWithData out = result.get(0);
        assertThat(out.dataJson()).isEqualTo("{\"a\":1}");
        assertThat(out.dataXml()).isNull();

        ArgumentCaptor<Collection<JsonBackfillRow>> cap = ArgumentCaptor.forClass(Collection.class);
        verify(repository).backfillJsonWhereNull(cap.capture());
        assertThat(cap.getValue()).containsExactly(new JsonBackfillRow(7L, "{\"a\":1}"));
    }

    @Test
    void leafWithBothNullReturnsNullDataAndNoBackfill() {
        CachedNode node = leaf(7L, 1L, "Doc", "Report");
        when(cache.getById(7L)).thenReturn(Optional.of(node));
        when(policy.hasData("Report")).thenReturn(true);
        when(repository.findPayloadByIds(List.of(7L)))
                .thenReturn(List.of(new PayloadRow(7L, null, null)));

        List<ItemWithData> result = service.getItemsWithData(List.of(7L));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).dataJson()).isNull();
        assertThat(result.get(0).dataXml()).isNull();
        verify(repository, never()).backfillJsonWhereNull(anyCollection());
    }

    @Test
    void typeWithoutDataNeverConsultsPayloadColumns() {
        CachedNode shortcut = leaf(7L, 1L, "S", "Shortcut");
        when(cache.getById(7L)).thenReturn(Optional.of(shortcut));
        when(policy.hasData("Shortcut")).thenReturn(false);

        List<ItemWithData> result = service.getItemsWithData(List.of(7L));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).dataJson()).isNull();
        assertThat(result.get(0).dataXml()).isNull();
        verify(repository, never()).findPayloadByIds(anyCollection());
    }

    @Test
    void folderExpandsOneLevelOfChildrenWithPayloadShaping() {
        CachedNode parent = folder(2L, 1L, "Box");
        CachedNode dataChild = leaf(3L, 2L, "Doc", "Report");
        CachedNode noDataChild = leaf(4L, 2L, "Short", "Shortcut");
        when(cache.getById(2L)).thenReturn(Optional.of(parent));
        when(cache.getChildren(2L)).thenReturn(List.of(dataChild, noDataChild));
        when(policy.hasData("Report")).thenReturn(true);
        when(policy.hasData("Shortcut")).thenReturn(false);
        when(repository.findPayloadByIds(List.of(3L)))
                .thenReturn(List.of(new PayloadRow(3L, "{\"k\":1}", null)));

        List<ItemWithData> result = service.getItemsWithData(List.of(2L));

        assertThat(result).hasSize(1);
        ItemWithData folderItem = result.get(0);
        assertThat(folderItem.dataJson()).isNull();
        assertThat(folderItem.dataXml()).isNull();
        assertThat(folderItem.children()).hasSize(2);

        ItemWithData childData = folderItem.children().stream()
                .filter(c -> c.itemTreeId() == 3L).findFirst().orElseThrow();
        assertThat(childData.dataJson()).isEqualTo("{\"k\":1}");
        assertThat(childData.children()).isEmpty();

        ItemWithData childNoData = folderItem.children().stream()
                .filter(c -> c.itemTreeId() == 4L).findFirst().orElseThrow();
        assertThat(childNoData.dataJson()).isNull();
        assertThat(childNoData.dataXml()).isNull();
        assertThat(childNoData.children()).isEmpty();
    }

    @Test
    void batchedPayloadFetchAndBackfill() {
        CachedNode a = leaf(7L, 1L, "A", "Report");
        CachedNode b = leaf(8L, 1L, "B", "Report");
        when(cache.getById(7L)).thenReturn(Optional.of(a));
        when(cache.getById(8L)).thenReturn(Optional.of(b));
        when(policy.hasData("Report")).thenReturn(true);
        when(repository.findPayloadByIds(List.of(7L, 8L))).thenReturn(List.of(
                new PayloadRow(7L, null, "<a/>"),
                new PayloadRow(8L, "{\"b\":2}", null)
        ));
        when(converter.xmlToJson("<a/>")).thenReturn("{}");

        service.getItemsWithData(List.of(7L, 8L));

        verify(repository).findPayloadByIds(List.of(7L, 8L));
        ArgumentCaptor<Collection<JsonBackfillRow>> cap = ArgumentCaptor.forClass(Collection.class);
        verify(repository).backfillJsonWhereNull(cap.capture());
        assertThat(cap.getValue()).containsExactly(new JsonBackfillRow(7L, "{}"));
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew test --tests "com.myxcomp.ice.xtree.service.ItemServiceGetItemsTest"`
Expected: compilation failure — `ItemService.getItemsWithData` and `ItemWithData` do not exist (record was added in Step 1, so failure is only on the method).

- [ ] **Step 4: Add `getItemsWithData` to `ItemService`**

Append to `ItemService`:

```java
    /**
     * Bulk fetch by id (POST /items/get). Missing ids are silently omitted. Folders are
     * expanded one level (children carry their own shaped payload). Non-folder data-bearing
     * nodes consult the DB; rows with {@code JSON IS NULL AND XML IS NOT NULL} are converted
     * for the response and queued for async backfill (design §11).
     */
    public java.util.List<ItemWithData> getItemsWithData(java.util.List<Long> ids) {
        Objects.requireNonNull(ids, "ids");
        if (ids.isEmpty()) return java.util.List.of();

        // 1. Look up requested nodes + children of any folder; partition into folders / leaves.
        java.util.List<CachedNode> requested = new java.util.ArrayList<>();
        java.util.Map<Long, java.util.List<CachedNode>> folderChildren = new java.util.LinkedHashMap<>();
        for (Long id : ids) {
            if (id == null) continue;
            Optional<CachedNode> opt = cache.getById(id);
            if (opt.isEmpty()) continue;
            CachedNode n = opt.get();
            requested.add(n);
            if (Types.isFolder(n.type())) {
                folderChildren.put(n.itemTreeId(), cache.getChildren(n.itemTreeId()));
            }
        }

        // 2. Collect ids that need DB payload (non-folder, hasData(type)) from both layers.
        java.util.LinkedHashSet<Long> payloadIds = new java.util.LinkedHashSet<>();
        for (CachedNode n : requested) {
            if (!Types.isFolder(n.type()) && policy.hasData(n.type())) {
                payloadIds.add(n.itemTreeId());
            }
        }
        for (java.util.List<CachedNode> children : folderChildren.values()) {
            for (CachedNode c : children) {
                if (!Types.isFolder(c.type()) && policy.hasData(c.type())) {
                    payloadIds.add(c.itemTreeId());
                }
            }
        }

        java.util.Map<Long, PayloadRow> payloadById = new java.util.HashMap<>();
        if (!payloadIds.isEmpty()) {
            for (PayloadRow row : repository.findPayloadByIds(java.util.List.copyOf(payloadIds))) {
                payloadById.put(row.itemTreeId(), row);
            }
        }

        // 3. Shape each requested node, recursively shape folder children.
        java.util.List<com.myxcomp.ice.xtree.persistence.JsonBackfillRow> backfillBatch =
                new java.util.ArrayList<>();
        java.util.List<ItemWithData> out = new java.util.ArrayList<>(requested.size());
        for (CachedNode n : requested) {
            if (Types.isFolder(n.type())) {
                java.util.List<ItemWithData> shapedChildren = new java.util.ArrayList<>();
                for (CachedNode c : folderChildren.get(n.itemTreeId())) {
                    shapedChildren.add(shape(c, payloadById, backfillBatch, java.util.List.of()));
                }
                out.add(shape(n, payloadById, backfillBatch, java.util.List.copyOf(shapedChildren)));
            } else {
                out.add(shape(n, payloadById, backfillBatch, java.util.List.of()));
            }
        }

        // 4. Schedule backfill (one batched call per request).
        if (!backfillBatch.isEmpty()) {
            java.util.List<com.myxcomp.ice.xtree.persistence.JsonBackfillRow> snapshot =
                    java.util.List.copyOf(backfillBatch);
            backfillExecutor.execute(() -> {
                try {
                    repository.backfillJsonWhereNull(snapshot);
                } catch (RuntimeException e) {
                    log.warn("Backfill failed for {} rows: {}", snapshot.size(), e.getMessage());
                }
            });
        }

        return java.util.List.copyOf(out);
    }

    private ItemWithData shape(CachedNode n,
                               java.util.Map<Long, PayloadRow> payloadById,
                               java.util.List<com.myxcomp.ice.xtree.persistence.JsonBackfillRow> backfillBatch,
                               java.util.List<ItemWithData> children) {
        // Folder or type-without-data: no payload.
        if (Types.isFolder(n.type()) || !policy.hasData(n.type())) {
            return new ItemWithData(n.itemTreeId(), n.parentId(), n.name(), n.type(),
                    n.lastUpdate(), n.lastUpdateUser(), null, null, children);
        }

        PayloadRow row = payloadById.get(n.itemTreeId());
        String json = row != null ? row.json() : null;
        String xml  = row != null ? row.xml()  : null;

        // ICEX path (types-sent-as-xml-to-ui empty): we always ship JSON.
        if (policy.isSentAsXmlToUi(n.type())) {
            // Reserved for future legacy bridges (§10 full matrix). Ship XML as-is, or
            // convert JSON → XML on the fly if XML missing. No backfill in this branch.
            String shippedXml = xml != null ? xml : (json != null ? converter.jsonToXml(json) : null);
            return new ItemWithData(n.itemTreeId(), n.parentId(), n.name(), n.type(),
                    n.lastUpdate(), n.lastUpdateUser(), null, shippedXml, children);
        }

        if (json != null) {
            return new ItemWithData(n.itemTreeId(), n.parentId(), n.name(), n.type(),
                    n.lastUpdate(), n.lastUpdateUser(), json, null, children);
        }
        if (xml != null) {
            String convertedJson = converter.xmlToJson(xml);
            backfillBatch.add(new com.myxcomp.ice.xtree.persistence.JsonBackfillRow(
                    n.itemTreeId(), convertedJson));
            return new ItemWithData(n.itemTreeId(), n.parentId(), n.name(), n.type(),
                    n.lastUpdate(), n.lastUpdateUser(), convertedJson, null, children);
        }
        // Both null.
        return new ItemWithData(n.itemTreeId(), n.parentId(), n.name(), n.type(),
                n.lastUpdate(), n.lastUpdateUser(), null, null, children);
    }
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew test --tests "com.myxcomp.ice.xtree.service.ItemServiceGetItemsTest"`
Expected: PASS (7 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/service/ItemService.java \
        src/main/java/com/myxcomp/ice/xtree/service/ItemWithData.java \
        src/test/java/com/myxcomp/ice/xtree/service/ItemServiceGetItemsTest.java
git commit -m "feat(service): add ItemService.getItemsWithData with async JSON backfill"
```

---

## Task 14: Full-suite regression check

**Why:** Touching `TimeMapper` (Task 3) and adding new beans (`SequenceGenerator`, `EventPublisher` callers) could affect existing Spring context tests in `ItemTreeApplicationTests`. A clean run of `./gradlew clean build` proves Phase 7 hasn't broken anything earlier.

- [ ] **Step 1: Run the full build**

Run: `./gradlew clean build`
Expected: `BUILD SUCCESSFUL`. The test count should be Phase 6's 231 + this phase's new tests (≈40+). Confirm the count and that no test is skipped or disabled.

- [ ] **Step 2: If `ItemTreeApplicationTests` fails because no `EventPublisher` bean is wired**

This is the most likely regression. The Spring context now requires an `EventPublisher` bean because `ItemService` depends on it. Phase 7 does *not* introduce a production implementation — Phase 10 does. To keep Phase 7's context bootable, add a minimal dev-only no-op publisher.

Create `src/main/java/com/myxcomp/ice/xtree/messaging/dev/NoOpEventPublisher.java`:

```java
package com.myxcomp.ice.xtree.messaging.dev;

import com.myxcomp.ice.xtree.messaging.EventPublisher;
import com.myxcomp.ice.xtree.messaging.event.TreeMutationEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Phase-A placeholder. Logs the event at DEBUG and returns. Phase 10 supplants this with
 * {@code LocalLoopbackEventPublisher} (also {@code @Profile("dev")}) once the in-memory bus
 * is wired. Phase B supplies the JMS-backed implementation under {@code @Profile("prod")}.
 */
@Component
@Profile("dev")
public class NoOpEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(NoOpEventPublisher.class);

    @Override
    public void publish(TreeMutationEvent event) {
        log.debug("NoOpEventPublisher dropped event: {}", event.getOperationType());
    }
}
```

Re-run `./gradlew clean build`.
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit (only if the no-op publisher was needed)**

```bash
git add src/main/java/com/myxcomp/ice/xtree/messaging/dev/NoOpEventPublisher.java
git commit -m "feat(messaging): add Phase-A no-op EventPublisher to keep dev context bootable"
```

---

## Task 15: Update `IMPLEMENTATION_NOTES.md`

- [ ] **Step 1: Mark Phase 7 complete**

In `IMPLEMENTATION_NOTES.md`, change the `## Phase 7 — Services ⬅ NEXT` heading to:

```markdown
## Phase 7 — Services ✅ COMPLETE (2026-05-16)

**Deviations from plan (reviewed and approved):**
- `EventPublisher` interface and `SequenceGenerator` moved from Phase 10 into Phase 7 (services are the first caller). Phase 10 still owns the production JMS-backed implementations.
- Added a Phase-A `@Profile("dev")` `NoOpEventPublisher` in `messaging/dev/` so the Spring context boots before Phase 10 lands; Phase 10 will replace it with `LocalLoopbackEventPublisher`.
- `TimeMapper.now()` added as the application-wide clock entry point (instead of a separate `Clock` bean) — keeps the CLAUDE.md "TimeMapper is the only thing that touches the wall clock" invariant.
- Service-layer exception model: `ItemTreeException` abstract base + `NotFoundException` (404) + `ValidationException` (400), all carrying an `ErrorCode` enum. Phase 8 will map these.
- Async backfill uses a single `ThreadPoolTaskExecutor` (`backfillExecutor`, core/max=1, queue=100, `AbortPolicy`). Bounded queue + abort gives visible backpressure rather than silent OOM.

**Actual done state:** _N_ tests green; `./gradlew clean build` → BUILD SUCCESSFUL.
```

Then change the `## Phase 8 — HTTP layer` heading to `## Phase 8 — HTTP layer ⬅ NEXT`.

- [ ] **Step 2: Commit**

```bash
git add IMPLEMENTATION_NOTES.md
git commit -m "docs: mark Phase 7 complete; advance NEXT marker to Phase 8"
```

---

## Self-review

After all tasks pass:

1. **Spec coverage:**
   - §3 endpoints: `/items` (create) ✅, `/items/{id}` (delete) ✅, `/items/{id}/move` ✅, `/items/{id}/rename` ✅, `/items/{id}/data` ✅, `/items/get` ✅, `/tree` ✅, `/tree/{rootId}/subtree` ✅, `/search` ✅, `/users/{userName}/home-folder` ✅ — every service-layer method exists.
   - §5 write order (validate → DB → cache → event): enforced and verified via `InOrder` in every write test.
   - §10 validation matrix: `TYPE_CANNOT_HAVE_DATA`, `DATA_REQUIRED`, `FOLDER_CANNOT_HAVE_DATA` all tested.
   - §11 read-shaping matrix: ICEX three-row matrix covered; full XML-UI matrix is reachable via `policy.isSentAsXmlToUi(...)` in `shape()` (kept for future bridges).
   - §13 identity rules: `effectiveUser()` used for `lastUpdateUser`; both `iceUser` and `impersonatedUser` go on the event envelope.
   - IMPLEMENTATION_NOTES Phase 7 task list: `ItemService`, `TreeService`, `SearchService`, `HomeFolderService`, async backfill executor — all delivered. `PathResolver impl (interface from Phase 6)` already done in Phase 6 — confirmed.

2. **Placeholder scan:** No "TBD", "implement later", or "add appropriate error handling" remains. Every code block is complete.

3. **Type consistency:** All references to `ErrorCode` values cross-check against the enum (Task 1); all `*Payload` types match the records in `messaging/event/payload/`; `TreeMutationEvent.builder()...` matches the existing `@Builder` shape (Phase 2 lombok-generated).

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-16-phase7-services.md`. Two execution options:

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints.

Which approach?

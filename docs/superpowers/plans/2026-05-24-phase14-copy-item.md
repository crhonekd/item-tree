# Phase 14 — Copy Item Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a sixth mutation operation — `POST /api/v1/itemtree/items/{id}/copy` — that clones a single item or an entire folder subtree (up to a configurable cap, default 100 nodes) into a folder owned by the caller, broadcasting a single atomic `COPY` event to peers.

**Architecture:**
- DB-snapshot approach: cache validation → DB BFS inside the transaction (authoritative count) → pre-allocate N ids → batch INSERT → cache `applyCopy` under one write lock → broadcast single `COPY` event with BFS-ordered subtree payload.
- New OperationType + `CopyPayload(List<CopiedNode>)` event payload, mirroring the existing 5-op shape; payload's JSON/XML never broadcast (design §6).
- Cap enforced twice: pre-flight against the cache (fast fail before opening a transaction) and authoritative inside the DB transaction (defence against cache drift). Both throw `CopyTooLargeException` → HTTP 413.

**Tech Stack:** Java 21, Spring Boot 3.4, Spring `JdbcClient` + `JdbcTemplate.batchUpdate`, JUnit 5 + AssertJ + Mockito + Awaitility, H2 (Oracle compat mode). No new production dependencies; design spec at `docs/superpowers/specs/2026-05-24-copy-item-design.md`.

---

## File Structure

**New production files (all under `src/main/java/com/myxcomp/ice/xtree/`):**

| File | Responsibility |
|---|---|
| `service/exception/CopyTooLargeException.java` | New checked exception (extends `ItemTreeException`); the only condition that maps to HTTP 413. |
| `config/CopyProperties.java` | `@ConfigurationProperties("itemtree.copy")` record with `maxNodes` + `@PostConstruct` validation. |
| `persistence/ItemTreeFullRow.java` | Combined structural + payload row record (used only by the two new repo methods). |
| `messaging/event/payload/CopyPayload.java` | New `EventPayload` record carrying a `List<CopiedNode>` (nested record for the per-node shape). |

**Modified production files:**

| File | Change |
|---|---|
| `service/exception/ErrorCode.java` | Add 6 enum values: `CANNOT_COPY_ROOT`, `DESTINATION_NOT_FOUND`, `DESTINATION_NOT_FOLDER`, `DESTINATION_NOT_IN_USER_FOLDER`, `COPY_INTO_DESCENDANT`, `COPY_TOO_LARGE`. |
| `api/advice/GlobalExceptionHandler.java` | Add `@ExceptionHandler(CopyTooLargeException.class)` mapping to HTTP 413. |
| `messaging/event/OperationType.java` | Add `COPY`. |
| `messaging/event/TreeMutationEventDeserializer.java` | Add `case COPY -> CopyPayload.class` in `deserializePayload`. |
| `messaging/EventDispatcher.java` | Add `case COPY` branch translating `CopyPayload.newNodes` to `CachedNode`s and calling `cache.applyCopy`. |
| `cache/TreeCache.java` | Add `void applyCopy(List<CachedNode> newNodes)` to the interface. |
| `cache/DefaultTreeCache.java` | Implement `applyCopy` under a single write lock. |
| `persistence/ItemTreeRepository.java` | Add `findRowsForCopy(long rootId, int limit)`, `insertBatch(List<ItemTreeFullRow>)`, `allocateIds(int n)`. |
| `persistence/JdbcItemTreeRepository.java` | Implement the three new methods (BFS via existing pattern; `JdbcTemplate.batchUpdate`; `CONNECT BY LEVEL` for id allocation). |
| `service/ItemService.java` | Add `copyItem(long sourceId, long destinationFolderId, UserContext)`; inject `CopyProperties`. |
| `api/controller/ItemController.java` | Implement `copyItem` generated interface method. |
| `src/main/resources/openapi/itemtree-api.yaml` | New `POST /items/{id}/copy` endpoint + `CopyItemRequest` schema + `PayloadTooLarge` shared response. |
| `src/main/resources/application.yml` | Add `itemtree.copy.max-nodes: 100` (optional — `@DefaultValue` covers it, but the line documents the knob). |

**New test files:**

| File | Coverage |
|---|---|
| `src/test/java/com/myxcomp/ice/xtree/config/CopyPropertiesTest.java` | Default applied; `< 1` rejected at startup. |

**Modified test files:**

| File | Coverage added |
|---|---|
| `cache/DefaultTreeCacheTest.java` | New `ApplyCopy` nested class (happy, idempotency, tolerance, atomicity, null guards); writer rotation in `Concurrency` extended. |
| `messaging/event/TreeMutationEventTest.java` | COPY envelope round-trip + unknown-field forward-compat. |
| `messaging/EventDispatcherTest.java` | COPY dispatch + ClassCastException on wrong payload. |
| `messaging/EventConsumerServiceTest.java` | COPY round-trip + self-echo drop on COPY. |
| `service/ItemServiceTest.java` | New `CopyItem` nested class (all 8 validations, happy paths, suffix algorithm, verbatim payload, BFS-ordered apply, event publish, publisher exception swallowed). |
| `persistence/JdbcItemTreeRepositoryIT.java` | `findRowsForCopy` (BFS order, `limit = cap+1` short-circuit, unknown id, IN-list chunking), `insertBatch` (happy + rollback), `allocateIds` (uniqueness + monotonicity). |
| `api/controller/ItemControllerTest.java` | New `CopyItem` nested class (201 happy + each of 8 errors → status + errorCode). |
| `messaging/MessagingLoopbackIT.java` | Copy round-trip: originator → peer cache convergence. |
| `e2e/ItemTreeApplicationE2EIT.java` | `copyPropagatesAcrossInstances` (subtree convergence on the in-memory bus). |
| `actuator/ObservabilityExposureIT.java` (or current `Phase 12 IT` location) | Assert `itemtree_copy_requests_total`, `itemtree_copy_rejected_total`, `itemtree_copy_subtree_size_count` present after workload. |

**Generated code (do NOT hand-edit):** the openapi-generator plugin regenerates `com.myxcomp.ice.xtree.generated.api.ItemsApi` and the `CopyItemRequest` DTO from the yaml. Re-run `./gradlew build` after the yaml edit; the controller will then compile against the new interface method.

---

## Conventions reminder

- TimeMapper is the sole `now()` source — use `timeMapper.now()`, never `Instant.now()`.
- `LASTUPDATE` / `LASTUPDATEUSER` go through `TimeMapper.toLocalDateTime`/`toInstant` at every JDBC boundary; row mappers stay consistent.
- All cache mutations log + skip on missing references (apply* tolerance, design §4) — applies to `applyCopy` too.
- Constants/SQL column names referenced ≥ 2 times → `private static final String`.
- Multiple same-shape tests differing only by input → `@ParameterizedTest` + `@MethodSource`.
- AssertJ semantic assertions (`isEmpty()`, `isZero()`, `isNotNull()`).
- Defensive copies on cache read; never expose live internal collections.
- `parentId` is `Long`, never null; root parent is `0` (`TreeConstants.ROOT_PARENT_ID`); root id is `1` (`TreeConstants.ROOT_ID`).

---

## Tasks

### Task 1: Error codes

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/service/exception/ErrorCode.java`
- Test: `src/test/java/com/myxcomp/ice/xtree/service/exception/ErrorCodeTest.java` (create if missing; otherwise extend)

- [ ] **Step 1: Write the failing test**

Create or extend `src/test/java/com/myxcomp/ice/xtree/service/exception/ErrorCodeTest.java`:
```java
package com.myxcomp.ice.xtree.service.exception;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class ErrorCodeTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "CANNOT_COPY_ROOT",
        "DESTINATION_NOT_FOUND",
        "DESTINATION_NOT_FOLDER",
        "DESTINATION_NOT_IN_USER_FOLDER",
        "COPY_INTO_DESCENDANT",
        "COPY_TOO_LARGE"
    })
    void copyErrorCodesExist(String name) {
        assertThatCode(() -> ErrorCode.valueOf(name)).doesNotThrowAnyException();
    }

    @Test
    void preExistingCodesStillPresent() {
        assertThat(ErrorCode.ITEM_NOT_FOUND).isNotNull();
        assertThat(ErrorCode.HOME_FOLDER_NOT_FOUND).isNotNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.myxcomp.ice.xtree.service.exception.ErrorCodeTest`
Expected: FAIL — `IllegalArgumentException: No enum constant ErrorCode.CANNOT_COPY_ROOT`.

- [ ] **Step 3: Add the new enum values**

Modify `src/main/java/com/myxcomp/ice/xtree/service/exception/ErrorCode.java`:
```java
package com.myxcomp.ice.xtree.service.exception;

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
    HOME_FOLDER_NOT_FOUND,
    INVALID_SEARCH_PARAMS,
    DATA_NOT_SERIALISABLE,
    CANNOT_COPY_ROOT,
    DESTINATION_NOT_FOUND,
    DESTINATION_NOT_FOLDER,
    DESTINATION_NOT_IN_USER_FOLDER,
    COPY_INTO_DESCENDANT,
    COPY_TOO_LARGE
}
```

- [ ] **Step 4: Run test to verify pass**

Run: `./gradlew test --tests com.myxcomp.ice.xtree.service.exception.ErrorCodeTest`
Expected: PASS, 7 tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/service/exception/ErrorCode.java \
        src/test/java/com/myxcomp/ice/xtree/service/exception/ErrorCodeTest.java
git commit -m "feat(copy): add 6 new ErrorCode values for copy operation"
```

---

### Task 2: `CopyTooLargeException` + HTTP 413 handler

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/service/exception/CopyTooLargeException.java`
- Modify: `src/main/java/com/myxcomp/ice/xtree/api/advice/GlobalExceptionHandler.java`
- Test: extend `src/test/java/com/myxcomp/ice/xtree/api/advice/GlobalExceptionHandlerTest.java`

- [ ] **Step 1: Write the failing test**

Add to `GlobalExceptionHandlerTest.java`:
```java
@Test
void copyTooLargeMapsTo413() {
    CopyTooLargeException ex = new CopyTooLargeException(
            "Source subtree has 250 nodes, cap is 100");

    ResponseEntity<Problem> resp = handler.handleCopyTooLarge(ex);

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    assertThat(resp.getBody()).isNotNull();
    assertThat(resp.getBody().getStatus()).isEqualTo(413);
    assertThat(resp.getBody().getErrorCode()).isEqualTo(ErrorCode.COPY_TOO_LARGE.name());
    assertThat(resp.getBody().getDetail()).contains("250 nodes");
}
```

(Adjust imports at the top of the test file to include `CopyTooLargeException`, `ErrorCode`, and `HttpStatus` if not already present.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.myxcomp.ice.xtree.api.advice.GlobalExceptionHandlerTest`
Expected: FAIL — class `CopyTooLargeException` does not exist; method `handleCopyTooLarge` does not exist.

- [ ] **Step 3: Create the exception class**

`src/main/java/com/myxcomp/ice/xtree/service/exception/CopyTooLargeException.java`:
```java
package com.myxcomp.ice.xtree.service.exception;

/** Maps to HTTP 413 in the HTTP layer. */
public class CopyTooLargeException extends ItemTreeException {
    public CopyTooLargeException(String message) {
        super(ErrorCode.COPY_TOO_LARGE, message);
    }
}
```

- [ ] **Step 4: Add the handler**

Modify `GlobalExceptionHandler.java` — add this method (and import the exception):
```java
@ExceptionHandler(CopyTooLargeException.class)
public ResponseEntity<Problem> handleCopyTooLarge(CopyTooLargeException e) {
    return problemFactory.build(HttpStatus.PAYLOAD_TOO_LARGE, e.errorCode(), e.getMessage());
}
```

- [ ] **Step 5: Run test to verify pass**

Run: `./gradlew test --tests com.myxcomp.ice.xtree.api.advice.GlobalExceptionHandlerTest`
Expected: PASS (all pre-existing tests still green; new one green).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/service/exception/CopyTooLargeException.java \
        src/main/java/com/myxcomp/ice/xtree/api/advice/GlobalExceptionHandler.java \
        src/test/java/com/myxcomp/ice/xtree/api/advice/GlobalExceptionHandlerTest.java
git commit -m "feat(copy): map CopyTooLargeException to HTTP 413"
```

---

### Task 3: `CopyProperties` configuration

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/config/CopyProperties.java`
- Create: `src/test/java/com/myxcomp/ice/xtree/config/CopyPropertiesTest.java`
- Modify: `src/main/resources/application.yml` (documentation)

- [ ] **Step 1: Write the failing test**

`src/test/java/com/myxcomp/ice/xtree/config/CopyPropertiesTest.java`:
```java
package com.myxcomp.ice.xtree.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class CopyPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EnableProps.class);

    @Test
    void defaultMaxNodesIs100() {
        runner.run(ctx -> assertThat(ctx.getBean(CopyProperties.class).maxNodes()).isEqualTo(100));
    }

    @Test
    void explicitValueOverridesDefault() {
        runner.withPropertyValues("itemtree.copy.max-nodes=250")
              .run(ctx -> assertThat(ctx.getBean(CopyProperties.class).maxNodes()).isEqualTo(250));
    }

    @Test
    void zeroIsRejectedAtStartup() {
        runner.withPropertyValues("itemtree.copy.max-nodes=0")
              .run(ctx -> assertThat(ctx).hasFailed()
                      .getFailure()
                      .hasMessageContaining("itemtree.copy.max-nodes must be >= 1"));
    }

    @Test
    void negativeIsRejectedAtStartup() {
        runner.withPropertyValues("itemtree.copy.max-nodes=-5")
              .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Configuration
    @EnableConfigurationProperties(CopyProperties.class)
    static class EnableProps {}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.myxcomp.ice.xtree.config.CopyPropertiesTest`
Expected: FAIL — class `CopyProperties` does not exist.

- [ ] **Step 3: Write minimal implementation**

`src/main/java/com/myxcomp/ice/xtree/config/CopyProperties.java`:
```java
package com.myxcomp.ice.xtree.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for the copy-item operation (design §2.4, §4.4).
 *
 * @param maxNodes hard cap on subtree size per copy request; requests exceeding
 *                 this fail with {@code COPY_TOO_LARGE} (HTTP 413)
 */
@ConfigurationProperties("itemtree.copy")
public record CopyProperties(@DefaultValue("100") int maxNodes) {

    @PostConstruct
    void validate() {
        if (maxNodes < 1) {
            throw new IllegalStateException(
                    "itemtree.copy.max-nodes must be >= 1, got " + maxNodes);
        }
    }
}
```

- [ ] **Step 4: Register the properties class on the main application**

Open the existing `@Configuration` class (likely `ItemTreeApplication` or a dedicated `PropertiesConfig` — check `git grep -n "@EnableConfigurationProperties" src/main/java` to find the canonical spot) and add `CopyProperties.class` to its `@EnableConfigurationProperties({ ..., CopyProperties.class })`. If no such grouping exists yet, register on `ItemTreeApplication`:
```java
@EnableConfigurationProperties(CopyProperties.class)
```
Match whatever the codebase already does for `DataProperties` (Phase 5).

- [ ] **Step 5: Document in `application.yml` (optional but recommended)**

Add under the existing `itemtree:` root in `src/main/resources/application.yml`:
```yaml
itemtree:
  copy:
    max-nodes: 100      # hard cap on subtree size per copy request (default 100)
```
If `itemtree:` already exists, append `copy:` as a sibling of the existing children.

- [ ] **Step 6: Run test to verify pass**

Run: `./gradlew test --tests com.myxcomp.ice.xtree.config.CopyPropertiesTest`
Expected: PASS, 4 tests green.

- [ ] **Step 7: Run full build to verify no regressions**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/config/CopyProperties.java \
        src/test/java/com/myxcomp/ice/xtree/config/CopyPropertiesTest.java \
        src/main/resources/application.yml \
        src/main/java/com/myxcomp/ice/xtree/ItemTreeApplication.java
git commit -m "feat(copy): add CopyProperties (itemtree.copy.max-nodes, default 100)"
```
(Adjust the final path if the `@EnableConfigurationProperties` lives elsewhere.)

---

### Task 4: `ItemTreeFullRow` row record

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/persistence/ItemTreeFullRow.java`
- Create: `src/test/java/com/myxcomp/ice/xtree/persistence/ItemTreeFullRowTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/com/myxcomp/ice/xtree/persistence/ItemTreeFullRowTest.java`:
```java
package com.myxcomp.ice.xtree.persistence;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class ItemTreeFullRowTest {

    @Test
    void carriesAllFields() {
        Instant t = Instant.parse("2026-05-24T10:00:00Z");
        ItemTreeFullRow row = new ItemTreeFullRow(
                42L, 1L, "Report A", "Report",
                "{\"k\":1}", "<r><k>1</k></r>", t, "alice");

        assertThat(row.itemTreeId()).isEqualTo(42L);
        assertThat(row.parentId()).isEqualTo(1L);
        assertThat(row.name()).isEqualTo("Report A");
        assertThat(row.type()).isEqualTo("Report");
        assertThat(row.json()).isEqualTo("{\"k\":1}");
        assertThat(row.xml()).isEqualTo("<r><k>1</k></r>");
        assertThat(row.lastUpdate()).isEqualTo(t);
        assertThat(row.lastUpdateUser()).isEqualTo("alice");
    }

    @Test
    void nullJsonAndXmlAllowed() {
        ItemTreeFullRow row = new ItemTreeFullRow(
                42L, 1L, "F", "Folder", null, null,
                Instant.parse("2026-05-24T10:00:00Z"), "alice");
        assertThat(row.json()).isNull();
        assertThat(row.xml()).isNull();
    }

    @Test
    void parentIdMustNotBeNull() {
        assertThatNullPointerException().isThrownBy(() ->
                new ItemTreeFullRow(42L, null, "F", "Folder", null, null,
                        Instant.parse("2026-05-24T10:00:00Z"), "alice"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.myxcomp.ice.xtree.persistence.ItemTreeFullRowTest`
Expected: FAIL — class `ItemTreeFullRow` does not exist.

- [ ] **Step 3: Write the record**

`src/main/java/com/myxcomp/ice/xtree/persistence/ItemTreeFullRow.java`:
```java
package com.myxcomp.ice.xtree.persistence;

import java.time.Instant;
import java.util.Objects;

/**
 * Combined structural + payload row used by the copy operation.
 *
 * <p>{@code parentId} is required (never null; {@code 0} for the root); {@code json} and
 * {@code xml} are nullable. Stored timestamps are UTC {@code Instant}s (design §14).
 */
public record ItemTreeFullRow(
        long itemTreeId,
        Long parentId,
        String name,
        String type,
        String json,
        String xml,
        Instant lastUpdate,
        String lastUpdateUser
) {
    public ItemTreeFullRow {
        Objects.requireNonNull(parentId, "parentId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(lastUpdate, "lastUpdate");
        Objects.requireNonNull(lastUpdateUser, "lastUpdateUser");
    }
}
```

- [ ] **Step 4: Run test to verify pass**

Run: `./gradlew test --tests com.myxcomp.ice.xtree.persistence.ItemTreeFullRowTest`
Expected: PASS, 3 tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/persistence/ItemTreeFullRow.java \
        src/test/java/com/myxcomp/ice/xtree/persistence/ItemTreeFullRowTest.java
git commit -m "feat(copy): add ItemTreeFullRow record"
```

---

### Task 5: `ItemTreeRepository.allocateIds(int n)`

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/persistence/ItemTreeRepository.java`
- Modify: `src/main/java/com/myxcomp/ice/xtree/persistence/JdbcItemTreeRepository.java`
- Modify: `src/test/java/com/myxcomp/ice/xtree/persistence/JdbcItemTreeRepositoryIT.java`

- [ ] **Step 1: Write the failing test**

Add to `JdbcItemTreeRepositoryIT.java` (within an appropriate nested class, e.g. a new `@Nested class AllocateIds` block):
```java
@Test
void allocateIdsReturnsNUniqueStrictlyIncreasingIds() {
    List<Long> ids = repository.allocateIds(10);

    assertThat(ids).hasSize(10);
    assertThat(ids).doesNotHaveDuplicates();
    for (int i = 1; i < ids.size(); i++) {
        assertThat(ids.get(i)).isGreaterThan(ids.get(i - 1));
    }
}

@Test
void allocateIdsWithOneReturnsSingleId() {
    List<Long> ids = repository.allocateIds(1);
    assertThat(ids).hasSize(1);
}

@Test
void allocateIdsWithZeroReturnsEmpty() {
    assertThat(repository.allocateIds(0)).isEmpty();
}

@Test
void allocateIdsRejectsNegative() {
    assertThatIllegalArgumentException().isThrownBy(() -> repository.allocateIds(-1));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.myxcomp.ice.xtree.persistence.JdbcItemTreeRepositoryIT`
Expected: FAIL — method `allocateIds` does not exist.

- [ ] **Step 3: Add the interface method**

Add to `ItemTreeRepository.java`:
```java
/**
 * Allocates {@code n} fresh ids from {@code ITEMTREE_ID_SQN} in one round-trip
 * (Oracle/H2 hierarchical {@code CONNECT BY} query). Returns an empty list when
 * {@code n == 0}; throws {@link IllegalArgumentException} for negative {@code n}.
 */
List<Long> allocateIds(int n);
```

- [ ] **Step 4: Implement in `JdbcItemTreeRepository`**

```java
@Override
public List<Long> allocateIds(int n) {
    if (n < 0) {
        throw new IllegalArgumentException("n must be >= 0, got " + n);
    }
    if (n == 0) {
        return List.of();
    }
    // Oracle hierarchical query; H2 in Oracle compat mode supports CONNECT BY since 2.x.
    String sql = "SELECT ITEMTREE_ID_SQN.NEXTVAL AS ID FROM DUAL CONNECT BY LEVEL <= :n";
    return jdbcClient.sql(sql)
            .param("n", n)
            .query(Long.class)
            .list();
}
```
(If `jdbcClient` is a field name different from what's in scope, match the existing convention used by `findStructuralChangedSince`.)

- [ ] **Step 5: Run test to verify pass**

Run: `./gradlew test --tests com.myxcomp.ice.xtree.persistence.JdbcItemTreeRepositoryIT`
Expected: PASS — all 4 new tests green.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/persistence/ItemTreeRepository.java \
        src/main/java/com/myxcomp/ice/xtree/persistence/JdbcItemTreeRepository.java \
        src/test/java/com/myxcomp/ice/xtree/persistence/JdbcItemTreeRepositoryIT.java
git commit -m "feat(copy): add ItemTreeRepository.allocateIds(n)"
```

---

### Task 6: `ItemTreeRepository.findRowsForCopy(rootId, limit)`

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/persistence/ItemTreeRepository.java`
- Modify: `src/main/java/com/myxcomp/ice/xtree/persistence/JdbcItemTreeRepository.java`
- Create: `src/main/java/com/myxcomp/ice/xtree/persistence/rowmapper/ItemTreeFullRowMapper.java`
- Modify: `src/test/java/com/myxcomp/ice/xtree/persistence/JdbcItemTreeRepositoryIT.java`

- [ ] **Step 1: Write the failing tests**

Add to `JdbcItemTreeRepositoryIT.java` (new `@Nested class FindRowsForCopy` block):
```java
@Nested
class FindRowsForCopy {

    @Test
    void unknownRootReturnsEmpty() {
        assertThat(repository.findRowsForCopy(999_999L, 1000)).isEmpty();
    }

    @Test
    void singleLeafReturnsOneRow() {
        // pick a known leaf id from seed data — e.g. 25 (leafItem)
        List<ItemTreeFullRow> rows = repository.findRowsForCopy(25L, 1000);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).itemTreeId()).isEqualTo(25L);
    }

    @Test
    void folderSubtreeIsBfsOrdered() {
        // pick a known folder root from seed data (e.g. 12 = deepuser home, or any folder with descendants)
        List<ItemTreeFullRow> rows = repository.findRowsForCopy(12L, 1000);

        assertThat(rows).isNotEmpty();
        // every non-root row must appear after its parent in the list
        Set<Long> seen = new HashSet<>();
        seen.add(rows.get(0).itemTreeId());
        for (int i = 1; i < rows.size(); i++) {
            ItemTreeFullRow row = rows.get(i);
            assertThat(seen)
                    .as("row %d parent %d must appear before child %d",
                            i, row.parentId(), row.itemTreeId())
                    .contains(row.parentId());
            seen.add(row.itemTreeId());
        }
    }

    @Test
    void limitShortCircuitsAtLimitPlusOne() {
        // synthesize a wide subtree on the fly: insert a folder with 200 children
        Instant t = Instant.parse("2026-05-24T10:00:00Z");
        long wideFolder = repository.insert(1L, "wide-for-copy", "Folder",
                null, null, t, "test");
        try {
            for (int i = 0; i < 200; i++) {
                repository.insert(wideFolder, "child-" + i, "Folder",
                        null, null, t, "test");
            }

            List<ItemTreeFullRow> rows = repository.findRowsForCopy(wideFolder, 50);
            assertThat(rows).hasSize(50);
        } finally {
            repository.cascadeDeleteSubtree(wideFolder);
        }
    }

    @Test
    void payloadColumnsAreCopiedThrough() {
        Instant t = Instant.parse("2026-05-24T10:00:00Z");
        long folder = repository.insert(1L, "copy-payload-folder", "Folder",
                null, null, t, "test");
        try {
            long withJson = repository.insert(folder, "with-json", "Report",
                    "{\"a\":1}", "<r><a>1</a></r>", t, "test");

            List<ItemTreeFullRow> rows = repository.findRowsForCopy(withJson, 10);
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).json()).isEqualTo("{\"a\":1}");
            assertThat(rows.get(0).xml()).isEqualTo("<r><a>1</a></r>");
        } finally {
            repository.cascadeDeleteSubtree(folder);
        }
    }
}
```
Add imports at the top of the file if missing: `org.junit.jupiter.api.Nested`, `java.util.HashSet`, `java.util.Set`, `java.time.Instant`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.myxcomp.ice.xtree.persistence.JdbcItemTreeRepositoryIT$FindRowsForCopy"`
Expected: FAIL — method `findRowsForCopy` does not exist.

- [ ] **Step 3: Create the row mapper**

`src/main/java/com/myxcomp/ice/xtree/persistence/rowmapper/ItemTreeFullRowMapper.java`:
```java
package com.myxcomp.ice.xtree.persistence.rowmapper;

import com.myxcomp.ice.xtree.common.TimeMapper;
import com.myxcomp.ice.xtree.persistence.ItemTreeFullRow;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;

public class ItemTreeFullRowMapper implements RowMapper<ItemTreeFullRow> {

    private final TimeMapper timeMapper;

    public ItemTreeFullRowMapper(TimeMapper timeMapper) {
        this.timeMapper = timeMapper;
    }

    @Override
    public ItemTreeFullRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        long id          = rs.getLong("ITEMTREEID");
        Long parentId    = rs.getObject("PARENTID", Long.class);
        String name      = rs.getString("NAME");
        String type      = rs.getString("TYPE");
        String json      = rs.getString("JSON");
        String xml       = rs.getString("XML");
        LocalDateTime ts = rs.getObject("LASTUPDATE", LocalDateTime.class);
        String user      = rs.getString("LASTUPDATEUSER");
        return new ItemTreeFullRow(id, parentId, name, type, json, xml,
                timeMapper.toInstant(ts), user);
    }
}
```

- [ ] **Step 4: Add the interface method**

Add to `ItemTreeRepository.java`:
```java
/**
 * BFS read of {@code rootId} and all descendants — structural + payload columns
 * in one combined row. Stops once {@code limit} rows have been collected; caller
 * passes {@code cap + 1} to detect cap violations without traversing huge subtrees.
 *
 * @return list ordered such that every non-root node appears after its parent;
 *         empty if {@code rootId} does not exist
 */
List<ItemTreeFullRow> findRowsForCopy(long rootId, int limit);
```

- [ ] **Step 5: Implement BFS in `JdbcItemTreeRepository`**

Mirror the existing `cascadeDeleteSubtree` BFS pattern (H2 2.x cannot resolve a recursive CTE self-reference in a `PreparedStatement`, per `feedback-h2-recursive-cte`). Add at the top of the file:
```java
private static final int IN_LIST_CHUNK_SIZE = 1000;
private static final String SQL_FIND_ROWS_FOR_COPY_BY_IDS =
        "SELECT ITEMTREEID, PARENTID, NAME, TYPE, JSON, XML, LASTUPDATE, LASTUPDATEUSER " +
        "FROM ITEMTREE WHERE ITEMTREEID IN (:ids)";
private static final String SQL_FIND_CHILD_IDS_BY_PARENTS =
        "SELECT ITEMTREEID FROM ITEMTREE WHERE PARENTID IN (:parentIds)";
```
(If the IN-list constant already exists, reuse it.)

Method body:
```java
@Override
@Transactional(readOnly = true)
public List<ItemTreeFullRow> findRowsForCopy(long rootId, int limit) {
    if (limit <= 0) {
        return List.of();
    }
    ItemTreeFullRowMapper mapper = new ItemTreeFullRowMapper(timeMapper);

    List<Long> frontier = new ArrayList<>();
    frontier.add(rootId);
    List<ItemTreeFullRow> collected = new ArrayList<>();

    while (!frontier.isEmpty() && collected.size() < limit) {
        // chunk current frontier
        List<List<Long>> chunks = chunk(frontier, IN_LIST_CHUNK_SIZE);
        // capture all rows for the chunk, preserving frontier order
        Map<Long, ItemTreeFullRow> byId = new HashMap<>();
        List<Long> nextFrontier = new ArrayList<>();
        for (List<Long> chunkIds : chunks) {
            List<ItemTreeFullRow> rows = jdbcClient.sql(SQL_FIND_ROWS_FOR_COPY_BY_IDS)
                    .param("ids", chunkIds)
                    .query(mapper)
                    .list();
            for (ItemTreeFullRow row : rows) byId.put(row.itemTreeId(), row);

            List<Long> childIds = jdbcClient.sql(SQL_FIND_CHILD_IDS_BY_PARENTS)
                    .param("parentIds", chunkIds)
                    .query(Long.class)
                    .list();
            nextFrontier.addAll(childIds);
        }
        // emit in frontier order so BFS order is preserved
        for (Long id : frontier) {
            if (collected.size() >= limit) break;
            ItemTreeFullRow row = byId.get(id);
            if (row != null) collected.add(row);
        }
        frontier = nextFrontier;
    }
    return collected;
}

private static <T> List<List<T>> chunk(List<T> input, int size) {
    List<List<T>> out = new ArrayList<>();
    for (int i = 0; i < input.size(); i += size) {
        out.add(input.subList(i, Math.min(input.size(), i + size)));
    }
    return out;
}
```
Add imports: `java.util.ArrayList`, `java.util.HashMap`, `java.util.Map`, `org.springframework.transaction.annotation.Transactional` (probably already present).

- [ ] **Step 6: Run tests to verify pass**

Run: `./gradlew test --tests "com.myxcomp.ice.xtree.persistence.JdbcItemTreeRepositoryIT$FindRowsForCopy"`
Expected: PASS — 5 tests green.

- [ ] **Step 7: Run full repo IT suite (no regressions)**

Run: `./gradlew test --tests com.myxcomp.ice.xtree.persistence.JdbcItemTreeRepositoryIT`
Expected: PASS — all repo tests green.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/persistence/ItemTreeRepository.java \
        src/main/java/com/myxcomp/ice/xtree/persistence/JdbcItemTreeRepository.java \
        src/main/java/com/myxcomp/ice/xtree/persistence/rowmapper/ItemTreeFullRowMapper.java \
        src/test/java/com/myxcomp/ice/xtree/persistence/JdbcItemTreeRepositoryIT.java
git commit -m "feat(copy): add ItemTreeRepository.findRowsForCopy (BFS + limit short-circuit)"
```

---

### Task 7: `ItemTreeRepository.insertBatch(rows)`

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/persistence/ItemTreeRepository.java`
- Modify: `src/main/java/com/myxcomp/ice/xtree/persistence/JdbcItemTreeRepository.java`
- Modify: `src/test/java/com/myxcomp/ice/xtree/persistence/JdbcItemTreeRepositoryIT.java`

- [ ] **Step 1: Write the failing tests**

Add to `JdbcItemTreeRepositoryIT.java`:
```java
@Nested
class InsertBatch {

    @Test
    void insertsAllRowsInOrder() {
        Instant t = Instant.parse("2026-05-24T10:00:00Z");
        List<Long> ids = repository.allocateIds(3);

        List<ItemTreeFullRow> rows = List.of(
                new ItemTreeFullRow(ids.get(0), 1L, "batch-root", "Folder",
                        null, null, t, "test"),
                new ItemTreeFullRow(ids.get(1), ids.get(0), "batch-child", "Folder",
                        null, null, t, "test"),
                new ItemTreeFullRow(ids.get(2), ids.get(1), "batch-leaf", "Report",
                        "{\"x\":1}", null, t, "test")
        );
        try {
            repository.insertBatch(rows);

            List<ItemTreeFullRow> read = repository.findRowsForCopy(ids.get(0), 100);
            assertThat(read).extracting(ItemTreeFullRow::itemTreeId)
                    .containsExactlyInAnyOrderElementsOf(ids);
            assertThat(read).filteredOn(r -> r.itemTreeId() == ids.get(2))
                    .singleElement()
                    .satisfies(r -> assertThat(r.json()).isEqualTo("{\"x\":1}"));
        } finally {
            repository.cascadeDeleteSubtree(ids.get(0));
        }
    }

    @Test
    void emptyListIsNoOp() {
        // Should not throw and should not mutate the table.
        int before = repository.findRowsForCopy(1L, Integer.MAX_VALUE).size();
        repository.insertBatch(List.of());
        int after = repository.findRowsForCopy(1L, Integer.MAX_VALUE).size();
        assertThat(after).isEqualTo(before);
    }

    @Test
    void rollsBackOnDuplicateId() {
        Instant t = Instant.parse("2026-05-24T10:00:00Z");
        long sharedId = repository.allocateIds(1).get(0);
        List<ItemTreeFullRow> rows = List.of(
                new ItemTreeFullRow(sharedId, 1L, "dup-1", "Folder", null, null, t, "test"),
                new ItemTreeFullRow(sharedId, 1L, "dup-2", "Folder", null, null, t, "test")
        );
        assertThatThrownBy(() -> repository.insertBatch(rows))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        // verify neither row was committed (PK violation should roll back the batch)
        assertThat(repository.findRowsForCopy(sharedId, 10)).isEmpty();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.myxcomp.ice.xtree.persistence.JdbcItemTreeRepositoryIT$InsertBatch"`
Expected: FAIL — method `insertBatch` does not exist.

- [ ] **Step 3: Add the interface method**

Add to `ItemTreeRepository.java`:
```java
/**
 * Inserts N rows in a single batched JDBC statement. Ids and parentIds are
 * supplied by the caller (no SEQUENCE.NEXTVAL inside the INSERT). Empty input
 * is a no-op. All-or-nothing: a constraint violation rolls back the batch.
 */
void insertBatch(List<ItemTreeFullRow> rows);
```

- [ ] **Step 4: Implement in `JdbcItemTreeRepository`**

Add SQL constant and method:
```java
private static final String SQL_INSERT_BATCH =
        "INSERT INTO ITEMTREE " +
        "  (ITEMTREEID, PARENTID, NAME, TYPE, JSON, XML, LASTUPDATE, LASTUPDATEUSER) " +
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

@Override
@Transactional
public void insertBatch(List<ItemTreeFullRow> rows) {
    Objects.requireNonNull(rows, "rows");
    if (rows.isEmpty()) return;

    jdbcTemplate.batchUpdate(SQL_INSERT_BATCH, new BatchPreparedStatementSetter() {
        @Override
        public void setValues(PreparedStatement ps, int i) throws SQLException {
            ItemTreeFullRow row = rows.get(i);
            ps.setLong(1, row.itemTreeId());
            ps.setLong(2, row.parentId());
            ps.setString(3, row.name());
            ps.setString(4, row.type());
            if (row.json() != null) ps.setString(5, row.json()); else ps.setNull(5, Types.CLOB);
            if (row.xml()  != null) ps.setString(6, row.xml());  else ps.setNull(6, Types.CLOB);
            ps.setObject(7, timeMapper.toLocalDateTime(row.lastUpdate()));
            ps.setString(8, row.lastUpdateUser());
        }

        @Override
        public int getBatchSize() {
            return rows.size();
        }
    });
}
```
Add imports: `java.sql.PreparedStatement`, `java.sql.SQLException`, `java.sql.Types`, `org.springframework.jdbc.core.BatchPreparedStatementSetter`. `jdbcTemplate` should already be a field — `streamAllStructural` uses it; if not, inject `JdbcTemplate` the same way that method does.

- [ ] **Step 5: Run tests to verify pass**

Run: `./gradlew test --tests "com.myxcomp.ice.xtree.persistence.JdbcItemTreeRepositoryIT$InsertBatch"`
Expected: PASS — 3 tests green.

- [ ] **Step 6: Full repo IT regression check**

Run: `./gradlew test --tests com.myxcomp.ice.xtree.persistence.JdbcItemTreeRepositoryIT`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/persistence/ItemTreeRepository.java \
        src/main/java/com/myxcomp/ice/xtree/persistence/JdbcItemTreeRepository.java \
        src/test/java/com/myxcomp/ice/xtree/persistence/JdbcItemTreeRepositoryIT.java
git commit -m "feat(copy): add ItemTreeRepository.insertBatch (batched JDBC)"
```

---

### Task 8: `TreeCache.applyCopy` + `DefaultTreeCache` implementation

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/cache/TreeCache.java`
- Modify: `src/main/java/com/myxcomp/ice/xtree/cache/DefaultTreeCache.java`
- Modify: `src/test/java/com/myxcomp/ice/xtree/cache/DefaultTreeCacheTest.java`

- [ ] **Step 1: Write the failing tests**

Add to `DefaultTreeCacheTest.java` (new `@Nested class ApplyCopy`):
```java
@Nested
class ApplyCopy {

    private final Instant T = Instant.parse("2026-05-24T10:00:00Z");

    @Test
    void appliesAllNodesInOrder() {
        cache.applyCreate(folder(10L, 0L, "dest"));
        List<CachedNode> batch = List.of(
                folder(100L, 10L, "root-copy"),
                folder(101L, 100L, "child1"),
                node(102L, 101L, "leaf", "Report")
        );
        cache.applyCopy(batch);

        assertThat(cache.getById(100L)).isPresent();
        assertThat(cache.getById(101L)).isPresent();
        assertThat(cache.getById(102L)).isPresent();
        assertThat(cache.getChildren(10L))
                .extracting(CachedNode::itemTreeId)
                .contains(100L);
        assertThat(cache.getChildren(100L))
                .extracting(CachedNode::itemTreeId)
                .contains(101L);
        assertThat(cache.getChildren(101L))
                .extracting(CachedNode::itemTreeId)
                .contains(102L);
    }

    @Test
    void emptyListIsNoOp() {
        int before = cache.size();
        cache.applyCopy(List.of());
        assertThat(cache.size()).isEqualTo(before);
    }

    @Test
    void idempotentReapplyOverwritesWithSameValues() {
        cache.applyCreate(folder(10L, 0L, "dest"));
        List<CachedNode> batch = List.of(folder(200L, 10L, "x"));
        cache.applyCopy(batch);
        cache.applyCopy(batch);
        assertThat(cache.getById(200L)).get().extracting(CachedNode::name).isEqualTo("x");
    }

    @Test
    void toleratesOrphanParent() {
        // root copy points at a parent that doesn't exist in the cache
        cache.applyCopy(List.of(folder(300L, 99_999L, "orphan-root")));
        assertThat(cache.getById(300L)).isPresent();
    }

    @Test
    void foldersAreIndexedByName() {
        cache.applyCreate(folder(10L, 0L, "dest"));
        cache.applyCopy(List.of(folder(400L, 10L, "report-folder")));
        assertThat(cache.findHomeFolder("report-folder"))
                .get()
                .extracting(CachedNode::itemTreeId)
                .isEqualTo(400L);
    }

    @Test
    void rejectsNullList() {
        assertThatNullPointerException().isThrownBy(() -> cache.applyCopy(null));
    }

    @Test
    void rejectsNullElement() {
        cache.applyCreate(folder(10L, 0L, "dest"));
        List<CachedNode> batch = new ArrayList<>();
        batch.add(folder(500L, 10L, "ok"));
        batch.add(null);
        assertThatNullPointerException().isThrownBy(() -> cache.applyCopy(batch));
    }

    private CachedNode folder(long id, long parentId, String name) {
        return new CachedNode(id, parentId, name, "Folder", T, "alice");
    }

    private CachedNode node(long id, long parentId, String name, String type) {
        return new CachedNode(id, parentId, name, type, T, "alice");
    }
}
```
Imports to add if missing: `org.junit.jupiter.api.Nested`, `java.time.Instant`, `java.util.ArrayList`, `assertThatNullPointerException`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.myxcomp.ice.xtree.cache.DefaultTreeCacheTest$ApplyCopy"`
Expected: FAIL — method `applyCopy` does not exist.

- [ ] **Step 3: Add to interface**

Append to `TreeCache.java`:
```java
/**
 * Applies a BFS-ordered batch of newly created nodes — a copied subtree — under
 * a single write lock. Idempotent and tolerant per the {@link #applyCreate}
 * contract (design §4): id-already-present is upserted; missing parent
 * references are still recorded (apply* tolerance). Atomic from any concurrent
 * reader's view.
 *
 * @param newNodes BFS-ordered list, never null, may be empty; elements never null
 */
void applyCopy(List<CachedNode> newNodes);
```

- [ ] **Step 4: Implement in `DefaultTreeCache`**

```java
@Override
public void applyCopy(List<CachedNode> newNodes) {
    Objects.requireNonNull(newNodes, "newNodes");
    if (newNodes.isEmpty()) return;
    for (CachedNode n : newNodes) {
        Objects.requireNonNull(n, "newNodes element");
    }
    lock.writeLock().lock();
    try {
        for (CachedNode n : newNodes) {
            byId.put(n.itemTreeId(), n);
            childrenByParent
                    .computeIfAbsent(n.parentId(), k -> ConcurrentHashMap.newKeySet())
                    .add(n.itemTreeId());
            if (Types.isFolder(n.type())) {
                foldersByName
                        .computeIfAbsent(n.name(), k -> ConcurrentHashMap.newKeySet())
                        .add(n.itemTreeId());
            }
        }
    } finally {
        lock.writeLock().unlock();
    }
}
```
Imports already present (`Objects`, `ConcurrentHashMap`, `Types`).

- [ ] **Step 5: Extend the concurrency-stress test (writer rotation)**

In `DefaultTreeCacheTest.java`'s existing concurrency test (likely a method or `@Nested class Concurrency`), add `applyCopy` as a sixth writer-rotation case. Insert the new branch alongside the existing 5 mutation kinds. Verify it compiles and runs.

- [ ] **Step 6: Run cache test suite**

Run: `./gradlew test --tests com.myxcomp.ice.xtree.cache.DefaultTreeCacheTest`
Expected: PASS — all cache tests green including the 7 new `ApplyCopy` tests and the extended concurrency rotation.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/cache/TreeCache.java \
        src/main/java/com/myxcomp/ice/xtree/cache/DefaultTreeCache.java \
        src/test/java/com/myxcomp/ice/xtree/cache/DefaultTreeCacheTest.java
git commit -m "feat(copy): add TreeCache.applyCopy (atomic batch apply under one write lock)"
```

---

### Task 9: `OperationType.COPY` + `CopyPayload` + nested `CopiedNode`

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/messaging/event/OperationType.java`
- Create: `src/main/java/com/myxcomp/ice/xtree/messaging/event/payload/CopyPayload.java`
- Create: `src/test/java/com/myxcomp/ice/xtree/messaging/event/payload/CopyPayloadTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/com/myxcomp/ice/xtree/messaging/event/payload/CopyPayloadTest.java`:
```java
package com.myxcomp.ice.xtree.messaging.event.payload;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CopyPayloadTest {

    private final ObjectMapper om = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void roundTrip() throws Exception {
        Instant t = Instant.parse("2026-05-24T10:00:00Z");
        CopyPayload original = new CopyPayload(List.of(
                new CopyPayload.CopiedNode(100L, 10L, "root-copy", "Folder", t, "alice"),
                new CopyPayload.CopiedNode(101L, 100L, "child", "Report", t, "alice")
        ));
        String json = om.writeValueAsString(original);
        CopyPayload parsed = om.readValue(json, CopyPayload.class);
        assertThat(parsed).isEqualTo(original);
    }

    @Test
    void unknownFieldsIgnoredOnEnvelope() throws Exception {
        String json = """
                { "newNodes": [
                    { "itemTreeId": 100, "parentId": 10, "name": "x", "type": "Folder",
                      "lastUpdate": "2026-05-24T10:00:00Z", "lastUpdateUser": "alice",
                      "unknownField": "ignored" }
                  ],
                  "extraEnvelopeField": "ignored" }
                """;
        CopyPayload parsed = om.readValue(json, CopyPayload.class);
        assertThat(parsed.newNodes()).hasSize(1);
        assertThat(parsed.newNodes().get(0).itemTreeId()).isEqualTo(100L);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.myxcomp.ice.xtree.messaging.event.payload.CopyPayloadTest`
Expected: FAIL — class `CopyPayload` does not exist.

- [ ] **Step 3: Add the enum value**

Modify `OperationType.java`:
```java
package com.myxcomp.ice.xtree.messaging.event;

public enum OperationType {
    CREATE, UPDATE, MOVE, RENAME, DELETE, COPY
}
```

- [ ] **Step 4: Create the payload record**

`src/main/java/com/myxcomp/ice/xtree/messaging/event/payload/CopyPayload.java`:
```java
package com.myxcomp.ice.xtree.messaging.event.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;

/**
 * COPY event payload (design §6). Sent by the originating instance after a
 * successful copy. {@code newNodes} is BFS-ordered (new root first, then
 * depth-by-depth) so peers can apply via {@link
 * com.myxcomp.ice.xtree.cache.TreeCache#applyCopy} under one write lock.
 *
 * <p>JSON/XML payloads are not broadcast (design §6).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CopyPayload(List<CopiedNode> newNodes) implements EventPayload {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CopiedNode(
            long itemTreeId,
            Long parentId,
            String name,
            String type,
            Instant lastUpdate,
            String lastUpdateUser
    ) {}
}
```

- [ ] **Step 5: Run test to verify pass**

Run: `./gradlew test --tests com.myxcomp.ice.xtree.messaging.event.payload.CopyPayloadTest`
Expected: PASS, 2 tests green.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/messaging/event/OperationType.java \
        src/main/java/com/myxcomp/ice/xtree/messaging/event/payload/CopyPayload.java \
        src/test/java/com/myxcomp/ice/xtree/messaging/event/payload/CopyPayloadTest.java
git commit -m "feat(copy): add OperationType.COPY and CopyPayload"
```

---

### Task 10: `TreeMutationEventDeserializer` — wire COPY case

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/messaging/event/TreeMutationEventDeserializer.java`
- Modify: `src/test/java/com/myxcomp/ice/xtree/messaging/event/TreeMutationEventTest.java`

- [ ] **Step 1: Write the failing test**

Add to `TreeMutationEventTest.java`:
```java
@Test
void copyEventRoundTrip() throws Exception {
    Instant t = Instant.parse("2026-05-24T10:00:00Z");
    CopyPayload payload = new CopyPayload(List.of(
            new CopyPayload.CopiedNode(100L, 10L, "x", "Folder", t, "alice")
    ));
    TreeMutationEvent event = TreeMutationEvent.builder()
            .eventId("e1")
            .instanceId("i1")
            .sequence(1L)
            .occurredAt(t)
            .iceUser("alice")
            .impersonatedUser(null)
            .operationType(OperationType.COPY)
            .payload(payload)
            .build();

    String json = objectMapper.writeValueAsString(event);
    TreeMutationEvent parsed = objectMapper.readValue(json, TreeMutationEvent.class);

    assertThat(parsed.getOperationType()).isEqualTo(OperationType.COPY);
    assertThat(parsed.getPayload()).isInstanceOf(CopyPayload.class);
    CopyPayload parsedPayload = (CopyPayload) parsed.getPayload();
    assertThat(parsedPayload.newNodes()).hasSize(1);
    assertThat(parsedPayload.newNodes().get(0).itemTreeId()).isEqualTo(100L);
}
```
Add imports: `com.myxcomp.ice.xtree.messaging.event.payload.CopyPayload`, `java.util.List`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.myxcomp.ice.xtree.messaging.event.TreeMutationEventTest.copyEventRoundTrip"`
Expected: FAIL — switch on `operationType` is non-exhaustive (the compiler may also flag the missing case).

- [ ] **Step 3: Add the case to the deserializer**

In `TreeMutationEventDeserializer.java`'s `deserializePayload` method:
```java
return switch (operationType) {
    case CREATE  -> p.getCodec().treeToValue(payloadNode, CreatePayload.class);
    case UPDATE  -> p.getCodec().treeToValue(payloadNode, UpdatePayload.class);
    case MOVE    -> p.getCodec().treeToValue(payloadNode, MovePayload.class);
    case RENAME  -> p.getCodec().treeToValue(payloadNode, RenamePayload.class);
    case DELETE  -> p.getCodec().treeToValue(payloadNode, DeletePayload.class);
    case COPY    -> p.getCodec().treeToValue(payloadNode, CopyPayload.class);
};
```
Add import: `com.myxcomp.ice.xtree.messaging.event.payload.CopyPayload`.

- [ ] **Step 4: Run test to verify pass**

Run: `./gradlew test --tests com.myxcomp.ice.xtree.messaging.event.TreeMutationEventTest`
Expected: PASS — all envelope tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/messaging/event/TreeMutationEventDeserializer.java \
        src/test/java/com/myxcomp/ice/xtree/messaging/event/TreeMutationEventTest.java
git commit -m "feat(copy): wire COPY case into TreeMutationEvent deserialization"
```

---

### Task 11: `EventDispatcher` — wire COPY branch

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/messaging/EventDispatcher.java`
- Modify: `src/test/java/com/myxcomp/ice/xtree/messaging/EventDispatcherTest.java`

- [ ] **Step 1: Write the failing test**

Add to `EventDispatcherTest.java`:
```java
@Test
void copyDispatchesToApplyCopy() {
    Instant t = Instant.parse("2026-05-24T10:00:00Z");
    CopyPayload payload = new CopyPayload(List.of(
            new CopyPayload.CopiedNode(100L, 10L, "x", "Folder", t, "alice"),
            new CopyPayload.CopiedNode(101L, 100L, "y", "Report", t, "alice")
    ));
    TreeMutationEvent event = TreeMutationEvent.builder()
            .operationType(OperationType.COPY)
            .payload(payload)
            .occurredAt(t)
            .build();

    dispatcher.dispatch(event);

    ArgumentCaptor<List<CachedNode>> captor = ArgumentCaptor.captor();
    verify(cache).applyCopy(captor.capture());
    assertThat(captor.getValue())
            .extracting(CachedNode::itemTreeId)
            .containsExactly(100L, 101L);
}

@Test
void copyWithWrongPayloadTypeThrowsClassCast() {
    TreeMutationEvent event = TreeMutationEvent.builder()
            .operationType(OperationType.COPY)
            .payload(new CreatePayload(1L, 0L, "x", "Folder",
                    Instant.parse("2026-05-24T10:00:00Z"), "alice"))
            .occurredAt(Instant.parse("2026-05-24T10:00:00Z"))
            .build();
    assertThatThrownBy(() -> dispatcher.dispatch(event))
            .isInstanceOf(ClassCastException.class);
}
```
Add imports: `com.myxcomp.ice.xtree.messaging.event.payload.CopyPayload`, `com.myxcomp.ice.xtree.cache.CachedNode`, `org.mockito.ArgumentCaptor`, `java.util.List`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests com.myxcomp.ice.xtree.messaging.EventDispatcherTest`
Expected: FAIL — switch is non-exhaustive for `COPY`.

- [ ] **Step 3: Add the branch**

In `EventDispatcher.java`'s `dispatch` method, add:
```java
case COPY -> {
    CopyPayload p = (CopyPayload) event.getPayload();
    List<CachedNode> nodes = p.newNodes().stream()
            .map(n -> new CachedNode(
                    n.itemTreeId(), n.parentId(), n.name(), n.type(),
                    n.lastUpdate(), n.lastUpdateUser()))
            .toList();
    cache.applyCopy(nodes);
}
```
Add imports: `com.myxcomp.ice.xtree.messaging.event.payload.CopyPayload`, `java.util.List`.

- [ ] **Step 4: Run tests to verify pass**

Run: `./gradlew test --tests com.myxcomp.ice.xtree.messaging.EventDispatcherTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/messaging/EventDispatcher.java \
        src/test/java/com/myxcomp/ice/xtree/messaging/EventDispatcherTest.java
git commit -m "feat(copy): wire COPY branch in EventDispatcher"
```

---

### Task 12: `EventConsumerService` — COPY round-trip + self-echo drop

**Files:**
- Modify: `src/test/java/com/myxcomp/ice/xtree/messaging/EventConsumerServiceTest.java`

(No production change needed — the consumer is operation-type-agnostic. We're only verifying the path through the new deserializer + dispatcher.)

- [ ] **Step 1: Write the test**

Add to `EventConsumerServiceTest.java`:
```java
@Test
void copyEventDispatchedWhenNotSelfEcho() throws Exception {
    Instant t = Instant.parse("2026-05-24T10:00:00Z");
    String json = objectMapper.writeValueAsString(TreeMutationEvent.builder()
            .eventId("e1")
            .instanceId("peer-instance")        // != local instanceId
            .sequence(1L)
            .occurredAt(t)
            .iceUser("alice")
            .operationType(OperationType.COPY)
            .payload(new CopyPayload(List.of(
                    new CopyPayload.CopiedNode(100L, 10L, "x", "Folder", t, "alice"))))
            .build());

    consumer.processPayload(json);

    verify(dispatcher).dispatch(argThat(e -> e.getOperationType() == OperationType.COPY));
}

@Test
void copyEventDroppedOnSelfEcho() throws Exception {
    when(instanceIdProvider.getInstanceId()).thenReturn("self");
    Instant t = Instant.parse("2026-05-24T10:00:00Z");
    String json = objectMapper.writeValueAsString(TreeMutationEvent.builder()
            .eventId("e1")
            .instanceId("self")                  // matches local instanceId
            .sequence(1L)
            .occurredAt(t)
            .iceUser("alice")
            .operationType(OperationType.COPY)
            .payload(new CopyPayload(List.of(
                    new CopyPayload.CopiedNode(100L, 10L, "x", "Folder", t, "alice"))))
            .build());

    consumer.processPayload(json);

    verify(dispatcher, never()).dispatch(any());
}
```
Add imports as needed; reuse existing test fixtures for `dispatcher`, `instanceIdProvider`, `consumer`, `objectMapper` (already present per Phase 10 tests).

- [ ] **Step 2: Run tests**

Run: `./gradlew test --tests com.myxcomp.ice.xtree.messaging.EventConsumerServiceTest`
Expected: PASS (no production change needed; the route is already plumbed through tasks 10 + 11).

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/myxcomp/ice/xtree/messaging/EventConsumerServiceTest.java
git commit -m "test(copy): cover COPY round-trip and self-echo drop in EventConsumerServiceTest"
```

---

### Task 13: `ItemService.copyItem` — happy path scaffolding

This is the heart of the feature. Implemented in stages (one nested class of tests per stage) so each stage is a small, reviewable commit.

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/service/ItemService.java`
- Modify: `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceTest.java`

- [ ] **Step 1: Write the failing happy-path test (single-item copy, no collision)**

Add to `ItemServiceTest.java` a new `@Nested class CopyItem` and the first test:
```java
@Nested
class CopyItem {

    private final Instant T = Instant.parse("2026-05-24T10:00:00Z");
    private final UserContext ctx = new UserContext("alice", null);

    @BeforeEach
    void resetMocks() {
        // ensure existing test fixtures are loaded; mocks for cache + repository + publisher
        when(timeMapper.now()).thenReturn(T);
        when(instanceIdProvider.getInstanceId()).thenReturn("inst-1");
        when(sequenceGenerator.next()).thenReturn(1L);
    }

    @Test
    void happyPathSingleItemCopy() {
        long sourceId = 50L;
        long destId = 10L;
        long newId = 999L;
        CachedNode source = new CachedNode(sourceId, 99L, "Report A", "Report", T, "bob");
        CachedNode dest = new CachedNode(destId, 1L, "alice", "Folder", T, "alice");
        when(cache.getById(sourceId)).thenReturn(Optional.of(source));
        when(cache.getById(destId)).thenReturn(Optional.of(dest));
        when(cache.findHomeFolder("alice")).thenReturn(Optional.of(dest));
        when(cache.isAncestor(destId, destId)).thenReturn(false);    // dest == home, ancestor check skipped via equality
        when(cache.isAncestor(sourceId, destId)).thenReturn(false);
        when(cache.getChildren(destId)).thenReturn(List.of());
        // cache pre-flight BFS: just the source row
        when(cache.getSubtreeFlat(sourceId)).thenReturn(List.of(source));
        // DB snapshot
        when(repository.findRowsForCopy(eq(sourceId), anyInt())).thenReturn(List.of(
                new ItemTreeFullRow(sourceId, 99L, "Report A", "Report",
                        "{\"k\":1}", null, T, "bob")));
        when(repository.allocateIds(1)).thenReturn(List.of(newId));

        List<CachedNode> result = service.copyItem(sourceId, destId, ctx);

        // returned subtree
        assertThat(result).hasSize(1);
        assertThat(result.get(0).itemTreeId()).isEqualTo(newId);
        assertThat(result.get(0).parentId()).isEqualTo(destId);
        assertThat(result.get(0).name()).isEqualTo("Report A");
        assertThat(result.get(0).type()).isEqualTo("Report");
        assertThat(result.get(0).lastUpdate()).isEqualTo(T);
        assertThat(result.get(0).lastUpdateUser()).isEqualTo("alice");

        // batch INSERT shape
        ArgumentCaptor<List<ItemTreeFullRow>> insertCaptor = ArgumentCaptor.captor();
        verify(repository).insertBatch(insertCaptor.capture());
        assertThat(insertCaptor.getValue()).hasSize(1);
        assertThat(insertCaptor.getValue().get(0).json()).isEqualTo("{\"k\":1}");
        assertThat(insertCaptor.getValue().get(0).lastUpdateUser()).isEqualTo("alice");

        // cache apply
        ArgumentCaptor<List<CachedNode>> cacheCaptor = ArgumentCaptor.captor();
        verify(cache).applyCopy(cacheCaptor.capture());
        assertThat(cacheCaptor.getValue()).extracting(CachedNode::itemTreeId).containsExactly(newId);

        // event publish
        ArgumentCaptor<TreeMutationEvent> eventCaptor = ArgumentCaptor.captor();
        verify(publisher).publish(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getOperationType()).isEqualTo(OperationType.COPY);
        CopyPayload payload = (CopyPayload) eventCaptor.getValue().getPayload();
        assertThat(payload.newNodes()).hasSize(1);
        assertThat(payload.newNodes().get(0).itemTreeId()).isEqualTo(newId);
    }
}
```
Add imports: `org.junit.jupiter.api.Nested`, `org.junit.jupiter.api.BeforeEach`, `com.myxcomp.ice.xtree.persistence.ItemTreeFullRow`, `com.myxcomp.ice.xtree.messaging.event.payload.CopyPayload`, `org.mockito.ArgumentCaptor`. The test class likely already has `service`, `cache`, `repository`, `publisher`, `timeMapper`, `instanceIdProvider`, `sequenceGenerator` fields per Phase 7 conventions — reuse them.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.myxcomp.ice.xtree.service.ItemServiceTest$CopyItem.happyPathSingleItemCopy"`
Expected: FAIL — method `copyItem` does not exist.

- [ ] **Step 3: Implement `copyItem` in `ItemService`**

Inject `CopyProperties`:
```java
private final CopyProperties copyProperties;

public ItemService(/* existing params */,
                   CopyProperties copyProperties) {
    /* existing assignments */
    this.copyProperties = Objects.requireNonNull(copyProperties, "copyProperties");
}
```
(Add `CopyProperties copyProperties` to the constructor signature; existing tests pass either `mock(CopyProperties.class)` with `when(copyProperties.maxNodes()).thenReturn(100)` or a real `new CopyProperties(100)`.)

Add the method:
```java
/**
 * Copies a single item or an entire folder subtree (capped at
 * {@code itemtree.copy.max-nodes}) into a folder owned by the caller.
 *
 * <p>Validation order:
 * ITEM_NOT_FOUND, CANNOT_COPY_ROOT, DESTINATION_NOT_FOUND,
 * DESTINATION_NOT_FOLDER, HOME_FOLDER_NOT_FOUND,
 * DESTINATION_NOT_IN_USER_FOLDER, COPY_INTO_DESCENDANT, COPY_TOO_LARGE.
 *
 * @return BFS-ordered list of the newly created cached nodes (new root first)
 * @throws NotFoundException        on the 404-class validations
 * @throws ValidationException      on the 400-class validations
 * @throws CopyTooLargeException    when subtree size exceeds the cap
 */
@Transactional
public List<CachedNode> copyItem(long sourceId, long destinationFolderId, UserContext userContext) {
    Objects.requireNonNull(userContext, "userContext");

    // 1. ITEM_NOT_FOUND
    CachedNode source = cache.getById(sourceId).orElseThrow(() -> {
        recordRejection(ErrorCode.ITEM_NOT_FOUND);
        return new NotFoundException(ErrorCode.ITEM_NOT_FOUND,
                "Item " + sourceId + " not found");
    });

    // 2. CANNOT_COPY_ROOT
    if (sourceId == TreeConstants.ROOT_ID) {
        recordRejection(ErrorCode.CANNOT_COPY_ROOT);
        throw new ValidationException(ErrorCode.CANNOT_COPY_ROOT,
                "Cannot copy the root folder");
    }

    // 3. DESTINATION_NOT_FOUND
    CachedNode destination = cache.getById(destinationFolderId).orElseThrow(() -> {
        recordRejection(ErrorCode.DESTINATION_NOT_FOUND);
        return new NotFoundException(ErrorCode.DESTINATION_NOT_FOUND,
                "Destination folder " + destinationFolderId + " not found");
    });

    // 4. DESTINATION_NOT_FOLDER
    if (!Types.isFolder(destination.type())) {
        recordRejection(ErrorCode.DESTINATION_NOT_FOLDER);
        throw new ValidationException(ErrorCode.DESTINATION_NOT_FOLDER,
                "Destination " + destinationFolderId + " is not a folder (type="
                        + destination.type() + ")");
    }

    // 5. HOME_FOLDER_NOT_FOUND
    String effectiveUser = userContext.effectiveUser();
    CachedNode homeFolder = cache.findHomeFolder(effectiveUser).orElseThrow(() -> {
        recordRejection(ErrorCode.HOME_FOLDER_NOT_FOUND);
        return new NotFoundException(ErrorCode.HOME_FOLDER_NOT_FOUND,
                "No home folder for user '" + effectiveUser + "'");
    });

    // 6. DESTINATION_NOT_IN_USER_FOLDER
    boolean destInUserFolder = destination.itemTreeId() == homeFolder.itemTreeId()
            || cache.isAncestor(homeFolder.itemTreeId(), destination.itemTreeId());
    if (!destInUserFolder) {
        recordRejection(ErrorCode.DESTINATION_NOT_IN_USER_FOLDER);
        throw new ValidationException(ErrorCode.DESTINATION_NOT_IN_USER_FOLDER,
                "Destination " + destinationFolderId
                        + " is not under home folder of '" + effectiveUser + "'");
    }

    // 7. COPY_INTO_DESCENDANT
    if (sourceId == destinationFolderId || cache.isAncestor(sourceId, destinationFolderId)) {
        recordRejection(ErrorCode.COPY_INTO_DESCENDANT);
        throw new ValidationException(ErrorCode.COPY_INTO_DESCENDANT,
                "Cannot copy id=" + sourceId + " into itself or a descendant");
    }

    int cap = copyProperties.maxNodes();

    // 8a. Pre-flight cap (cache)
    List<CachedNode> cachePreview = cache.getSubtreeFlat(sourceId);
    if (cachePreview.size() > cap) {
        recordRejection(ErrorCode.COPY_TOO_LARGE);
        throw new CopyTooLargeException(
                "Source subtree has " + cachePreview.size() + " nodes (cache); cap is " + cap);
    }

    // DB snapshot — authoritative
    List<ItemTreeFullRow> sourceRows = repository.findRowsForCopy(sourceId, cap + 1);
    if (sourceRows.isEmpty()) {
        // Source vanished between cache check and DB read; treat as not found.
        recordRejection(ErrorCode.ITEM_NOT_FOUND);
        throw new NotFoundException(ErrorCode.ITEM_NOT_FOUND,
                "Item " + sourceId + " not found in DB");
    }
    if (sourceRows.size() > cap) {
        recordRejection(ErrorCode.COPY_TOO_LARGE);
        throw new CopyTooLargeException(
                "Source subtree has more than " + cap + " nodes (DB)");
    }

    // Allocate ids and build oldId→newId map
    List<Long> newIds = repository.allocateIds(sourceRows.size());
    Map<Long, Long> idMap = new HashMap<>();
    for (int i = 0; i < sourceRows.size(); i++) {
        idMap.put(sourceRows.get(i).itemTreeId(), newIds.get(i));
    }

    // Compute top-level name (suffix on collision)
    String newRootName = chooseRootName(source.name(), destinationFolderId);

    Instant now = timeMapper.now();
    String stampUser = userContext.effectiveUser();

    // Build new rows, remapped + restamped
    List<ItemTreeFullRow> newRows = new ArrayList<>(sourceRows.size());
    List<CachedNode> newCacheNodes = new ArrayList<>(sourceRows.size());
    for (int i = 0; i < sourceRows.size(); i++) {
        ItemTreeFullRow src = sourceRows.get(i);
        long newId = newIds.get(i);
        long newParent = (i == 0)
                ? destinationFolderId
                : idMap.get(src.parentId());
        String name = (i == 0) ? newRootName : src.name();

        newRows.add(new ItemTreeFullRow(
                newId, newParent, name, src.type(),
                src.json(), src.xml(), now, stampUser));
        newCacheNodes.add(new CachedNode(
                newId, newParent, name, src.type(), now, stampUser));
    }

    repository.insertBatch(newRows);
    cache.applyCopy(newCacheNodes);

    // Build event payload
    List<CopyPayload.CopiedNode> payloadNodes = new ArrayList<>(newCacheNodes.size());
    for (CachedNode n : newCacheNodes) {
        payloadNodes.add(new CopyPayload.CopiedNode(
                n.itemTreeId(), n.parentId(), n.name(), n.type(),
                n.lastUpdate(), n.lastUpdateUser()));
    }
    try {
        publisher.publish(buildEvent(userContext, OperationType.COPY,
                new CopyPayload(payloadNodes), now));
    } catch (RuntimeException e) {
        log.error("EventPublisher threw on {}; event dropped", OperationType.COPY, e);
    }

    meterRegistry.counter("itemtree.copy.requests", "result", "success").increment();
    meterRegistry.summary("itemtree.copy.subtree.size").record(newCacheNodes.size());

    return newCacheNodes;
}

private String chooseRootName(String sourceName, long destinationFolderId) {
    java.util.Set<String> sibs = new java.util.HashSet<>();
    for (CachedNode c : cache.getChildren(destinationFolderId)) sibs.add(c.name());
    if (!sibs.contains(sourceName)) return sourceName;
    String first = sourceName + " (copy)";
    if (!sibs.contains(first)) return first;
    int n = 2;
    while (sibs.contains(sourceName + " (copy " + n + ")")) {
        n++;
    }
    return sourceName + " (copy " + n + ")";
}

private void recordRejection(ErrorCode reason) {
    meterRegistry.counter("itemtree.copy.requests", "result", "rejected").increment();
    meterRegistry.counter("itemtree.copy.rejected", "reason", reason.name()).increment();
}
```
Add imports as needed: `com.myxcomp.ice.xtree.common.TreeConstants`, `com.myxcomp.ice.xtree.config.CopyProperties`, `com.myxcomp.ice.xtree.messaging.event.payload.CopyPayload`, `com.myxcomp.ice.xtree.persistence.ItemTreeFullRow`, `com.myxcomp.ice.xtree.service.exception.CopyTooLargeException`.

- [ ] **Step 4: Run the happy-path test**

Run: `./gradlew test --tests "com.myxcomp.ice.xtree.service.ItemServiceTest$CopyItem.happyPathSingleItemCopy"`
Expected: PASS. (You may need to update any pre-existing `ItemServiceTest` constructor calls to pass a `CopyProperties` mock or real value.)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/service/ItemService.java \
        src/test/java/com/myxcomp/ice/xtree/service/ItemServiceTest.java
git commit -m "feat(copy): add ItemService.copyItem (single-item happy path)"
```

---

### Task 14: `ItemService.copyItem` — validation tests (8 errors) + folder-subtree happy path

**Files:**
- Modify: `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceTest.java`

(Production code from Task 13 already handles all 8 errors; this task locks the behavior in via tests.)

- [ ] **Step 1: Add the validation tests**

Inside the existing `CopyItem` nested class, add:
```java
@Test
void itemNotFoundOnUnknownSource() {
    when(cache.getById(123L)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.copyItem(123L, 10L, ctx))
            .isInstanceOf(NotFoundException.class)
            .satisfies(e -> assertThat(((NotFoundException) e).errorCode())
                    .isEqualTo(ErrorCode.ITEM_NOT_FOUND));
}

@Test
void cannotCopyRoot() {
    CachedNode root = new CachedNode(TreeConstants.ROOT_ID, 0L, "root", "Folder", T, "system");
    when(cache.getById(TreeConstants.ROOT_ID)).thenReturn(Optional.of(root));
    assertThatThrownBy(() -> service.copyItem(TreeConstants.ROOT_ID, 10L, ctx))
            .isInstanceOf(ValidationException.class)
            .satisfies(e -> assertThat(((ValidationException) e).errorCode())
                    .isEqualTo(ErrorCode.CANNOT_COPY_ROOT));
}

@Test
void destinationNotFound() {
    CachedNode source = new CachedNode(50L, 99L, "X", "Report", T, "bob");
    when(cache.getById(50L)).thenReturn(Optional.of(source));
    when(cache.getById(999L)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.copyItem(50L, 999L, ctx))
            .isInstanceOf(NotFoundException.class)
            .satisfies(e -> assertThat(((NotFoundException) e).errorCode())
                    .isEqualTo(ErrorCode.DESTINATION_NOT_FOUND));
}

@Test
void destinationNotFolder() {
    CachedNode source = new CachedNode(50L, 99L, "X", "Report", T, "bob");
    CachedNode notFolder = new CachedNode(11L, 1L, "thing", "Report", T, "bob");
    when(cache.getById(50L)).thenReturn(Optional.of(source));
    when(cache.getById(11L)).thenReturn(Optional.of(notFolder));
    assertThatThrownBy(() -> service.copyItem(50L, 11L, ctx))
            .isInstanceOf(ValidationException.class)
            .satisfies(e -> assertThat(((ValidationException) e).errorCode())
                    .isEqualTo(ErrorCode.DESTINATION_NOT_FOLDER));
}

@Test
void homeFolderNotFound() {
    CachedNode source = new CachedNode(50L, 99L, "X", "Report", T, "bob");
    CachedNode dest = new CachedNode(10L, 1L, "anotherUser", "Folder", T, "x");
    when(cache.getById(50L)).thenReturn(Optional.of(source));
    when(cache.getById(10L)).thenReturn(Optional.of(dest));
    when(cache.findHomeFolder("alice")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.copyItem(50L, 10L, ctx))
            .isInstanceOf(NotFoundException.class)
            .satisfies(e -> assertThat(((NotFoundException) e).errorCode())
                    .isEqualTo(ErrorCode.HOME_FOLDER_NOT_FOUND));
}

@Test
void destinationNotInUserFolder() {
    CachedNode source = new CachedNode(50L, 99L, "X", "Report", T, "bob");
    CachedNode dest = new CachedNode(10L, 1L, "other", "Folder", T, "x");
    CachedNode home = new CachedNode(20L, 1L, "alice", "Folder", T, "alice");
    when(cache.getById(50L)).thenReturn(Optional.of(source));
    when(cache.getById(10L)).thenReturn(Optional.of(dest));
    when(cache.findHomeFolder("alice")).thenReturn(Optional.of(home));
    when(cache.isAncestor(20L, 10L)).thenReturn(false);
    assertThatThrownBy(() -> service.copyItem(50L, 10L, ctx))
            .isInstanceOf(ValidationException.class)
            .satisfies(e -> assertThat(((ValidationException) e).errorCode())
                    .isEqualTo(ErrorCode.DESTINATION_NOT_IN_USER_FOLDER));
}

@Test
void copyIntoSelf() {
    CachedNode source = new CachedNode(10L, 1L, "alice", "Folder", T, "alice");
    when(cache.getById(10L)).thenReturn(Optional.of(source));
    when(cache.findHomeFolder("alice")).thenReturn(Optional.of(source));
    assertThatThrownBy(() -> service.copyItem(10L, 10L, ctx))
            .isInstanceOf(ValidationException.class)
            .satisfies(e -> assertThat(((ValidationException) e).errorCode())
                    .isEqualTo(ErrorCode.COPY_INTO_DESCENDANT));
}

@Test
void copyIntoDescendant() {
    CachedNode source = new CachedNode(50L, 99L, "src", "Folder", T, "bob");
    CachedNode descendant = new CachedNode(60L, 50L, "child", "Folder", T, "bob");
    CachedNode home = new CachedNode(20L, 1L, "alice", "Folder", T, "alice");
    when(cache.getById(50L)).thenReturn(Optional.of(source));
    when(cache.getById(60L)).thenReturn(Optional.of(descendant));
    when(cache.findHomeFolder("alice")).thenReturn(Optional.of(home));
    when(cache.isAncestor(20L, 60L)).thenReturn(true);   // descendant is in home
    when(cache.isAncestor(50L, 60L)).thenReturn(true);   // descendant is in source
    assertThatThrownBy(() -> service.copyItem(50L, 60L, ctx))
            .isInstanceOf(ValidationException.class)
            .satisfies(e -> assertThat(((ValidationException) e).errorCode())
                    .isEqualTo(ErrorCode.COPY_INTO_DESCENDANT));
}

@Test
void copyTooLargePreFlight() {
    CachedNode source = new CachedNode(50L, 99L, "src", "Folder", T, "bob");
    CachedNode dest = new CachedNode(10L, 1L, "alice", "Folder", T, "alice");
    when(cache.getById(50L)).thenReturn(Optional.of(source));
    when(cache.getById(10L)).thenReturn(Optional.of(dest));
    when(cache.findHomeFolder("alice")).thenReturn(Optional.of(dest));
    when(cache.isAncestor(50L, 10L)).thenReturn(false);
    // synthesize 101 cached nodes — over the default cap of 100
    List<CachedNode> oversized = new ArrayList<>();
    for (int i = 0; i < 101; i++) {
        oversized.add(new CachedNode(50L + i, i == 0 ? 99L : 50L + i - 1,
                "n" + i, "Folder", T, "bob"));
    }
    when(cache.getSubtreeFlat(50L)).thenReturn(oversized);

    assertThatThrownBy(() -> service.copyItem(50L, 10L, ctx))
            .isInstanceOf(CopyTooLargeException.class);
    verify(repository, never()).findRowsForCopy(anyLong(), anyInt());
}

@Test
void copyTooLargeAuthoritativeDb() {
    CachedNode source = new CachedNode(50L, 99L, "src", "Folder", T, "bob");
    CachedNode dest = new CachedNode(10L, 1L, "alice", "Folder", T, "alice");
    when(cache.getById(50L)).thenReturn(Optional.of(source));
    when(cache.getById(10L)).thenReturn(Optional.of(dest));
    when(cache.findHomeFolder("alice")).thenReturn(Optional.of(dest));
    when(cache.isAncestor(50L, 10L)).thenReturn(false);
    when(cache.getSubtreeFlat(50L)).thenReturn(List.of(source));     // cache says OK
    // DB returns cap+1 = 101 rows
    List<ItemTreeFullRow> dbRows = new ArrayList<>();
    for (int i = 0; i < 101; i++) {
        dbRows.add(new ItemTreeFullRow(50L + i, i == 0 ? 99L : 50L + i - 1,
                "n" + i, "Folder", null, null, T, "bob"));
    }
    when(repository.findRowsForCopy(eq(50L), anyInt())).thenReturn(dbRows);

    assertThatThrownBy(() -> service.copyItem(50L, 10L, ctx))
            .isInstanceOf(CopyTooLargeException.class);
    verify(repository, never()).insertBatch(any());
}
```

- [ ] **Step 2: Add the folder-subtree happy path with name suffix**

Still inside `CopyItem`:
```java
@Test
void folderSubtreeWithNameCollisionAutoSuffixed() {
    long sourceId = 50L;
    long destId = 10L;
    long destChildExisting = 30L;
    CachedNode source = new CachedNode(sourceId, 99L, "Things", "Folder", T, "bob");
    CachedNode dest = new CachedNode(destId, 1L, "alice", "Folder", T, "alice");
    CachedNode existingSibling = new CachedNode(destChildExisting, destId, "Things", "Folder", T, "alice");
    when(cache.getById(sourceId)).thenReturn(Optional.of(source));
    when(cache.getById(destId)).thenReturn(Optional.of(dest));
    when(cache.findHomeFolder("alice")).thenReturn(Optional.of(dest));
    when(cache.isAncestor(sourceId, destId)).thenReturn(false);
    when(cache.getSubtreeFlat(sourceId)).thenReturn(List.of(
            source,
            new CachedNode(51L, sourceId, "leaf", "Report", T, "bob")));
    when(cache.getChildren(destId)).thenReturn(List.of(existingSibling));
    when(repository.findRowsForCopy(eq(sourceId), anyInt())).thenReturn(List.of(
            new ItemTreeFullRow(sourceId, 99L, "Things", "Folder", null, null, T, "bob"),
            new ItemTreeFullRow(51L, sourceId, "leaf", "Report", "{\"k\":1}", null, T, "bob")));
    when(repository.allocateIds(2)).thenReturn(List.of(900L, 901L));

    List<CachedNode> result = service.copyItem(sourceId, destId, ctx);

    assertThat(result).hasSize(2);
    assertThat(result.get(0).name()).isEqualTo("Things (copy)");       // suffix
    assertThat(result.get(0).itemTreeId()).isEqualTo(900L);
    assertThat(result.get(1).name()).isEqualTo("leaf");                // children untouched
    assertThat(result.get(1).parentId()).isEqualTo(900L);              // remapped
}

@Test
void nameSuffixWalksToNumberedVariant() {
    long sourceId = 50L;
    long destId = 10L;
    CachedNode source = new CachedNode(sourceId, 99L, "X", "Folder", T, "bob");
    CachedNode dest = new CachedNode(destId, 1L, "alice", "Folder", T, "alice");
    when(cache.getById(sourceId)).thenReturn(Optional.of(source));
    when(cache.getById(destId)).thenReturn(Optional.of(dest));
    when(cache.findHomeFolder("alice")).thenReturn(Optional.of(dest));
    when(cache.isAncestor(sourceId, destId)).thenReturn(false);
    when(cache.getSubtreeFlat(sourceId)).thenReturn(List.of(source));
    when(cache.getChildren(destId)).thenReturn(List.of(
            new CachedNode(30L, destId, "X", "Folder", T, "alice"),
            new CachedNode(31L, destId, "X (copy)", "Folder", T, "alice"),
            new CachedNode(32L, destId, "X (copy 2)", "Folder", T, "alice")));
    when(repository.findRowsForCopy(eq(sourceId), anyInt())).thenReturn(List.of(
            new ItemTreeFullRow(sourceId, 99L, "X", "Folder", null, null, T, "bob")));
    when(repository.allocateIds(1)).thenReturn(List.of(900L));

    List<CachedNode> result = service.copyItem(sourceId, destId, ctx);
    assertThat(result.get(0).name()).isEqualTo("X (copy 3)");
}

@Test
void publisherExceptionIsSwallowed() {
    long sourceId = 50L;
    long destId = 10L;
    CachedNode source = new CachedNode(sourceId, 99L, "X", "Report", T, "bob");
    CachedNode dest = new CachedNode(destId, 1L, "alice", "Folder", T, "alice");
    when(cache.getById(sourceId)).thenReturn(Optional.of(source));
    when(cache.getById(destId)).thenReturn(Optional.of(dest));
    when(cache.findHomeFolder("alice")).thenReturn(Optional.of(dest));
    when(cache.isAncestor(sourceId, destId)).thenReturn(false);
    when(cache.getSubtreeFlat(sourceId)).thenReturn(List.of(source));
    when(cache.getChildren(destId)).thenReturn(List.of());
    when(repository.findRowsForCopy(eq(sourceId), anyInt())).thenReturn(List.of(
            new ItemTreeFullRow(sourceId, 99L, "X", "Report", null, null, T, "bob")));
    when(repository.allocateIds(1)).thenReturn(List.of(900L));
    doThrow(new RuntimeException("solace boom")).when(publisher).publish(any());

    // Must NOT throw; DB write and cache update still stand.
    List<CachedNode> result = service.copyItem(sourceId, destId, ctx);
    assertThat(result).hasSize(1);
    verify(repository).insertBatch(any());
    verify(cache).applyCopy(any());
}
```

- [ ] **Step 3: Run the full `CopyItem` test class**

Run: `./gradlew test --tests "com.myxcomp.ice.xtree.service.ItemServiceTest$CopyItem"`
Expected: PASS — all 13 tests green (1 from Task 13 + 12 here).

- [ ] **Step 4: Run the full ItemService test suite for regressions**

Run: `./gradlew test --tests com.myxcomp.ice.xtree.service.ItemServiceTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/myxcomp/ice/xtree/service/ItemServiceTest.java
git commit -m "test(copy): cover all 8 validation errors + suffix algorithm + publisher resilience"
```

---

### Task 15: OpenAPI spec — copy endpoint + `CopyItemRequest` schema + 413 response

**Files:**
- Modify: `src/main/resources/openapi/itemtree-api.yaml`

- [ ] **Step 1: Add the endpoint path**

Insert under `paths:` between the existing `rename` and `updateItemData` blocks (or any sensible position; mirror the `move` spec):
```yaml
  /api/v1/itemtree/items/{id}/copy:
    post:
      tags: [items]
      operationId: copyItem
      summary: Copy item (or subtree) into a folder owned by the caller
      parameters:
        - name: id
          in: path
          required: true
          schema:
            type: integer
            format: int64
        - $ref: '#/components/parameters/XIceUser'
        - $ref: '#/components/parameters/XImpersonatedUser'
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/CopyItemRequest'
      responses:
        '201':
          description: New subtree (BFS-ordered, root first)
          content:
            application/json:
              schema:
                type: array
                items:
                  $ref: '#/components/schemas/ItemNode'
        '400':
          $ref: '#/components/responses/BadRequest'
        '404':
          $ref: '#/components/responses/NotFound'
        '413':
          $ref: '#/components/responses/PayloadTooLarge'
        '503':
          $ref: '#/components/responses/ServiceUnavailable'
```

- [ ] **Step 2: Add the request schema**

Under `components.schemas` (next to `MoveRequest`):
```yaml
    CopyItemRequest:
      type: object
      required: [destinationFolderId]
      properties:
        destinationFolderId:
          type: integer
          format: int64
          description: |
            Folder that will own the new copy. Must be the caller's home folder
            or a descendant of it. Caller identity = impersonatedUser if present,
            else iceUser.
```

- [ ] **Step 3: Add the shared 413 response**

Under `components.responses`:
```yaml
    PayloadTooLarge:
      description: Copy subtree exceeds the configured cap
      content:
        application/problem+json:
          schema:
            $ref: '#/components/schemas/Problem'
```

- [ ] **Step 4: Regenerate sources**

Run: `./gradlew build`
Expected: BUILD FAILS at the controller compile step (because `ItemsApi` now has a new abstract method `copyItem`). That's the failing-test signal for the next task. Confirm the generated file has the method:
```bash
grep -A 5 "copyItem" build/generated/sources/openapi/src/main/java/com/myxcomp/ice/xtree/generated/api/ItemsApi.java | head -20
```

- [ ] **Step 5: Commit the yaml change**

```bash
git add src/main/resources/openapi/itemtree-api.yaml
git commit -m "feat(copy): add POST /items/{id}/copy to OpenAPI spec"
```

---

### Task 16: `ItemController.copyItem` — wire HTTP layer

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/api/controller/ItemController.java`
- Modify: `src/test/java/com/myxcomp/ice/xtree/api/controller/ItemControllerTest.java`

- [ ] **Step 1: Write the failing tests**

Add to `ItemControllerTest.java` (new `@Nested class CopyItem`):
```java
@Nested
class CopyItem {

    @Test
    void happyPathReturns201WithSubtree() throws Exception {
        Instant t = Instant.parse("2026-05-24T10:00:00Z");
        when(itemService.copyItem(eq(50L), eq(10L), any(UserContext.class)))
                .thenReturn(List.of(
                        new CachedNode(900L, 10L, "Things (copy)", "Folder", t, "alice"),
                        new CachedNode(901L, 900L, "leaf", "Report", t, "alice")));

        mockMvc.perform(post("/api/v1/itemtree/items/50/copy")
                        .header("X-Ice-User", "alice")
                        .contentType("application/json")
                        .content("{\"destinationFolderId\": 10}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].itemTreeId").value(900))
                .andExpect(jsonPath("$[0].name").value("Things (copy)"))
                .andExpect(jsonPath("$[1].itemTreeId").value(901));
    }

    @Test
    void missingDestinationFolderIdReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/itemtree/items/50/copy")
                        .header("X-Ice-User", "alice")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @MethodSource("serviceErrorCases")
    void serviceErrorsMappedToStatusAndErrorCode(
            RuntimeException thrown, int expectedStatus, String expectedCode) throws Exception {
        when(itemService.copyItem(anyLong(), anyLong(), any())).thenThrow(thrown);

        mockMvc.perform(post("/api/v1/itemtree/items/50/copy")
                        .header("X-Ice-User", "alice")
                        .contentType("application/json")
                        .content("{\"destinationFolderId\": 10}"))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.errorCode").value(expectedCode));
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> serviceErrorCases() {
        return java.util.stream.Stream.of(
            org.junit.jupiter.params.provider.Arguments.of(
                new NotFoundException(ErrorCode.ITEM_NOT_FOUND, "x"), 404, "ITEM_NOT_FOUND"),
            org.junit.jupiter.params.provider.Arguments.of(
                new ValidationException(ErrorCode.CANNOT_COPY_ROOT, "x"), 400, "CANNOT_COPY_ROOT"),
            org.junit.jupiter.params.provider.Arguments.of(
                new NotFoundException(ErrorCode.DESTINATION_NOT_FOUND, "x"), 404, "DESTINATION_NOT_FOUND"),
            org.junit.jupiter.params.provider.Arguments.of(
                new ValidationException(ErrorCode.DESTINATION_NOT_FOLDER, "x"), 400, "DESTINATION_NOT_FOLDER"),
            org.junit.jupiter.params.provider.Arguments.of(
                new NotFoundException(ErrorCode.HOME_FOLDER_NOT_FOUND, "x"), 404, "HOME_FOLDER_NOT_FOUND"),
            org.junit.jupiter.params.provider.Arguments.of(
                new ValidationException(ErrorCode.DESTINATION_NOT_IN_USER_FOLDER, "x"), 400, "DESTINATION_NOT_IN_USER_FOLDER"),
            org.junit.jupiter.params.provider.Arguments.of(
                new ValidationException(ErrorCode.COPY_INTO_DESCENDANT, "x"), 400, "COPY_INTO_DESCENDANT"),
            org.junit.jupiter.params.provider.Arguments.of(
                new CopyTooLargeException("too big"), 413, "COPY_TOO_LARGE"));
    }
}
```
Add imports as needed: `org.junit.jupiter.params.ParameterizedTest`, `org.junit.jupiter.params.provider.MethodSource`, the new exception/payload classes, `org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post`, etc.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.myxcomp.ice.xtree.api.controller.ItemControllerTest$CopyItem"`
Expected: FAIL — controller does not implement `copyItem` yet (or returns 501).

- [ ] **Step 3: Implement the controller method**

Add to `ItemController.java`:
```java
@Override
public ResponseEntity<List<ItemNode>> copyItem(Long id,
                                               String xIceUser,
                                               CopyItemRequest req,
                                               String xImpersonatedUser) {
    UserContext ctx = new UserContext(xIceUser, xImpersonatedUser);
    List<CachedNode> newSubtree = itemService.copyItem(id, req.getDestinationFolderId(), ctx);
    List<ItemNode> dtos = newSubtree.stream()
            .map(itemNodeMapper::toDto)
            .toList();
    return ResponseEntity.status(HttpStatus.CREATED).body(dtos);
}
```
Add imports: `com.myxcomp.ice.xtree.generated.model.CopyItemRequest`.

- [ ] **Step 4: Run tests to verify pass**

Run: `./gradlew test --tests com.myxcomp.ice.xtree.api.controller.ItemControllerTest`
Expected: PASS — all controller tests green.

- [ ] **Step 5: Full build check**

Run: `./gradlew clean build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/api/controller/ItemController.java \
        src/test/java/com/myxcomp/ice/xtree/api/controller/ItemControllerTest.java
git commit -m "feat(copy): wire ItemController.copyItem (201 happy + 8 error mappings)"
```

---

### Task 17: `MessagingLoopbackIT` — COPY round-trip

**Files:**
- Modify: `src/test/java/com/myxcomp/ice/xtree/messaging/MessagingLoopbackIT.java`

- [ ] **Step 1: Add the failing test**

Inside the existing class, after the existing round-trip tests:
```java
@Test
void copyRoundTripConvergesAcrossInstances() throws Exception {
    // Use ItemService.copyItem to produce a real COPY event; peer cache should converge.
    // Pick a small in-DB subtree from the seed data: a leaf is fine.
    // (Adjust ids to match the seed; use the same pattern as the existing tests for setup.)
    long sourceId = 25L;          // leafItem in seed
    long destId   = 12L;          // deepuser home folder

    UserContext ctx = new UserContext("deepuser", null);
    List<CachedNode> result = itemService.copyItem(sourceId, destId, ctx);

    assertThat(result).hasSize(1);
    long newId = result.get(0).itemTreeId();
    assertThat(cache.getById(newId)).isPresent();

    // give the bus a moment to deliver — peer subscriber runs synchronously by default,
    // but use Awaitility for robustness
    Awaitility.await().atMost(java.time.Duration.ofSeconds(2))
            .untilAsserted(() -> assertThat(peerCache.getById(newId)).isPresent());

    assertThat(peerCache.getById(newId)).get()
            .extracting(CachedNode::parentId).isEqualTo(destId);
}
```
(Adjust references to `itemService`, `cache`, `peerCache` to match the existing `MessagingLoopbackIT` fixture pattern; the file already wires two contexts or one-with-loopback per Phase 10.)

- [ ] **Step 2: Run the test**

Run: `./gradlew test --tests com.myxcomp.ice.xtree.messaging.MessagingLoopbackIT`
Expected: PASS — production wiring through tasks 9–13 already supports this; the test exercises it.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/myxcomp/ice/xtree/messaging/MessagingLoopbackIT.java
git commit -m "test(copy): cover COPY round-trip via in-memory bus"
```

---

### Task 18: `ObservabilityExposureIT` — assert new metrics

**Files:**
- Modify: `src/test/java/com/myxcomp/ice/xtree/.../ObservabilityExposureIT.java` (locate via `git grep -l ObservabilityExposureIT`)

- [ ] **Step 1: Add metric assertions**

In the existing workload block (where other mutations are exercised), insert one successful and one rejected copy:
```java
// successful copy
UserContext ctx = new UserContext("deepuser", null);
itemService.copyItem(25L, 12L, ctx);

// rejected copy (cap)
try { itemService.copyItem(1L /* root */, 12L, ctx); } catch (RuntimeException ignored) {}
```

In the assertion block (where `/actuator/prometheus` is scraped):
```java
assertThat(promBody).contains("itemtree_copy_requests_total{");
assertThat(promBody).contains("result=\"success\"");
assertThat(promBody).contains("result=\"rejected\"");
assertThat(promBody).contains("itemtree_copy_rejected_total{");
assertThat(promBody).contains("itemtree_copy_subtree_size_count");
```

- [ ] **Step 2: Run the IT**

Run: `./gradlew test --tests com.myxcomp.ice.xtree.*.ObservabilityExposureIT` (use the actual fully-qualified name found by grep)
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/myxcomp/ice/xtree/  # path adjusted to where ObservabilityExposureIT lives
git commit -m "test(copy): assert itemtree.copy.* metrics on /actuator/prometheus"
```

---

### Task 19: `ItemTreeApplicationE2EIT` — `copyPropagatesAcrossInstances`

**Files:**
- Modify: `src/test/java/com/myxcomp/ice/xtree/e2e/ItemTreeApplicationE2EIT.java`

- [ ] **Step 1: Add the test**

Mirror the existing `mutationPropagatesAcrossInstances` / `cascadeDeletePropagatesSubtreeRemovalToB` shape:
```java
@Test
void copyPropagatesAcrossInstances() throws Exception {
    // Use instance A to copy a small in-DB subtree (e.g. 25 → 12)
    long sourceId = 25L;
    long destId   = 12L;
    UserContext ctx = new UserContext("deepuser", null);

    List<CachedNode> result = a.itemService.copyItem(sourceId, destId, ctx);
    assertThat(result).hasSize(1);
    long newId = result.get(0).itemTreeId();

    // Instance B converges via the shared in-memory bus
    Awaitility.await().atMost(java.time.Duration.ofSeconds(15))
            .untilAsserted(() -> assertThat(b.cache.getById(newId)).isPresent());
    assertThat(b.cache.getById(newId)).get()
            .extracting(CachedNode::parentId).isEqualTo(destId);
}
```
(Adapt to whatever helper names `TwoInstanceContexts` exposes — `a.itemService`, `b.cache`, etc. — per the Phase 13 implementation.)

- [ ] **Step 2: Run the E2E**

Run: `./gradlew test --tests com.myxcomp.ice.xtree.e2e.ItemTreeApplicationE2EIT`
Expected: PASS.

- [ ] **Step 3: Run the full build to catch any regressions**

Run: `./gradlew clean build`
Expected: BUILD SUCCESSFUL; test count should be roughly 548 → 590+ depending on exact additions.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/myxcomp/ice/xtree/e2e/ItemTreeApplicationE2EIT.java
git commit -m "test(copy): cover COPY two-instance convergence in E2E IT"
```

---

### Task 20: Update `IMPLEMENTATION_NOTES.md` with the completion stamp

**Files:**
- Modify: `IMPLEMENTATION_NOTES.md`

- [ ] **Step 1: Replace the Phase 14 stub with a "✅ COMPLETE" stamp**

In `IMPLEMENTATION_NOTES.md`, replace the `## Phase 14 — Copy Item` header line and the section body to follow the Phase 13 completion format:
```markdown
## Phase 14 — Copy Item ✅ COMPLETE (2026-05-24, tagged `phase-14-copy-item`)

**Goal achieved:** New `POST /items/{id}/copy` endpoint copies items and folder subtrees (cap 100 nodes by default) into caller-owned folders. DB-snapshot + batch INSERT + atomic cache `applyCopy` + single `COPY` event. Two-instance convergence verified.

**Deviations from plan:** [populate during execution]

**Post-completion quality fixes:** [populate during execution]

**Actual done state:** [populate during execution — e.g. 595 tests green; `./gradlew clean build` → BUILD SUCCESSFUL]
```
Leave the placeholders so the executing-plans / subagent-driven-development workflow fills them in during implementation.

- [ ] **Step 2: Run full suite for sanity**

Run: `./gradlew clean build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Tag the commit (optional, per existing phase conventions)**

```bash
git tag phase-14-copy-item
```

- [ ] **Step 4: Commit**

```bash
git add IMPLEMENTATION_NOTES.md
git commit -m "docs(phase14): mark Phase 14 complete in IMPLEMENTATION_NOTES"
```

- [ ] **Step 5: Save the phase memory**

After commit, save `/home/dave/.claude/projects/-home-dave-Git-item-tree/memory/project-phase14-copy-item-done.md` per the auto-memory protocol, and append a one-line index entry to the MEMORY.md.

---

## Final verification checklist (at end of Task 20)

- [ ] `./gradlew clean build` → BUILD SUCCESSFUL
- [ ] Total test count > 590 (was 548 at end of Phase 13)
- [ ] `curl -X POST http://localhost:8080/api/v1/itemtree/items/25/copy -H "X-Ice-User: deepuser" -H "Content-Type: application/json" -d '{"destinationFolderId": 12}'` returns `201` with a JSON array containing the new node(s) (verify against the running dev profile)
- [ ] No new compilation warnings introduced.
- [ ] No imports of `com.myxcomp.ice.xtree.generated.*` outside `api/mapper/` and `api/controller/`.
- [ ] No `LocalDateTime.now()` / `Instant.now()` calls outside `TimeMapper`.
- [ ] All `apply*` calls on cache still tolerant of weird input (verify by reading any new test additions in the cache test file).

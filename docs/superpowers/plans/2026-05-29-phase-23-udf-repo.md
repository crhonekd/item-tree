# Phase 23 — `UDFRepo` Type Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a new item type `UDFRepo` — a per-user singleton living directly in the user's home folder, named after the user, carrying a JSON payload, immutable except for data updates.

**Architecture:** A new type literal recognised by `common/Types`, enforced entirely in the service layer (`ItemService`). No new HTTP status or exception class — all rejections reuse `ValidationException` (HTTP 400) with new `ErrorCode` values rendered by the existing `ProblemFactory`. The three `itemtree.data.*` policy lists are unchanged: `UDFRepo` is a default new-format type (has-data, JSON-only), exactly like `View`/`Eval`. Out-of-service scope adds a seed row, a test-UI dropdown entry, and Cucumber acceptance scenarios.

**Tech Stack:** Java 21, Spring Boot, JUnit 5 + Mockito + AssertJ, H2 (Oracle mode), Cucumber + RestAssured (acceptance), vanilla JS static UI.

**Spec:** `docs/superpowers/specs/2026-05-29-phase-23-udf-repo-design.md`

**Branch:** `main` (work stays on the current branch per user instruction).

**Conventions (from CLAUDE.md):** AssertJ semantic assertions; `private static final String` constants for repeated literals; `@Nested`/`@ParameterizedTest` grouping; never `LocalDateTime.now()` outside `TimeMapper`; run the full suite green before each commit.

---

## File Structure

**Modified (production):**
- `src/main/java/com/myxcomp/ice/xtree/common/Types.java` — add `UDF_REPO` constant + `isUdfRepo`.
- `src/main/java/com/myxcomp/ice/xtree/service/exception/ErrorCode.java` — 3 new codes.
- `src/main/java/com/myxcomp/ice/xtree/service/ItemService.java` — create/delete/rename/move/copy logic.
- `src/main/resources/db/data.sql` — one seed `UDFRepo` row.
- `src/main/resources/static/js/modal.js` — add `UDFRepo` to the create dropdown.

**Modified (test):**
- `src/test/java/com/myxcomp/ice/xtree/common/TypesTest.java`
- `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceCreateTest.java`
- `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceDeleteTest.java`
- `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceRenameTest.java`
- `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceMoveTest.java`
- `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceCopyTest.java`

**Modified / created (acceptance):**
- `acceptance/src/test/java/com/myxcomp/ice/xtree/acceptance/support/Sandbox.java` — expose `homeId()`.
- `acceptance/src/test/java/com/myxcomp/ice/xtree/acceptance/steps/UdfRepoSteps.java` — **create**.
- `acceptance/src/test/resources/features/udf-repo.feature` — **create**.

**Modified (docs):**
- `IMPLEMENTATION_NOTES.md` — Phase 23 section.

---

## Task 1: `Types.UDF_REPO` constant and `isUdfRepo` helper

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/common/Types.java`
- Test: `src/test/java/com/myxcomp/ice/xtree/common/TypesTest.java`

- [ ] **Step 1: Add failing tests**

Append these tests inside the `TypesTest` class body:

```java
    @Test
    void udfRepoConstantIsExactLiteral() {
        assertThat(Types.UDF_REPO).isEqualTo("UDFRepo");
    }

    @Test
    void isUdfRepoTrueForExactLiteral() {
        assertThat(Types.isUdfRepo("UDFRepo")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"udfrepo", "UDFREPO", "UDF.Repo", "Folder", "Report", ""})
    void isUdfRepoFalseForAnythingElse(String type) {
        assertThat(Types.isUdfRepo(type)).isFalse();
    }
```

Ensure these imports exist at the top of `TypesTest.java` (add any that are missing):

```java
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests 'com.myxcomp.ice.xtree.common.TypesTest'`
Expected: FAIL — `Types.UDF_REPO` / `Types.isUdfRepo` do not exist (compile error).

- [ ] **Step 3: Implement**

Edit `Types.java` to:

```java
package com.myxcomp.ice.xtree.common;

public final class Types {

    public static final String FOLDER = "Folder";
    public static final String UDF_REPO = "UDFRepo";

    public static boolean isFolder(String type) {
        return FOLDER.equals(type);
    }

    public static boolean isUdfRepo(String type) {
        return UDF_REPO.equals(type);
    }

    private Types() {}
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests 'com.myxcomp.ice.xtree.common.TypesTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/common/Types.java \
        src/test/java/com/myxcomp/ice/xtree/common/TypesTest.java
git commit -m "feat(phase23): add UDFRepo type constant and isUdfRepo helper

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: New `ErrorCode` values

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/service/exception/ErrorCode.java`

No standalone test — these are enum constants consumed by Tasks 3–7. Their correctness is verified by those tasks' tests. This task only makes the codebase compile with the new references.

- [ ] **Step 1: Add the three codes**

Add to the `ErrorCode` enum (after `NOT_IN_USER_FOLDER`, keep the trailing entry comma-correct):

```java
    NOT_IN_USER_FOLDER,
    UDF_REPO_ALREADY_EXISTS,
    UDF_REPO_INVALID_PARENT,
    UDF_REPO_PROTECTED
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/service/exception/ErrorCode.java
git commit -m "feat(phase23): add UDFRepo error codes

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: `createItem` — UDFRepo placement, uniqueness, forced name

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/service/ItemService.java` (the `createItem` method, currently lines 109–171)
- Test: `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceCreateTest.java`

**Behaviour:** After parent + ownership resolution, when `Types.isUdfRepo(type)`:
1. `parentId` must equal `homeFolder.itemTreeId()`, else `UDF_REPO_INVALID_PARENT` (400).
2. No existing direct child of the home folder may be a UDFRepo, else `UDF_REPO_ALREADY_EXISTS` (400).
3. The node name is forced to `effectiveUser`.
The existing `DATA_REQUIRED` check still applies (UDFRepo `hasData == true`).

- [ ] **Step 1: Write failing tests**

Add a nested class to `ItemServiceCreateTest`. Notes on the existing fixtures: `setUp()` already stubs `ownershipChecker.requireHomeFolderExists(anyString())` to return `folder(10L, 2L, "alice-home")` (so home folder id = 10), and `CTX_DIRECT = new UserContext("alice", null)`. The `folder(id, parentId, name)` helper exists.

```java
    @Nested
    class UdfRepo {

        @Test
        void happyPathForcesNameToUserAndPersists() {
            // parent IS the home folder (id 10)
            when(cache.getById(10L)).thenReturn(Optional.of(folder(10L, 2L, "alice-home")));
            when(cache.getChildren(10L)).thenReturn(java.util.List.of());
            when(policy.hasData("UDFRepo")).thenReturn(true);
            when(policy.isAlsoPersistedAsXmlOnWrite("UDFRepo")).thenReturn(false);
            when(timeMapper.now()).thenReturn(NOW);
            when(repository.insert(eq(10L), eq("alice"), eq("UDFRepo"),
                    eq("{\"k\":1}"), eq(null), eq(NOW), eq("alice"))).thenReturn(900L);
            when(instanceIdProvider.getInstanceId()).thenReturn("inst-1");
            when(sequenceGenerator.next()).thenReturn(1L);

            // client sends a bogus name; server must override it to "alice"
            CachedNode created = service.createItem(10L, "ignored-name", "UDFRepo", "{\"k\":1}", CTX_DIRECT);

            assertThat(created.name()).isEqualTo("alice");
            assertThat(created.type()).isEqualTo("UDFRepo");
            assertThat(created.parentId()).isEqualTo(10L);
            verify(repository).insert(10L, "alice", "UDFRepo", "{\"k\":1}", null, NOW, "alice");
        }

        @Test
        void rejectsSecondUdfRepoForSameUser() {
            when(cache.getById(10L)).thenReturn(Optional.of(folder(10L, 2L, "alice-home")));
            when(cache.getChildren(10L)).thenReturn(java.util.List.of(
                    new CachedNode(901L, 10L, "alice", "UDFRepo", NOW, "alice")));

            assertThatThrownBy(() ->
                    service.createItem(10L, "alice", "UDFRepo", "{\"k\":1}", CTX_DIRECT))
                    .isInstanceOf(ValidationException.class)
                    .extracting(e -> ((ValidationException) e).errorCode())
                    .isEqualTo(ErrorCode.UDF_REPO_ALREADY_EXISTS);

            verify(repository, never()).insert(anyLong(), anyString(), anyString(),
                    any(), any(), any(), anyString());
        }

        @Test
        void rejectsUdfRepoNotDirectlyUnderHomeFolder() {
            // parent 20 is owned by the user (a sub-folder) but is not the home folder itself.
            when(cache.getById(20L)).thenReturn(Optional.of(folder(20L, 10L, "sub")));

            assertThatThrownBy(() ->
                    service.createItem(20L, "alice", "UDFRepo", "{\"k\":1}", CTX_DIRECT))
                    .isInstanceOf(ValidationException.class)
                    .extracting(e -> ((ValidationException) e).errorCode())
                    .isEqualTo(ErrorCode.UDF_REPO_INVALID_PARENT);

            verify(repository, never()).insert(anyLong(), anyString(), anyString(),
                    any(), any(), any(), anyString());
        }

        @Test
        void rejectsUdfRepoWithoutData() {
            when(cache.getById(10L)).thenReturn(Optional.of(folder(10L, 2L, "alice-home")));
            when(cache.getChildren(10L)).thenReturn(java.util.List.of());
            when(policy.hasData("UDFRepo")).thenReturn(true);

            assertThatThrownBy(() ->
                    service.createItem(10L, "alice", "UDFRepo", null, CTX_DIRECT))
                    .isInstanceOf(ValidationException.class)
                    .extracting(e -> ((ValidationException) e).errorCode())
                    .isEqualTo(ErrorCode.DATA_REQUIRED);
        }
    }
```

> If `ItemServiceCreateTest` doesn't already statically import `ErrorCode` / `never` / `anyLong` / `any` / `anyString`, they are already present per the file header — no new imports needed.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests 'com.myxcomp.ice.xtree.service.ItemServiceCreateTest$UdfRepo'`
Expected: FAIL — UDFRepo logic not yet present (e.g. name not forced; no rejection).

- [ ] **Step 3: Implement**

In `ItemService.createItem`, locate this existing block:

```java
        String effectiveUser = userContext.effectiveUser();
        CachedNode homeFolder = ownershipChecker.requireHomeFolderExists(effectiveUser);
        ownershipChecker.requireOwned(parentId, homeFolder, effectiveUser, "Parent");

        boolean hasData = policy.hasData(type);
```

Insert the UDFRepo block between the `requireOwned(...)` line and `boolean hasData = ...`:

```java
        String effectiveUser = userContext.effectiveUser();
        CachedNode homeFolder = ownershipChecker.requireHomeFolderExists(effectiveUser);
        ownershipChecker.requireOwned(parentId, homeFolder, effectiveUser, "Parent");

        if (Types.isUdfRepo(type)) {
            if (parentId != homeFolder.itemTreeId()) {
                throw new ValidationException(ErrorCode.UDF_REPO_INVALID_PARENT,
                        "A UDFRepo must be created directly under the user's home folder");
            }
            for (CachedNode child : cache.getChildren(homeFolder.itemTreeId())) {
                if (Types.isUdfRepo(child.type())) {
                    throw new ValidationException(ErrorCode.UDF_REPO_ALREADY_EXISTS,
                            "A UDFRepo already exists for user '" + effectiveUser + "'");
                }
            }
            name = effectiveUser;
        }

        boolean hasData = policy.hasData(type);
```

(`name` is a method parameter and is freely reassignable; all downstream code — `repository.insert(...)` and the `CachedNode` construction — already reads `name`.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests 'com.myxcomp.ice.xtree.service.ItemServiceCreateTest'`
Expected: PASS (new nested class + all pre-existing create tests still green).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/service/ItemService.java \
        src/test/java/com/myxcomp/ice/xtree/service/ItemServiceCreateTest.java
git commit -m "feat(phase23): enforce UDFRepo placement, uniqueness, forced name on create

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 4: `deleteItem` — block deleting a UDFRepo

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/service/ItemService.java` (`deleteItem`, lines 178–205)
- Test: `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceDeleteTest.java`

**Behaviour:** Reject only when the *directly targeted* node is a UDFRepo. Cascade through an ancestor is intentionally not intercepted.

- [ ] **Step 1: Write failing tests**

Add to `ItemServiceDeleteTest` (mirror that file's existing `@Mock` set / `setUp()` / `UserContext`; use whatever its existing helper for building a node is — if none, construct `new CachedNode(...)` inline). `ownershipChecker.requireHomeFolderExists` and `requireOwned` are mocks and are no-ops by default.

```java
    @Test
    void deletingUdfRepoIsRejected() {
        long id = 901L;
        when(cache.getById(id)).thenReturn(Optional.of(
                new CachedNode(id, 10L, "alice", "UDFRepo",
                        Instant.parse("2026-05-29T00:00:00Z"), "alice")));

        assertThatThrownBy(() -> service.deleteItem(id, new UserContext("alice", null)))
                .isInstanceOf(ValidationException.class)
                .extracting(e -> ((ValidationException) e).errorCode())
                .isEqualTo(ErrorCode.UDF_REPO_PROTECTED);

        verify(repository, never()).cascadeDeleteSubtree(anyLong());
    }
```

Confirm these statics are imported in the file (add any missing):

```java
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.myxcomp.ice.xtree.service.exception.ValidationException;
import com.myxcomp.ice.xtree.service.exception.ErrorCode;
import java.time.Instant;
import java.util.Optional;
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.myxcomp.ice.xtree.service.ItemServiceDeleteTest'`
Expected: FAIL — delete currently proceeds and calls `cascadeDeleteSubtree`.

- [ ] **Step 3: Implement**

In `deleteItem`, replace the opening probe + ownership block:

```java
        if (cache.getById(id).isEmpty()) {
            log.info("deleteItem: id={} not present in cache; no-op", id);
            return;
        }

        String effectiveUser = userContext.effectiveUser();
        CachedNode homeFolder = ownershipChecker.requireHomeFolderExists(effectiveUser);
        ownershipChecker.requireOwned(id, homeFolder, effectiveUser, "Item");

        List<Long> deletedIds = repository.cascadeDeleteSubtree(id);
```

with:

```java
        Optional<CachedNode> target = cache.getById(id);
        if (target.isEmpty()) {
            log.info("deleteItem: id={} not present in cache; no-op", id);
            return;
        }

        String effectiveUser = userContext.effectiveUser();
        CachedNode homeFolder = ownershipChecker.requireHomeFolderExists(effectiveUser);
        ownershipChecker.requireOwned(id, homeFolder, effectiveUser, "Item");

        if (Types.isUdfRepo(target.get().type())) {
            throw new ValidationException(ErrorCode.UDF_REPO_PROTECTED,
                    "UDFRepo " + id + " cannot be deleted");
        }

        List<Long> deletedIds = repository.cascadeDeleteSubtree(id);
```

(`java.util.Optional` is already imported in `ItemService`.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests 'com.myxcomp.ice.xtree.service.ItemServiceDeleteTest'`
Expected: PASS (new test + all pre-existing delete tests green).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/service/ItemService.java \
        src/test/java/com/myxcomp/ice/xtree/service/ItemServiceDeleteTest.java
git commit -m "feat(phase23): block direct deletion of a UDFRepo

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 5: `renameItem` — block renaming a UDFRepo

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/service/ItemService.java` (`renameItem`, lines 212–240)
- Test: `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceRenameTest.java`

- [ ] **Step 1: Write failing test**

Add to `ItemServiceRenameTest` (mirror existing fixtures):

```java
    @Test
    void renamingUdfRepoIsRejected() {
        long id = 901L;
        when(cache.getById(id)).thenReturn(Optional.of(
                new CachedNode(id, 10L, "alice", "UDFRepo",
                        Instant.parse("2026-05-29T00:00:00Z"), "alice")));

        assertThatThrownBy(() -> service.renameItem(id, "hacked", new UserContext("alice", null)))
                .isInstanceOf(ValidationException.class)
                .extracting(e -> ((ValidationException) e).errorCode())
                .isEqualTo(ErrorCode.UDF_REPO_PROTECTED);

        verify(repository, never()).updateName(anyLong(), anyString(), any(), anyString());
    }
```

Add any missing imports (same set as Task 4, plus `org.mockito.ArgumentMatchers.any`, `anyString`).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.myxcomp.ice.xtree.service.ItemServiceRenameTest'`
Expected: FAIL — rename proceeds and calls `updateName`.

- [ ] **Step 3: Implement**

In `renameItem`, replace:

```java
        if (cache.getById(id).isEmpty()) {
            throw new NotFoundException(ErrorCode.ITEM_NOT_FOUND, "Item " + id + " not found");
        }

        String effectiveUser = userContext.effectiveUser();
        CachedNode homeFolder = ownershipChecker.requireHomeFolderExists(effectiveUser);
        ownershipChecker.requireOwned(id, homeFolder, effectiveUser, "Item");

        Instant now = timeMapper.now();
```

with:

```java
        CachedNode existing = cache.getById(id).orElseThrow(() -> new NotFoundException(
                ErrorCode.ITEM_NOT_FOUND, "Item " + id + " not found"));

        String effectiveUser = userContext.effectiveUser();
        CachedNode homeFolder = ownershipChecker.requireHomeFolderExists(effectiveUser);
        ownershipChecker.requireOwned(id, homeFolder, effectiveUser, "Item");

        if (Types.isUdfRepo(existing.type())) {
            throw new ValidationException(ErrorCode.UDF_REPO_PROTECTED,
                    "UDFRepo " + id + " cannot be renamed");
        }

        Instant now = timeMapper.now();
```

(`ValidationException` is already imported in `ItemService`.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests 'com.myxcomp.ice.xtree.service.ItemServiceRenameTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/service/ItemService.java \
        src/test/java/com/myxcomp/ice/xtree/service/ItemServiceRenameTest.java
git commit -m "feat(phase23): block renaming a UDFRepo

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 6: `moveItem` — block moving a UDFRepo

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/service/ItemService.java` (`moveItem`, lines 246–292)
- Test: `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceMoveTest.java`

- [ ] **Step 1: Write failing test**

Add to `ItemServiceMoveTest` (mirror existing fixtures). The moved source is a UDFRepo (id 901, under home folder 10); destination is a sibling folder 20.

```java
    @Test
    void movingUdfRepoIsRejected() {
        long id = 901L;
        long newParentId = 20L;
        when(cache.getById(id)).thenReturn(Optional.of(
                new CachedNode(id, 10L, "alice", "UDFRepo",
                        Instant.parse("2026-05-29T00:00:00Z"), "alice")));
        when(cache.getById(newParentId)).thenReturn(Optional.of(
                new CachedNode(newParentId, 10L, "sub", "Folder",
                        Instant.parse("2026-05-29T00:00:00Z"), "alice")));
        when(cache.isAncestor(id, newParentId)).thenReturn(false);

        assertThatThrownBy(() -> service.moveItem(id, newParentId, new UserContext("alice", null)))
                .isInstanceOf(ValidationException.class)
                .extracting(e -> ((ValidationException) e).errorCode())
                .isEqualTo(ErrorCode.UDF_REPO_PROTECTED);

        verify(repository, never()).updateParent(anyLong(), anyLong(), any(), anyString());
    }
```

Add any missing imports (`anyLong`, `any`, `anyString`, `never`, `verify`, `when`, `assertThatThrownBy`, `Optional`, `Instant`, `ValidationException`, `ErrorCode`).

> The move validation order is: ITEM_NOT_FOUND → self-move → NEW_PARENT_NOT_FOUND → NEW_PARENT_NOT_FOLDER → ancestor walk → ownership → **UDFRepo guard**. The stubs above satisfy every prior gate so the test reaches the new guard.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.myxcomp.ice.xtree.service.ItemServiceMoveTest'`
Expected: FAIL — move proceeds and calls `updateParent`.

- [ ] **Step 3: Implement**

In `moveItem`, locate the ownership block near the end:

```java
        String effectiveUser = userContext.effectiveUser();
        CachedNode homeFolder = ownershipChecker.requireHomeFolderExists(effectiveUser);
        ownershipChecker.requireOwned(id, homeFolder, effectiveUser, "Source");
        ownershipChecker.requireOwned(newParentId, homeFolder, effectiveUser, "New parent");

        Instant now = timeMapper.now();
```

Insert the guard after the two `requireOwned` calls:

```java
        String effectiveUser = userContext.effectiveUser();
        CachedNode homeFolder = ownershipChecker.requireHomeFolderExists(effectiveUser);
        ownershipChecker.requireOwned(id, homeFolder, effectiveUser, "Source");
        ownershipChecker.requireOwned(newParentId, homeFolder, effectiveUser, "New parent");

        if (Types.isUdfRepo(item.type())) {
            throw new ValidationException(ErrorCode.UDF_REPO_PROTECTED,
                    "UDFRepo " + id + " cannot be moved");
        }

        Instant now = timeMapper.now();
```

(`item` is the source `CachedNode` resolved at the top of `moveItem`.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests 'com.myxcomp.ice.xtree.service.ItemServiceMoveTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/service/ItemService.java \
        src/test/java/com/myxcomp/ice/xtree/service/ItemServiceMoveTest.java
git commit -m "feat(phase23): block moving a UDFRepo

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 7: `copyItem` — reject direct copy; skip UDFRepo nodes in a subtree

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/service/ItemService.java` (`copyItem`, lines 510–643)
- Test: `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceCopyTest.java`

**Behaviour:**
- Direct copy where the source is a UDFRepo → reject `UDF_REPO_PROTECTED` (after the `CANNOT_COPY_ROOT` guard), recording a copy-rejection metric.
- Subtree copy → filter out any UDFRepo rows before id allocation. Safe: UDFRepo is always a leaf, and the subtree root is never a UDFRepo (rejected above).

- [ ] **Step 1: Write failing tests**

Add a nested class to `ItemServiceCopyTest`. Mirror the existing `CopyItem` setup: `ctx = new UserContext("alice", null)`, time/instance/sequence stubbed leniently.

```java
    @Nested
    class UdfRepoCopy {

        private final Instant T = Instant.parse("2026-05-29T10:00:00Z");
        private final UserContext ctx = new UserContext("alice", null);

        @BeforeEach
        void setup() {
            lenient().when(timeMapper.now()).thenReturn(T);
            lenient().when(instanceIdProvider.getInstanceId()).thenReturn("inst-1");
            lenient().when(sequenceGenerator.next()).thenReturn(1L);
        }

        @Test
        void rejectsDirectCopyOfUdfRepo() {
            long sourceId = 901L;
            long destId = 20L;
            CachedNode source = new CachedNode(sourceId, 10L, "alice", "UDFRepo", T, "alice");
            CachedNode dest = new CachedNode(destId, 10L, "sub", "Folder", T, "alice");
            when(cache.getById(sourceId)).thenReturn(Optional.of(source));
            when(cache.getById(destId)).thenReturn(Optional.of(dest));
            when(ownershipChecker.requireHomeFolderExists("alice"))
                    .thenReturn(new CachedNode(10L, 2L, "alice", "Folder", T, "alice"));

            assertThatThrownBy(() -> service.copyItem(sourceId, destId, ctx))
                    .isInstanceOf(ValidationException.class)
                    .extracting(e -> ((ValidationException) e).errorCode())
                    .isEqualTo(ErrorCode.UDF_REPO_PROTECTED);

            verify(repository, never()).insertBatch(any());
        }

        @Test
        void skipsUdfRepoNodesWhenCopyingASubtree() {
            long sourceId = 70L;   // a folder
            long destId = 20L;
            CachedNode sourceFolder = new CachedNode(sourceId, 10L, "MixedFolder", "Folder", T, "alice");
            CachedNode dest = new CachedNode(destId, 10L, "sub", "Folder", T, "alice");
            when(cache.getById(sourceId)).thenReturn(Optional.of(sourceFolder));
            when(cache.getById(destId)).thenReturn(Optional.of(dest));
            when(ownershipChecker.requireHomeFolderExists("alice"))
                    .thenReturn(new CachedNode(10L, 2L, "alice", "Folder", T, "alice"));
            when(cache.isAncestor(sourceId, destId)).thenReturn(false);
            when(cache.getChildren(destId)).thenReturn(java.util.List.of());
            when(cache.getSubtreeFlatFull(sourceId)).thenReturn(java.util.List.of(sourceFolder));
            // DB snapshot: folder + a normal child + a UDFRepo child (which must be skipped)
            when(repository.findRowsForCopy(eq(sourceId), anyInt())).thenReturn(java.util.List.of(
                    new ItemTreeFullRow(sourceId, 10L, "MixedFolder", "Folder", null, null, T, "alice"),
                    new ItemTreeFullRow(72L, sourceId, "MixedLeaf", "View", "{\"a\":1}", null, T, "alice"),
                    new ItemTreeFullRow(901L, sourceId, "alice", "UDFRepo", "{\"k\":1}", null, T, "alice")));
            when(repository.allocateIds(2)).thenReturn(java.util.List.of(801L, 802L));

            List<CachedNode> result = service.copyItem(sourceId, destId, ctx);

            // UDFRepo excluded: 2 nodes copied, none of type UDFRepo
            assertThat(result).hasSize(2);
            assertThat(result).noneMatch(n -> "UDFRepo".equals(n.type()));

            ArgumentCaptor<List<ItemTreeFullRow>> captor = ArgumentCaptor.captor();
            verify(repository).insertBatch(captor.capture());
            assertThat(captor.getValue())
                    .hasSize(2)
                    .noneMatch(r -> "UDFRepo".equals(r.type()));
        }
    }
```

Confirm `ItemServiceCopyTest` imports (most already present): `assertThat`, `assertThatThrownBy`, `when`, `lenient`, `verify`, `never`, `any`, `anyInt`, `eq`, `ArgumentCaptor`, `Optional`, `Instant`, `List`, `ItemTreeFullRow`, `ValidationException`, `ErrorCode`, `CachedNode`, `UserContext`, `@Nested`, `@BeforeEach`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests 'com.myxcomp.ice.xtree.service.ItemServiceCopyTest$UdfRepoCopy'`
Expected: FAIL — direct copy not rejected; UDFRepo row not skipped (allocateIds(2) stub unused → copy tries allocateIds(3)).

- [ ] **Step 3: Implement**

**(a) Direct-copy guard.** In `copyItem`, after the `CANNOT_COPY_ROOT` block (step 2) and before the `DESTINATION_NOT_FOUND` block (step 3), insert:

```java
        // 2b. UDF_REPO_PROTECTED — a UDFRepo may never be copied directly
        if (Types.isUdfRepo(source.type())) {
            recordCopyRejection(ErrorCode.UDF_REPO_PROTECTED);
            throw new ValidationException(ErrorCode.UDF_REPO_PROTECTED,
                    "UDFRepo " + sourceId + " cannot be copied");
        }
```

**(b) Subtree filter.** Locate the DB snapshot block:

```java
        // 8b. DB snapshot — authoritative
        List<ItemTreeFullRow> sourceRows = repository.findRowsForCopy(sourceId, cap + 1);
        if (sourceRows.isEmpty()) {
            recordCopyRejection(ErrorCode.ITEM_NOT_FOUND);
            throw new NotFoundException(ErrorCode.ITEM_NOT_FOUND,
                    "Item " + sourceId + " not found in DB");
        }
        if (sourceRows.size() > cap) {
            recordCopyRejection(ErrorCode.COPY_TOO_LARGE);
            throw new CopyTooLargeException(
                    "Source subtree has more than " + cap + " nodes (DB)");
        }
```

Immediately after that block (before `// Allocate ids and build oldId→newId map`), insert:

```java
        // Phase 23: a UDFRepo is a per-user singleton and is never duplicated by copy.
        // It is always a leaf, so dropping it can never orphan a child. The subtree
        // root is guaranteed not to be a UDFRepo (rejected at step 2b above).
        sourceRows = sourceRows.stream()
                .filter(r -> !Types.isUdfRepo(r.type()))
                .toList();
```

(`sourceRows` is a local variable and is reassignable. `java.util.stream` collectors are available; `List.toList()` returns an unmodifiable list, which the downstream indexed loop only reads.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests 'com.myxcomp.ice.xtree.service.ItemServiceCopyTest'`
Expected: PASS (new nested class + all pre-existing copy tests green).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/service/ItemService.java \
        src/test/java/com/myxcomp/ice/xtree/service/ItemServiceCopyTest.java
git commit -m "feat(phase23): reject direct UDFRepo copy and skip UDFRepo in subtree copies

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 8: Seed data — a pre-existing UDFRepo for testuser1

**Files:**
- Modify: `src/main/resources/db/data.sql`

**Why:** Exercises JSON-only read shaping and gives the acceptance suite a pre-existing UDFRepo for the duplicate / delete-reject paths when the configured user is `testuser1`. Id `15` is free (home folders use 10–13; depth chain uses 20–25).

- [ ] **Step 1: Add the row**

After the home-folders block (the `INSERT ... VALUES (10,...),(11,...),(12,...),(13,...)` ending at line 36), add a new section:

```sql
-- ── 3b. Per-user UDFRepo singleton (Phase 23) — JSON-only, name == username ──
INSERT INTO ITEMTREE (ITEMTREEID, PARENTID, NAME, TYPE, XML, LASTUPDATEUSER, LASTUPDATE, JSON) VALUES
  (15, 10, 'testuser1', 'UDFRepo', NULL, 'system', TIMESTAMP '2026-05-01 10:00:00', '{"udfs":[]}');
```

Also extend the file header comment block (the bullet list around lines 6–11) by adding a bullet:

```
--   * one UDFRepo under testuser1 (per-user singleton, Phase 23)
```

- [ ] **Step 2: Verify the app + full suite still boot/pass against the seeded schema**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL — all 705 main-module tests pass (the E2E and repository tests load this `data.sql`; the new row must not break tree assembly, home-folder resolution, or counts).

> If any existing test hard-codes the exact child count of `testuser1` (id 10) or total row count, update that expectation to include the new row. Search before assuming: `grep -rn "getChildren(10\|itemTreeId.*10\b" src/test` and the E2E node-count assertions.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/db/data.sql
git commit -m "feat(phase23): seed a UDFRepo singleton for testuser1

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 9: Test UI — offer `UDFRepo` in the create dropdown

**Files:**
- Modify: `src/main/resources/static/js/modal.js`

**Why:** The static UI has no build step and no JS test harness, so this is a manual-verification task. Adding `UDFRepo` to `KNOWN_TYPES` makes it selectable in the "Create child" modal; the server forces the name and enforces placement/uniqueness, and `toastError` already surfaces the `Problem` body on rejection. `UDFRepo` is data-bearing, so it must NOT be added to `TYPES_WITHOUT_DATA` — leaving the JSON `data` textarea enabled.

- [ ] **Step 1: Add `UDFRepo` to `KNOWN_TYPES`**

In `modal.js`, the `KNOWN_TYPES` array currently ends with `'View', 'UDF.Context', 'Eval',` (around lines 6–11). Add `'UDFRepo'` to the array, e.g.:

```js
  'Bucket.Collection', 'View', 'UDF.Context', 'Eval', 'UDFRepo',
```

Do **not** add it to `TYPES_WITHOUT_DATA`.

- [ ] **Step 2: Manual verification**

Run the app: `./gradlew bootRun` (dev profile, H2). In a browser at the app root:
1. Log in as `testuser1`.
2. Right-click the `testuser1` home folder → "Create child". Confirm `UDFRepo` appears in the Type dropdown and the Data textarea is enabled when it's selected.
3. Selecting `UDFRepo`, entering JSON `{"udfs":[]}`, and clicking Create → expect a toast error `UDF_REPO_ALREADY_EXISTS` (testuser1 already has the seeded repo from Task 8). This confirms end-to-end wiring.
Stop the app afterwards.

Expected: dropdown shows `UDFRepo`; duplicate create surfaces the `UDF_REPO_ALREADY_EXISTS` problem toast.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/js/modal.js
git commit -m "feat(phase23): offer UDFRepo in the test-UI create dropdown

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 10: Acceptance scenarios

**Files:**
- Modify: `acceptance/src/test/java/com/myxcomp/ice/xtree/acceptance/support/Sandbox.java`
- Create: `acceptance/src/test/java/com/myxcomp/ice/xtree/acceptance/steps/UdfRepoSteps.java`
- Create: `acceptance/src/test/resources/features/udf-repo.feature`

**Design constraint:** A UDFRepo cannot be deleted and must live directly in the home folder, so it cannot use the self-cleaning sandbox folder and cannot be torn down. The scenarios are therefore written to be **idempotent across reruns**: a `Given my UDFRepo exists` step creates-if-absent (tolerating `UDF_REPO_ALREADY_EXISTS`) and resolves the repo id. The created UDFRepo is the user's legitimate singleton — leaving it in place is correct product behaviour, not test pollution — and is deliberately **not** added to `world.created` (which would make the `@After` hook attempt an impossible delete).

- [ ] **Step 1: Expose the home folder id from `Sandbox`**

`Sandbox.resolveHomeFolder()` already fetches the home folder id but discards it after creating the sandbox. Capture it in a field and expose it.

Add a field next to the existing static fields:

```java
    private static ApiClient api;
    private static long rootId;
    private static long homeId;
    private static boolean ready;
```

In `init()`, store the resolved id:

```java
    public static synchronized void init() {
        if (ready) {
            return;
        }
        api = new ApiClient(TestConfig.baseUrl(), TestConfig.user());
        awaitReadiness();
        homeId = resolveHomeFolder();
        rootId = createSandboxFolder(homeId);
        ready = true;
    }
```

Add an accessor next to `rootId()`:

```java
    public static long homeId() {
        if (!ready) throw new IllegalStateException("Sandbox.init() has not been called");
        return homeId;
    }
```

- [ ] **Step 2: Create the step definitions**

Create `acceptance/src/test/java/com/myxcomp/ice/xtree/acceptance/steps/UdfRepoSteps.java`:

```java
package com.myxcomp.ice.xtree.acceptance.steps;

import com.myxcomp.ice.xtree.acceptance.support.Sandbox;
import com.myxcomp.ice.xtree.acceptance.support.World;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.When;
import io.restassured.response.Response;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class UdfRepoSteps {

    private static final String TYPE_UDF_REPO        = "UDFRepo";
    private static final String FIELD_PARENT_ID      = "parentId";
    private static final String FIELD_NAME           = "name";
    private static final String FIELD_TYPE           = "type";
    private static final String FIELD_DATA           = "data";
    private static final String FIELD_NEW_NAME       = "newName";
    private static final String FIELD_NEW_PARENT_ID  = "newParentId";
    private static final String FIELD_ITEM_TREE_ID   = "itemTreeId";
    private static final String FIELD_ERROR_CODE     = "errorCode";
    private static final String ERR_ALREADY_EXISTS   = "UDF_REPO_ALREADY_EXISTS";

    private final World world;

    public UdfRepoSteps(World world) {
        this.world = world;
    }

    @Given("my UDFRepo exists")
    public void myUdfRepoExists() {
        long homeId = Sandbox.homeId();
        Response r = world.api.post("/items", createBody(homeId));
        long id;
        if (r.statusCode() == 201) {
            id = r.jsonPath().getLong(FIELD_ITEM_TREE_ID);
        } else {
            assertThat(r.statusCode())
                    .as("create UDFRepo: body %s", r.getBody().asString())
                    .isEqualTo(400);
            assertThat(r.jsonPath().getString(FIELD_ERROR_CODE)).isEqualTo(ERR_ALREADY_EXISTS);
            id = findUdfRepoUnder(homeId);
        }
        // Deliberately NOT added to world.created: a UDFRepo cannot be deleted.
        world.currentItemId = id;
    }

    @When("I create another UDFRepo")
    public void iCreateAnotherUdfRepo() {
        world.lastResponse = world.api.post("/items", createBody(Sandbox.homeId()));
    }

    @When("I try to delete my UDFRepo")
    public void iTryToDeleteMyUdfRepo() {
        world.lastResponse = world.api.delete("/items/" + world.currentItemId);
    }

    @When("I try to rename my UDFRepo to {string}")
    public void iTryToRenameMyUdfRepo(String newName) {
        world.lastResponse = world.api.post("/items/" + world.currentItemId + "/rename",
                Map.of(FIELD_NEW_NAME, newName));
    }

    @When("I try to move my UDFRepo into the sandbox")
    public void iTryToMoveMyUdfRepoIntoTheSandbox() {
        world.lastResponse = world.api.post("/items/" + world.currentItemId + "/move",
                Map.of(FIELD_NEW_PARENT_ID, world.sandboxRootId));
    }

    private Map<String, Object> createBody(long homeId) {
        Map<String, Object> body = new HashMap<>();
        body.put(FIELD_PARENT_ID, homeId);
        body.put(FIELD_NAME, world.api.user());          // server overrides this anyway
        body.put(FIELD_TYPE, TYPE_UDF_REPO);
        body.put(FIELD_DATA, Map.of("udfs", java.util.List.of()));
        return body;
    }

    private long findUdfRepoUnder(long homeId) {
        Response r = world.api.get("/tree/" + homeId + "/subtree");
        assertThat(r.statusCode()).isEqualTo(200);
        Long id = r.jsonPath().getLong("find { it.type == '" + TYPE_UDF_REPO + "' }." + FIELD_ITEM_TREE_ID);
        assertThat(id).as("no UDFRepo found directly under home folder %s", homeId).isNotNull();
        return id;
    }
}
```

> Step phrasings `the response status is {int}` and `the error code is {string}` are already defined in `ErrorSteps`. The `/move` and `/rename` paths and field names mirror `ItemSteps`. `world.api.user()` returns the configured `X-Ice-User`.

- [ ] **Step 3: Create the feature file**

Create `acceptance/src/test/resources/features/udf-repo.feature`:

```gherkin
Feature: UDFRepo per-user singleton

  A UDFRepo lives directly in the user's home folder, is named after the user,
  and is unique per user. It can never be duplicated, renamed, moved, or deleted —
  only its data may be updated.

  Scenario: a user's UDFRepo cannot be duplicated, renamed, moved, or deleted
    Given my UDFRepo exists
    When I create another UDFRepo
    Then the response status is 400
    And the error code is "UDF_REPO_ALREADY_EXISTS"
    When I try to delete my UDFRepo
    Then the response status is 400
    And the error code is "UDF_REPO_PROTECTED"
    When I try to rename my UDFRepo to "hacked"
    Then the response status is 400
    And the error code is "UDF_REPO_PROTECTED"
    When I try to move my UDFRepo into the sandbox
    Then the response status is 400
    And the error code is "UDF_REPO_PROTECTED"
```

- [ ] **Step 4: Run the acceptance suite against a live instance**

In one terminal, start the app: `./gradlew bootRun` (dev profile; seeds testuser1 with a UDFRepo from Task 8). The acceptance suite defaults its user to the configured `TestConfig.user()` — confirm it is `testuser1` (the seeded user) for this run, or that the run user has a resolvable home folder.

In another terminal: `./gradlew :acceptance:test`
Expected: all scenarios pass, including the existing suite plus `udf-repo.feature`. Stop `bootRun` afterwards.

> If `:acceptance` is not wired into the root `test` task (it is a standalone module per Phase 22), run it explicitly as above.

- [ ] **Step 5: Commit**

```bash
git add acceptance/src/test/java/com/myxcomp/ice/xtree/acceptance/support/Sandbox.java \
        acceptance/src/test/java/com/myxcomp/ice/xtree/acceptance/steps/UdfRepoSteps.java \
        acceptance/src/test/resources/features/udf-repo.feature
git commit -m "test(phase23): acceptance scenarios for UDFRepo singleton rules

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 11: Documentation + final full-suite verification

**Files:**
- Modify: `IMPLEMENTATION_NOTES.md`

- [ ] **Step 1: Add the Phase 23 section**

Append a section to `IMPLEMENTATION_NOTES.md` following the format of the existing phase entries (find the last phase section and add after it):

```markdown
## Phase 23 — UDFRepo type ✅ COMPLETE (2026-05-29)

**Goal:** A per-user singleton item type `UDFRepo`, living directly in the user's
home folder, named after the user, JSON-payload-bearing, immutable except for data
updates.

- New type literal `UDFRepo` (`Types.UDF_REPO` + `Types.isUdfRepo`). Default
  new-format policy (has-data, JSON-only) — not added to any `itemtree.data.*` list.
- `createItem`: when type is `UDFRepo`, the parent must be the caller's home folder
  (`UDF_REPO_INVALID_PARENT`), no existing UDFRepo may sit directly under it
  (`UDF_REPO_ALREADY_EXISTS`), and the name is forced to the effective username.
- `deleteItem` / `renameItem` / `moveItem`: reject when the targeted node is a
  UDFRepo (`UDF_REPO_PROTECTED`). Cascade delete via an ancestor is not intercepted.
- `copyItem`: rejects a direct copy of a UDFRepo and silently skips UDFRepo nodes
  inside a copied subtree (UDFRepo is always a leaf).
- All rejections are HTTP 400 via the existing `ValidationException` path.
- Seed data: a UDFRepo for `testuser1`. Test UI: `UDFRepo` added to the create
  dropdown. Acceptance: `udf-repo.feature` (idempotent — the singleton is not torn down).

**Done when:** full main-module suite green; `:acceptance` green against a live
instance seeded for the configured user.
```

- [ ] **Step 2: Run the entire main-module suite**

Run: `./gradlew clean test`
Expected: BUILD SUCCESSFUL, zero failures (705 prior + the UDFRepo additions across Types/Create/Delete/Rename/Move/Copy tests).

- [ ] **Step 3: Commit**

```bash
git add IMPLEMENTATION_NOTES.md
git commit -m "docs(phase23): implementation notes for the UDFRepo type

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

- [ ] **Step 4: Update auto-memory**

Add a one-line pointer to `MEMORY.md` and a `project-phase23-udf-repo-done.md` memory file recording: Phase 23 done 2026-05-29; UDFRepo per-user singleton (placement/uniqueness/forced-name on create; delete/rename/move/direct-copy blocked; subtree copy skips it); new ErrorCodes; seed row id 15; acceptance `udf-repo.feature`. (This is a memory write, not a git commit.)

---

## Self-Review

**Spec coverage:**
- Invariant 1 (one per user, in home folder) → Task 3 (uniqueness + placement). ✓
- Invariant 2 (reject duplicate with meaningful error) → Task 3 `UDF_REPO_ALREADY_EXISTS`. ✓
- Invariant 3 (name == username) → Task 3 forced name; Task 5/6 block rename/move that would break it. ✓
- Invariant 4 (no delete, only update) → Task 4 (delete), Task 5 (rename), Task 6 (move); update path unchanged. ✓
- Invariant 5 (must have JSON payload) → Task 1/3: has-data default policy ⇒ `DATA_REQUIRED` enforced. ✓
- Copy decision (skip in subtree, reject direct) → Task 7. ✓
- Cascade decision (direct-delete only) → Task 4 (guard on the targeted node only). ✓
- Extra scope: seed row → Task 8; test UI → Task 9; acceptance → Task 10. ✓

**Placeholder scan:** No TBD/TODO; every code step shows full code and exact commands. ✓

**Type consistency:** `Types.UDF_REPO` / `Types.isUdfRepo`, `ErrorCode.UDF_REPO_ALREADY_EXISTS` / `UDF_REPO_INVALID_PARENT` / `UDF_REPO_PROTECTED`, `ValidationException(errorCode, message)`, `CachedNode(id, parentId, name, type, instant, user)`, `ItemTreeFullRow(id, parentId, name, type, json, xml, instant, user)`, `Sandbox.homeId()` used consistently across tasks. ✓

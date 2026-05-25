# Phase 16 — User-folder ownership enforcement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enforce, server-side, that an authenticated user can only mutate items inside their own home-folder subtree, across all 6 mutation endpoints (create, delete, rename, move, update-data, copy). Violations return HTTP 403 with `errorCode = NOT_IN_USER_FOLDER`.

**Architecture:** Introduce one `service/OwnershipChecker` `@Component` (constructor-injects `TreeCache`) with two methods — `requireHomeFolderExists` and `requireOwned`. Inject into `ItemService`; each mutation calls the checker at the appropriate step in its validation order. New `ForbiddenException` (HTTP 403) added to the exception model. Existing `DESTINATION_NOT_IN_USER_FOLDER` (400/Validation) is removed and replaced everywhere with `NOT_IN_USER_FOLDER` (403/Forbidden).

**Tech Stack:** Java 21, Spring Boot, JUnit 5, Mockito, AssertJ. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-05-25-user-folder-ownership-design.md`.

---

## File Structure

### Create (new)
| File | Responsibility |
|---|---|
| `src/main/java/com/myxcomp/ice/xtree/service/exception/ForbiddenException.java` | Exception type, maps to HTTP 403, carries `ErrorCode`. |
| `src/main/java/com/myxcomp/ice/xtree/service/OwnershipChecker.java` | Component with `requireHomeFolderExists(user)` and `requireOwned(itemId, home, user, label)`. |
| `src/test/java/com/myxcomp/ice/xtree/service/OwnershipCheckerTest.java` | Unit tests for the checker. |
| `src/test/java/com/myxcomp/ice/xtree/service/exception/ForbiddenExceptionTest.java` | Construction + errorCode/message round-trip. |
| `docs/superpowers/plans/2026-05-25-phase16-user-folder-ownership.md` | This plan (already being written). |
| `/home/dave/.claude/projects/-home-dave-Git-item-tree/memory/project-phase16-ownership-done.md` | Memory note at end. |

### Modify
| File | Change |
|---|---|
| `src/main/java/com/myxcomp/ice/xtree/service/exception/ErrorCode.java` | Add `NOT_IN_USER_FOLDER`; remove `DESTINATION_NOT_IN_USER_FOLDER`. |
| `src/main/java/com/myxcomp/ice/xtree/api/advice/GlobalExceptionHandler.java` | Add `@ExceptionHandler(ForbiddenException.class)` → 403. |
| `src/main/java/com/myxcomp/ice/xtree/service/ItemService.java` | Inject `OwnershipChecker`. Add `requireHomeFolderExists` + `requireOwned` calls into 5 mutations. Refactor `copyItem` to use checker; replace `DESTINATION_NOT_IN_USER_FOLDER`. Adjust `deleteItem` to probe cache first. |
| `src/test/java/com/myxcomp/ice/xtree/service/exception/ErrorCodeTest.java` | Swap `DESTINATION_NOT_IN_USER_FOLDER` → `NOT_IN_USER_FOLDER` in both lists. |
| `src/test/java/com/myxcomp/ice/xtree/api/advice/GlobalExceptionHandlerTest.java` | Add `forbiddenExceptionMapsTo403WithErrorCode` test. |
| `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceCreateTest.java` | Inject `OwnershipChecker` mock; stub for existing tests; add ownership-failure tests. |
| `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceDeleteTest.java` | Same; plus cache-probe-shift coverage. |
| `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceRenameTest.java` | Same. |
| `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceMoveTest.java` | Same; plus source/parent matrix. |
| `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceUpdateDataTest.java` | Same. |
| `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceCopyTest.java` | Inject `OwnershipChecker` mock; rewrite `destinationNotInUserFolder` to expect `ForbiddenException`/`NOT_IN_USER_FOLDER`. |
| `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceMetricsTest.java` | Inject `OwnershipChecker` mock for `ItemService` construction. |
| `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceGetItemsTest.java` | Inject `OwnershipChecker` mock for `ItemService` construction. |
| `src/test/java/com/myxcomp/ice/xtree/api/controller/ItemControllerTest.java` | Add 5 `forbidden403` tests (one per mutation endpoint). Update `CopyItem.serviceErrorCases` to swap `DESTINATION_NOT_IN_USER_FOLDER`→`NOT_IN_USER_FOLDER` and 400→403/Forbidden. |
| `src/test/java/com/myxcomp/ice/xtree/e2e/ItemTreeApplicationE2EIT.java` | Add `mutationsForbiddenOutsideOwnFolder` test. |
| `src/test/java/com/myxcomp/ice/xtree/e2e/ObservabilityExposureIT.java` | Update `itemtree.copy.rejected{reason}` assertion (if any) to new tag value. |
| `CLAUDE.md` | Delete the "Add per-user authorization checks — UI enforces permissions" line from "Things to NEVER do". |
| `itemtree-service-design.md` | §3 — add bullet on ownership rule; remove "Cascade delete is unbounded; permissions are enforced UI-side."; update error-code list. §13 — append "Home-folder ownership enforcement" subsection. |
| `IMPLEMENTATION_NOTES.md` | Rename existing `## Phase 16 — Work PC wiring` → `## Phase 17 — …`. Insert new `## Phase 16 — User-folder ownership enforcement` section. |

---

## Conventions used throughout this plan

- **TDD cycle.** Each functional task is: failing test → run-to-confirm-fail → implementation → run-to-confirm-pass → commit. Refactors (e.g. removing `DESTINATION_NOT_IN_USER_FOLDER`) are coordinated edits where production code + tests change together to keep the codebase compilable.
- **AssertJ semantic assertions** per CLAUDE.md (e.g. `isInstanceOf` + `satisfies` for typed exceptions; `isNotNull()` not `isEqualTo(null)`).
- **`@Nested` grouping** in service tests when adding ownership tests to existing test classes — one new `@Nested class Ownership` per file.
- **Mockito `lenient()`** for `@BeforeEach` stubs that some tests don't exercise (existing pattern in `ItemServiceCopyTest`).
- **Test commands.** Single test class: `./gradlew test --tests 'com.myxcomp.ice.xtree.service.OwnershipCheckerTest'`. All: `./gradlew test`. Full build: `./gradlew clean build`.
- **Commits.** One per logical task. Conventional-commit prefix: `feat(phase16):`, `test(phase16):`, `refactor(phase16):`, `docs(phase16):`.

---

## Task 1 — Add `NOT_IN_USER_FOLDER` to `ErrorCode` enum (TDD via `ErrorCodeTest`)

**Files:**
- Modify: `src/test/java/com/myxcomp/ice/xtree/service/exception/ErrorCodeTest.java`
- Modify: `src/main/java/com/myxcomp/ice/xtree/service/exception/ErrorCode.java`

- [ ] **Step 1: Add `NOT_IN_USER_FOLDER` to the expected list in `ErrorCodeTest`.**

Edit `ErrorCodeTest.java` — append `"NOT_IN_USER_FOLDER"` to `EXPECTED_NAMES` (line 15-34). Result:

```java
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
        "HOME_FOLDER_NOT_FOUND",
        "INVALID_SEARCH_PARAMS",
        "DATA_NOT_SERIALISABLE",
        "CANNOT_COPY_ROOT",
        "DESTINATION_NOT_FOUND",
        "DESTINATION_NOT_FOLDER",
        "DESTINATION_NOT_IN_USER_FOLDER",
        "COPY_INTO_DESCENDANT",
        "COPY_TOO_LARGE",
        "NOT_IN_USER_FOLDER"
);
```

- [ ] **Step 2: Run test to verify it fails.**

Run: `./gradlew test --tests 'com.myxcomp.ice.xtree.service.exception.ErrorCodeTest'`
Expected: FAIL with `containsExactlyInAnyOrderElementsOf` mismatch — `NOT_IN_USER_FOLDER` listed as expected but not actual.

- [ ] **Step 3: Add the enum value.**

Edit `ErrorCode.java` (line 22) — add `NOT_IN_USER_FOLDER` as the last enum constant. Note the trailing comma on `COPY_TOO_LARGE`:

```java
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
    COPY_TOO_LARGE,
    NOT_IN_USER_FOLDER
}
```

- [ ] **Step 4: Run test to verify it passes.**

Run: `./gradlew test --tests 'com.myxcomp.ice.xtree.service.exception.ErrorCodeTest'`
Expected: PASS (both `enumExposesExactlyTheCodesTheDesignRequires` and `copyErrorCodesExist`).

- [ ] **Step 5: Commit.**

```bash
git add src/main/java/com/myxcomp/ice/xtree/service/exception/ErrorCode.java \
        src/test/java/com/myxcomp/ice/xtree/service/exception/ErrorCodeTest.java
git commit -m "feat(phase16): add NOT_IN_USER_FOLDER error code

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Task 2 — Add `ForbiddenException` (TDD via `ForbiddenExceptionTest`)

**Files:**
- Create: `src/test/java/com/myxcomp/ice/xtree/service/exception/ForbiddenExceptionTest.java`
- Create: `src/main/java/com/myxcomp/ice/xtree/service/exception/ForbiddenException.java`

- [ ] **Step 1: Write the failing test.**

Create `src/test/java/com/myxcomp/ice/xtree/service/exception/ForbiddenExceptionTest.java`:

```java
package com.myxcomp.ice.xtree.service.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ForbiddenExceptionTest {

    @Test
    void carriesErrorCodeAndMessage() {
        ForbiddenException ex = new ForbiddenException(
                ErrorCode.NOT_IN_USER_FOLDER, "X is not yours");
        assertThat(ex.errorCode()).isEqualTo(ErrorCode.NOT_IN_USER_FOLDER);
        assertThat(ex).hasMessage("X is not yours");
    }

    @Test
    void extendsItemTreeException() {
        ForbiddenException ex = new ForbiddenException(
                ErrorCode.NOT_IN_USER_FOLDER, "x");
        assertThat(ex).isInstanceOf(ItemTreeException.class);
    }

    @Test
    void nullErrorCodeIsRejected() {
        assertThatThrownBy(() -> new ForbiddenException(null, "x"))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("errorCode");
    }
}
```

- [ ] **Step 2: Run test to verify it fails.**

Run: `./gradlew test --tests 'com.myxcomp.ice.xtree.service.exception.ForbiddenExceptionTest'`
Expected: COMPILATION FAILURE — `ForbiddenException` is not defined.

- [ ] **Step 3: Write the implementation.**

Create `src/main/java/com/myxcomp/ice/xtree/service/exception/ForbiddenException.java`:

```java
package com.myxcomp.ice.xtree.service.exception;

/** Maps to HTTP 403 in the HTTP layer. */
public class ForbiddenException extends ItemTreeException {
    public ForbiddenException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
```

- [ ] **Step 4: Run test to verify it passes.**

Run: `./gradlew test --tests 'com.myxcomp.ice.xtree.service.exception.ForbiddenExceptionTest'`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit.**

```bash
git add src/main/java/com/myxcomp/ice/xtree/service/exception/ForbiddenException.java \
        src/test/java/com/myxcomp/ice/xtree/service/exception/ForbiddenExceptionTest.java
git commit -m "feat(phase16): add ForbiddenException (HTTP 403)

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Task 3 — Add `ForbiddenException` → 403 mapping in `GlobalExceptionHandler` (TDD)

**Files:**
- Modify: `src/test/java/com/myxcomp/ice/xtree/api/advice/GlobalExceptionHandlerTest.java`
- Modify: `src/main/java/com/myxcomp/ice/xtree/api/advice/GlobalExceptionHandler.java`

- [ ] **Step 1: Write the failing test.**

Open `GlobalExceptionHandlerTest.java`. Locate the test methods for `ValidationException` / `NotFoundException` mapping. Add this test (place it adjacent to the other `handle*` tests; follow the existing test style — read the file to mirror the imports and helper-method usage):

```java
@Test
void forbiddenExceptionMapsTo403WithErrorCodeAndProblemJson() {
    ForbiddenException ex = new ForbiddenException(
            ErrorCode.NOT_IN_USER_FOLDER, "Parent 42 is not under home folder of 'alice'");

    ResponseEntity<Problem> response = handler.handleForbidden(ex);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(response.getHeaders().getContentType())
            .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().getStatus()).isEqualTo(403);
    assertThat(response.getBody().getErrorCode()).isEqualTo("NOT_IN_USER_FOLDER");
    assertThat(response.getBody().getDetail())
            .isEqualTo("Parent 42 is not under home folder of 'alice'");
}
```

Add required imports if not already present:
```java
import com.myxcomp.ice.xtree.service.exception.ForbiddenException;
import org.springframework.http.MediaType;
```

- [ ] **Step 2: Run test to verify it fails.**

Run: `./gradlew test --tests 'com.myxcomp.ice.xtree.api.advice.GlobalExceptionHandlerTest.forbiddenExceptionMapsTo403WithErrorCodeAndProblemJson'`
Expected: COMPILATION FAILURE — `handler.handleForbidden` is not defined.

- [ ] **Step 3: Add the handler method.**

Edit `GlobalExceptionHandler.java`. Add the import:

```java
import com.myxcomp.ice.xtree.service.exception.ForbiddenException;
```

Add the handler method immediately after `handleValidation` (~line 41):

```java
@ExceptionHandler(ForbiddenException.class)
public ResponseEntity<Problem> handleForbidden(ForbiddenException e) {
    return problemFactory.build(HttpStatus.FORBIDDEN, e.errorCode(), e.getMessage());
}
```

- [ ] **Step 4: Run test to verify it passes; also run the full `GlobalExceptionHandlerTest` class to confirm no regression.**

Run:
```
./gradlew test --tests 'com.myxcomp.ice.xtree.api.advice.GlobalExceptionHandlerTest'
```
Expected: PASS (all tests, including the new one).

- [ ] **Step 5: Commit.**

```bash
git add src/main/java/com/myxcomp/ice/xtree/api/advice/GlobalExceptionHandler.java \
        src/test/java/com/myxcomp/ice/xtree/api/advice/GlobalExceptionHandlerTest.java
git commit -m "feat(phase16): map ForbiddenException to HTTP 403 with Problem body

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Task 4 — Build `OwnershipChecker` with full unit-test coverage (TDD)

**Files:**
- Create: `src/test/java/com/myxcomp/ice/xtree/service/OwnershipCheckerTest.java`
- Create: `src/main/java/com/myxcomp/ice/xtree/service/OwnershipChecker.java`

- [ ] **Step 1: Write the failing test.**

Create `src/test/java/com/myxcomp/ice/xtree/service/OwnershipCheckerTest.java`:

```java
package com.myxcomp.ice.xtree.service;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.TreeCache;
import com.myxcomp.ice.xtree.service.exception.ErrorCode;
import com.myxcomp.ice.xtree.service.exception.ForbiddenException;
import com.myxcomp.ice.xtree.service.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OwnershipCheckerTest {

    private static final Instant T = Instant.parse("2026-05-25T10:00:00Z");

    @Mock TreeCache cache;

    OwnershipChecker checker;

    @BeforeEach
    void setUp() {
        checker = new OwnershipChecker(cache);
    }

    @Nested
    class RequireHomeFolderExists {

        @Test
        void returnsHomeFolderWhenPresent() {
            CachedNode home = new CachedNode(10L, 2L, "alice", "Folder", T, "sys");
            when(cache.findHomeFolder("alice")).thenReturn(Optional.of(home));

            CachedNode result = checker.requireHomeFolderExists("alice");

            assertThat(result).isSameAs(home);
        }

        @Test
        void throwsHomeFolderNotFoundWhenAbsent() {
            when(cache.findHomeFolder("ghost")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> checker.requireHomeFolderExists("ghost"))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("ghost")
                    .satisfies(e -> assertThat(((NotFoundException) e).errorCode())
                            .isEqualTo(ErrorCode.HOME_FOLDER_NOT_FOUND));
        }

        @Test
        void rejectsNullUser() {
            assertThatThrownBy(() -> checker.requireHomeFolderExists(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("effectiveUser");
        }
    }

    @Nested
    class RequireOwned {

        private final CachedNode home = new CachedNode(10L, 2L, "alice", "Folder", T, "sys");

        @Test
        void allowsItemThatIsTheHomeFolderItself() {
            assertThatCode(() -> checker.requireOwned(10L, home, "alice", "Item"))
                    .doesNotThrowAnyException();
        }

        @Test
        void allowsItemThatIsAncestorOfHome() {
            // Wait — semantics: we ask "is itemId inside home subtree?" → cache.isAncestor(home, itemId)
            // This case: item 99 is in the subtree → ancestor(10, 99) = true
            when(cache.isAncestor(10L, 99L)).thenReturn(true);

            assertThatCode(() -> checker.requireOwned(99L, home, "alice", "Item"))
                    .doesNotThrowAnyException();
        }

        @Test
        void throwsForbiddenWhenItemIsNotInHomeSubtree() {
            when(cache.isAncestor(10L, 99L)).thenReturn(false);

            assertThatThrownBy(() -> checker.requireOwned(99L, home, "alice", "Parent"))
                    .isInstanceOf(ForbiddenException.class)
                    .satisfies(e -> assertThat(((ForbiddenException) e).errorCode())
                            .isEqualTo(ErrorCode.NOT_IN_USER_FOLDER))
                    .hasMessageContaining("Parent")
                    .hasMessageContaining("99")
                    .hasMessageContaining("alice");
        }

        @Test
        void contextLabelAppearsInDetail() {
            when(cache.isAncestor(10L, 50L)).thenReturn(false);

            assertThatThrownBy(() -> checker.requireOwned(50L, home, "alice", "New parent"))
                    .hasMessageContaining("New parent 50");
        }

        @Test
        void rejectsNullHomeFolder() {
            assertThatThrownBy(() -> checker.requireOwned(1L, null, "alice", "Item"))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("homeFolder");
        }

        @Test
        void rejectsNullUser() {
            assertThatThrownBy(() -> checker.requireOwned(1L, home, null, "Item"))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("effectiveUser");
        }

        @Test
        void rejectsNullContextLabel() {
            assertThatThrownBy(() -> checker.requireOwned(1L, home, "alice", null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("contextLabel");
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails.**

Run: `./gradlew test --tests 'com.myxcomp.ice.xtree.service.OwnershipCheckerTest'`
Expected: COMPILATION FAILURE — `OwnershipChecker` not defined.

- [ ] **Step 3: Write the implementation.**

Create `src/main/java/com/myxcomp/ice/xtree/service/OwnershipChecker.java`:

```java
package com.myxcomp.ice.xtree.service;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.TreeCache;
import com.myxcomp.ice.xtree.service.exception.ErrorCode;
import com.myxcomp.ice.xtree.service.exception.ForbiddenException;
import com.myxcomp.ice.xtree.service.exception.NotFoundException;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Server-side home-folder ownership check used by mutation operations
 * (Phase 16). An item is "owned" by user U iff it is U's home folder
 * itself or a descendant of U's home folder.
 */
@Component
public class OwnershipChecker {

    private final TreeCache cache;

    public OwnershipChecker(TreeCache cache) {
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    /**
     * Resolves and returns the home folder for {@code effectiveUser}.
     *
     * @throws NotFoundException ({@link ErrorCode#HOME_FOLDER_NOT_FOUND}) if no folder matches.
     */
    public CachedNode requireHomeFolderExists(String effectiveUser) {
        Objects.requireNonNull(effectiveUser, "effectiveUser");
        return cache.findHomeFolder(effectiveUser).orElseThrow(() -> new NotFoundException(
                ErrorCode.HOME_FOLDER_NOT_FOUND,
                "No home folder for user '" + effectiveUser + "'"));
    }

    /**
     * Asserts that {@code itemId} is inside the subtree rooted at {@code homeFolder}
     * (or is the home folder itself).
     *
     * @param contextLabel a short noun ("Parent", "Item", "Source", "Destination",
     *                     "New parent") used to build a readable {@code Problem.detail}.
     * @throws ForbiddenException ({@link ErrorCode#NOT_IN_USER_FOLDER}) when the item
     *                            is outside the user's home subtree.
     */
    public void requireOwned(long itemId, CachedNode homeFolder, String effectiveUser, String contextLabel) {
        Objects.requireNonNull(homeFolder, "homeFolder");
        Objects.requireNonNull(effectiveUser, "effectiveUser");
        Objects.requireNonNull(contextLabel, "contextLabel");

        if (itemId == homeFolder.itemTreeId()) return;
        if (cache.isAncestor(homeFolder.itemTreeId(), itemId)) return;

        throw new ForbiddenException(
                ErrorCode.NOT_IN_USER_FOLDER,
                contextLabel + " " + itemId
                        + " is not under home folder of '" + effectiveUser + "'");
    }
}
```

- [ ] **Step 4: Run test to verify it passes.**

Run: `./gradlew test --tests 'com.myxcomp.ice.xtree.service.OwnershipCheckerTest'`
Expected: PASS (all tests across both nested classes).

- [ ] **Step 5: Commit.**

```bash
git add src/main/java/com/myxcomp/ice/xtree/service/OwnershipChecker.java \
        src/test/java/com/myxcomp/ice/xtree/service/OwnershipCheckerTest.java
git commit -m "feat(phase16): add OwnershipChecker component

requireHomeFolderExists resolves the home folder or throws
HOME_FOLDER_NOT_FOUND. requireOwned asserts an item is in the user's
home subtree or throws NOT_IN_USER_FOLDER. Two-method shape avoids
duplicate home-folder lookups for moveItem (one lookup, two checks).

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Task 5 — Inject `OwnershipChecker` into `ItemService` (constructor change only, no behaviour change yet)

This task widens the `ItemService` constructor and updates **all** `ItemService*Test` classes to provide a mocked `OwnershipChecker`. No mutation method is yet calling the checker — that's done in Tasks 6–11. After this task all existing tests still pass.

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/service/ItemService.java`
- Modify: `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceCreateTest.java`
- Modify: `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceDeleteTest.java`
- Modify: `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceRenameTest.java`
- Modify: `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceMoveTest.java`
- Modify: `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceUpdateDataTest.java`
- Modify: `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceCopyTest.java`
- Modify: `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceMetricsTest.java`
- Modify: `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceGetItemsTest.java`

- [ ] **Step 1: Add the `OwnershipChecker` field, constructor parameter, and null guard in `ItemService`.**

Edit `ItemService.java`:

- Add field after line 69 (after `copyProperties`):
  ```java
  private final OwnershipChecker ownershipChecker;
  ```
- Add `OwnershipChecker ownershipChecker` as the last constructor parameter (line 71-81), and the assignment at the bottom of the constructor body:
  ```java
  this.ownershipChecker = Objects.requireNonNull(ownershipChecker, "ownershipChecker");
  ```

Final constructor shape:

```java
public ItemService(TreeCache cache,
                   ItemTreeRepository repository,
                   TypePolicy policy,
                   XmlJsonConverter converter,
                   EventPublisher publisher,
                   TimeMapper timeMapper,
                   InstanceIdProvider instanceIdProvider,
                   SequenceGenerator sequenceGenerator,
                   @Qualifier("backfillExecutor") TaskExecutor backfillExecutor,
                   MeterRegistry meterRegistry,
                   CopyProperties copyProperties,
                   OwnershipChecker ownershipChecker) {
    this.cache = cache;
    this.repository = repository;
    this.policy = policy;
    this.converter = converter;
    this.publisher = publisher;
    this.timeMapper = timeMapper;
    this.instanceIdProvider = instanceIdProvider;
    this.sequenceGenerator = sequenceGenerator;
    this.backfillExecutor = backfillExecutor;
    this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
    this.copyProperties = Objects.requireNonNull(copyProperties, "copyProperties");
    this.ownershipChecker = Objects.requireNonNull(ownershipChecker, "ownershipChecker");
}
```

- [ ] **Step 2: Update every `ItemService*Test` class to pass a mocked `OwnershipChecker`.**

In each of the 8 test files listed above, do these three edits:

a) Add the import:
```java
import org.mockito.Mock;
// (already present in all files; just verify)
```

b) Add a new `@Mock` field below the existing mocks (e.g. after `@Mock CopyProperties copyProperties;`):
```java
@Mock OwnershipChecker ownershipChecker;
```

c) In the `@BeforeEach setUp()` method, append `ownershipChecker` to the `new ItemService(...)` constructor call. Example (`ItemServiceCreateTest.java` lines 67-74):

```java
@BeforeEach
void setUp() {
    lenient().when(copyProperties.maxNodes()).thenReturn(100);
    service = new ItemService(
            cache, repository, policy, converter, publisher,
            timeMapper, instanceIdProvider, sequenceGenerator,
            new SyncTaskExecutor(), new SimpleMeterRegistry(), copyProperties,
            ownershipChecker);
}
```

Apply analogous edits to all 8 files. The constructor argument list is the same in every one.

- [ ] **Step 3: Compile and run all `ItemService*Test` classes.**

Run:
```
./gradlew test --tests 'com.myxcomp.ice.xtree.service.ItemService*Test'
```
Expected: PASS. (No behaviour change; the new mock is unused so far, which Mockito allows.)

- [ ] **Step 4: Run the full test suite to catch any other call site that constructs `ItemService` directly.**

Run: `./gradlew test`
Expected: PASS. If any other class instantiates `ItemService` (search with `grep -rn "new ItemService(" src/`), update it as well. Most likely there are none outside the Spring context — Spring's DI handles production wiring automatically because `OwnershipChecker` is `@Component`.

- [ ] **Step 5: Commit.**

```bash
git add src/main/java/com/myxcomp/ice/xtree/service/ItemService.java \
        src/test/java/com/myxcomp/ice/xtree/service/ItemService*Test.java
git commit -m "refactor(phase16): inject OwnershipChecker into ItemService

Constructor-only change; no mutation method calls the checker yet.
All ItemService*Test classes updated to pass a mock.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Task 6 — Wire `createItem` to enforce ownership (TDD)

**Files:**
- Modify: `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceCreateTest.java`
- Modify: `src/main/java/com/myxcomp/ice/xtree/service/ItemService.java`

- [ ] **Step 1: Write the failing tests.**

In `ItemServiceCreateTest.java`, add a new `@Nested` class at the bottom of the outer class (before the final `}`):

```java
@Nested
class Ownership {

    private final UserContext ctx = new UserContext("alice", null);
    private final CachedNode parentFolder = new CachedNode(2L, 1L, "Users", "Folder", NOW, "sys");
    private final CachedNode aliceHome = new CachedNode(10L, 2L, "alice", "Folder", NOW, "sys");

    @org.junit.jupiter.api.BeforeEach
    void stubCommon() {
        lenient().when(cache.getById(2L)).thenReturn(Optional.of(parentFolder));
        lenient().when(policy.hasData("Folder")).thenReturn(false);
        lenient().when(policy.isKnown("Folder")).thenReturn(true);
    }

    @Test
    void parentNotInUserHomeRejectsWith403() {
        when(ownershipChecker.requireHomeFolderExists("alice")).thenReturn(aliceHome);
        org.mockito.Mockito.doThrow(new com.myxcomp.ice.xtree.service.exception.ForbiddenException(
                ErrorCode.NOT_IN_USER_FOLDER, "Parent 2 is not under home folder of 'alice'"))
                .when(ownershipChecker).requireOwned(2L, aliceHome, "alice", "Parent");

        assertThatThrownBy(() -> service.createItem(2L, "x", "Folder", null, ctx))
                .isInstanceOf(com.myxcomp.ice.xtree.service.exception.ForbiddenException.class)
                .satisfies(e -> assertThat(
                        ((com.myxcomp.ice.xtree.service.exception.ForbiddenException) e).errorCode())
                        .isEqualTo(ErrorCode.NOT_IN_USER_FOLDER));

        verify(repository, never()).insert(anyLong(), any(), any(), any(), any(), any(), any());
        verifyNoInteractions(publisher);
    }

    @Test
    void noHomeFolderRejectsWith404() {
        when(ownershipChecker.requireHomeFolderExists("alice")).thenThrow(
                new NotFoundException(ErrorCode.HOME_FOLDER_NOT_FOUND,
                        "No home folder for user 'alice'"));

        assertThatThrownBy(() -> service.createItem(2L, "x", "Folder", null, ctx))
                .isInstanceOf(NotFoundException.class)
                .satisfies(e -> assertThat(((NotFoundException) e).errorCode())
                        .isEqualTo(ErrorCode.HOME_FOLDER_NOT_FOUND));

        verify(repository, never()).insert(anyLong(), any(), any(), any(), any(), any(), any());
        verifyNoInteractions(publisher);
    }

    @Test
    void ownershipCheckUsesImpersonatedUserNotIceUser() {
        UserContext impersonating = new UserContext("alice", "bob");
        CachedNode bobHome = new CachedNode(11L, 2L, "bob", "Folder", NOW, "sys");
        when(ownershipChecker.requireHomeFolderExists("bob")).thenReturn(bobHome);
        org.mockito.Mockito.doThrow(new com.myxcomp.ice.xtree.service.exception.ForbiddenException(
                ErrorCode.NOT_IN_USER_FOLDER, "Parent 2 is not under home folder of 'bob'"))
                .when(ownershipChecker).requireOwned(2L, bobHome, "bob", "Parent");

        assertThatThrownBy(() -> service.createItem(2L, "x", "Folder", null, impersonating))
                .isInstanceOf(com.myxcomp.ice.xtree.service.exception.ForbiddenException.class);

        verify(ownershipChecker, never()).requireHomeFolderExists("alice");
    }

    @Test
    void ownershipCheckRunsAfterParentNotFolder() {
        CachedNode notAFolder = new CachedNode(99L, 1L, "x", "Report", NOW, "sys");
        when(cache.getById(99L)).thenReturn(Optional.of(notAFolder));

        assertThatThrownBy(() -> service.createItem(99L, "x", "Folder", null, ctx))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).errorCode())
                        .isEqualTo(ErrorCode.PARENT_NOT_FOLDER));

        verifyNoInteractions(ownershipChecker);
    }

    @Test
    void ownershipPassesThenContinuesToTypeValidation() {
        when(ownershipChecker.requireHomeFolderExists("alice")).thenReturn(aliceHome);
        // requireOwned returns void — default mock behaviour = no throw → passes
        // Then typed-data validation fires:
        when(policy.hasData("Folder")).thenReturn(false);

        assertThatCode(() -> service.createItem(2L, "x", "Folder", null, ctx))
                .doesNotThrowAnyException();  // create succeeds (will hit repo.insert)
        // (covered by existing happy-path tests — just verifying the order doesn't regress)
    }
}
```

(Note: the last test will fail to compile/run cleanly without stubs for `repository.insert`, `timeMapper.now`, etc. — those are already covered by other tests. Omit `ownershipPassesThenContinuesToTypeValidation` if it adds noise; the validation order is also asserted by `ownershipCheckRunsAfterParentNotFolder`.)

- [ ] **Step 2: Run the new tests to verify they fail.**

Run: `./gradlew test --tests 'com.myxcomp.ice.xtree.service.ItemServiceCreateTest$Ownership'`
Expected: FAIL — `ItemService.createItem` does not yet call `ownershipChecker`.

- [ ] **Step 3: Add the ownership check to `createItem` in `ItemService.java`.**

Locate `createItem` (line 102). Insert the two new lines **between** the `PARENT_NOT_FOLDER` check (~line 113) and the `boolean hasData = ...` line (~line 115):

```java
CachedNode homeFolder = ownershipChecker.requireHomeFolderExists(userContext.effectiveUser());
ownershipChecker.requireOwned(parentId, homeFolder, userContext.effectiveUser(), "Parent");
```

Final relevant fragment of `createItem`:

```java
if (!Types.isFolder(parent.type())) {
    throw new ValidationException(ErrorCode.PARENT_NOT_FOLDER,
            "Parent " + parentId + " is not a folder (type=" + parent.type() + ")");
}

CachedNode homeFolder = ownershipChecker.requireHomeFolderExists(userContext.effectiveUser());
ownershipChecker.requireOwned(parentId, homeFolder, userContext.effectiveUser(), "Parent");

boolean hasData = policy.hasData(type);
```

- [ ] **Step 4: Run all `ItemServiceCreateTest` tests to verify pass and no regression.**

Run: `./gradlew test --tests 'com.myxcomp.ice.xtree.service.ItemServiceCreateTest'`
Expected: PASS — existing tests still green (the mocked `ownershipChecker` returns home and does nothing on `requireOwned` by default, so the happy-path tests continue to work), new Ownership tests pass.

If any existing test fails because it now requires `ownershipChecker` stubs: add `lenient().when(ownershipChecker.requireHomeFolderExists(anyString())).thenReturn(<any folder>);` in the existing test's setup, **or** make the existing test in-home by stubbing both calls. Prefer the smaller, targeted fix.

- [ ] **Step 5: Commit.**

```bash
git add src/main/java/com/myxcomp/ice/xtree/service/ItemService.java \
        src/test/java/com/myxcomp/ice/xtree/service/ItemServiceCreateTest.java
git commit -m "feat(phase16): enforce home-folder ownership on createItem

Parent must be the caller's home folder or a descendant. New step
fires after PARENT_NOT_FOLDER, before TYPE/DATA checks.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Task 7 — Wire `deleteItem` to enforce ownership (TDD) + shift probe to cache

**Behaviour change:** Today `deleteItem` calls `repository.cascadeDeleteSubtree` first and short-circuits on empty. After this task it calls `cache.getById(id)` first; if absent → silent no-op; if present → ownership check → cascade delete. Spec §3.2.

**Files:**
- Modify: `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceDeleteTest.java`
- Modify: `src/main/java/com/myxcomp/ice/xtree/service/ItemService.java`

- [ ] **Step 1: Write the failing tests.**

In `ItemServiceDeleteTest.java`, add a new nested class at the bottom:

```java
@Nested
class Ownership {

    private final UserContext ctx = new UserContext("alice", null);
    private final CachedNode targetItem = new CachedNode(50L, 10L, "Report", "Report",
            java.time.Instant.parse("2026-05-25T10:00:00Z"), "sys");
    private final CachedNode aliceHome = new CachedNode(10L, 2L, "alice", "Folder",
            java.time.Instant.parse("2026-05-25T10:00:00Z"), "sys");

    @Test
    void deletingItemNotInUserHomeRejectsWith403() {
        when(cache.getById(50L)).thenReturn(Optional.of(targetItem));
        when(ownershipChecker.requireHomeFolderExists("alice")).thenReturn(aliceHome);
        org.mockito.Mockito.doThrow(new com.myxcomp.ice.xtree.service.exception.ForbiddenException(
                ErrorCode.NOT_IN_USER_FOLDER, "Item 50 is not under home folder of 'alice'"))
                .when(ownershipChecker).requireOwned(50L, aliceHome, "alice", "Item");

        assertThatThrownBy(() -> service.deleteItem(50L, ctx))
                .isInstanceOf(com.myxcomp.ice.xtree.service.exception.ForbiddenException.class)
                .satisfies(e -> assertThat(
                        ((com.myxcomp.ice.xtree.service.exception.ForbiddenException) e).errorCode())
                        .isEqualTo(ErrorCode.NOT_IN_USER_FOLDER));

        verify(repository, never()).cascadeDeleteSubtree(anyLong());
        verifyNoInteractions(publisher);
    }

    @Test
    void deletingMissingIdIsNoopEvenWithoutHomeFolder() {
        // cache.getById returns empty → no auth check, no DB call
        when(cache.getById(999L)).thenReturn(Optional.empty());

        assertThatCode(() -> service.deleteItem(999L, ctx))
                .doesNotThrowAnyException();

        verify(repository, never()).cascadeDeleteSubtree(anyLong());
        verifyNoInteractions(ownershipChecker);
        verifyNoInteractions(publisher);
    }

    @Test
    void noHomeFolderRejectsWith404WhenItemExists() {
        when(cache.getById(50L)).thenReturn(Optional.of(targetItem));
        when(ownershipChecker.requireHomeFolderExists("alice")).thenThrow(
                new NotFoundException(ErrorCode.HOME_FOLDER_NOT_FOUND,
                        "No home folder for user 'alice'"));

        assertThatThrownBy(() -> service.deleteItem(50L, ctx))
                .isInstanceOf(NotFoundException.class)
                .satisfies(e -> assertThat(((NotFoundException) e).errorCode())
                        .isEqualTo(ErrorCode.HOME_FOLDER_NOT_FOUND));

        verify(repository, never()).cascadeDeleteSubtree(anyLong());
    }

    @Test
    void ownedItemFlowsThroughToCascadeDelete() {
        when(cache.getById(50L)).thenReturn(Optional.of(targetItem));
        when(ownershipChecker.requireHomeFolderExists("alice")).thenReturn(aliceHome);
        // requireOwned returns void → default mock = passes
        when(repository.cascadeDeleteSubtree(50L)).thenReturn(java.util.List.of(50L));
        when(timeMapper.now()).thenReturn(java.time.Instant.parse("2026-05-25T11:00:00Z"));
        when(instanceIdProvider.getInstanceId()).thenReturn("inst-1");
        when(sequenceGenerator.next()).thenReturn(1L);

        service.deleteItem(50L, ctx);

        verify(repository).cascadeDeleteSubtree(50L);
        verify(publisher).publish(any());
    }
}
```

(Verify the imports at top of file include `Optional`, `lenient`, `when`, `verify`, `verifyNoInteractions`, `any`, `anyLong`, `NotFoundException`, `ErrorCode`, etc. — add missing ones.)

- [ ] **Step 2: Run the new tests to verify they fail.**

Run: `./gradlew test --tests 'com.myxcomp.ice.xtree.service.ItemServiceDeleteTest$Ownership'`
Expected: FAIL — current `deleteItem` calls the repository first, ignoring the cache.

- [ ] **Step 3: Rewrite `deleteItem` in `ItemService.java` (lines 161–182).**

Replace the entire method body with the cache-probe-first flow:

```java
/**
 * Cascade-deletes {@code id} and all descendants. Silent no-op if {@code id} is
 * not present in the cache (Phase 16: cache is the authority for ownership).
 * Order: cache probe → ownership check → DB cascade → cache.applyDelete → event.
 */
@Transactional
public void deleteItem(long id, UserContext userContext) {
    Objects.requireNonNull(userContext, "userContext");

    if (cache.getById(id).isEmpty()) {
        log.info("deleteItem: id={} not present in cache; no-op", id);
        return;
    }

    String effectiveUser = userContext.effectiveUser();
    CachedNode homeFolder = ownershipChecker.requireHomeFolderExists(effectiveUser);
    ownershipChecker.requireOwned(id, homeFolder, effectiveUser, "Item");

    List<Long> deletedIds = repository.cascadeDeleteSubtree(id);
    if (deletedIds.isEmpty()) {
        log.info("deleteItem: id={} present in cache but not DB (drift); no-op", id);
        return;
    }
    meterRegistry.summary("itemtree.delete.cascade.size").record(deletedIds.size());
    cache.applyDelete(new HashSet<>(deletedIds));
    Instant now = timeMapper.now();
    try {
        publisher.publish(buildEvent(userContext, OperationType.DELETE,
                new DeletePayload(List.copyOf(deletedIds)), now));
    } catch (RuntimeException e) {
        log.error("EventPublisher threw on {}; event dropped", OperationType.DELETE, e);
    }
}
```

- [ ] **Step 4: Run all `ItemServiceDeleteTest` tests + metrics tests.**

Run:
```
./gradlew test --tests 'com.myxcomp.ice.xtree.service.ItemServiceDeleteTest' \
               --tests 'com.myxcomp.ice.xtree.service.ItemServiceMetricsTest'
```

Expected: PASS for new ownership tests; check existing delete tests. If any existing test was relying on the "id absent from cache but present in DB → still cascade-deletes" path, it will now fail. Spec §3.2 says that path is **correct** to no-op after this change; update those tests to stub `cache.getById` accordingly. (Search: `grep -n "deleteItem" src/test/java/com/myxcomp/ice/xtree/service/ItemServiceDeleteTest.java` — most tests already stub the cache because the existing-id paths require it; the bare "missing id" tests need `when(cache.getById(...)).thenReturn(Optional.empty())`.)

For `ItemServiceMetricsTest`: tests asserting the `itemtree.delete.cascade.size` summary need the item present in cache; add `when(cache.getById(id)).thenReturn(Optional.of(<any node>))` and stub `ownershipChecker.requireHomeFolderExists(...)` returning that node (so the item == home → ownership trivially passes).

- [ ] **Step 5: Commit.**

```bash
git add src/main/java/com/myxcomp/ice/xtree/service/ItemService.java \
        src/test/java/com/myxcomp/ice/xtree/service/ItemServiceDeleteTest.java \
        src/test/java/com/myxcomp/ice/xtree/service/ItemServiceMetricsTest.java
git commit -m "feat(phase16): enforce home-folder ownership on deleteItem

Probe point shifts from DB to cache. Absent from cache → silent no-op
(no auth check, no DB call). Present → ownership check before cascade
delete. Drift case (cache hit / DB miss) also no-ops.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Task 8 — Wire `renameItem` to enforce ownership (TDD)

**Files:**
- Modify: `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceRenameTest.java`
- Modify: `src/main/java/com/myxcomp/ice/xtree/service/ItemService.java`

- [ ] **Step 1: Write the failing tests.**

In `ItemServiceRenameTest.java`, add `@Nested class Ownership` at the bottom. Adapt the imports / member names from the file (read the existing `setUp`):

```java
@Nested
class Ownership {

    private final UserContext ctx = new UserContext("alice", null);
    private final java.time.Instant T = java.time.Instant.parse("2026-05-25T10:00:00Z");
    private final CachedNode targetItem = new CachedNode(50L, 10L, "OldName", "Report", T, "sys");
    private final CachedNode aliceHome = new CachedNode(10L, 2L, "alice", "Folder", T, "sys");

    @Test
    void renamingItemNotInUserHomeRejectsWith403() {
        when(cache.getById(50L)).thenReturn(Optional.of(targetItem));
        when(ownershipChecker.requireHomeFolderExists("alice")).thenReturn(aliceHome);
        org.mockito.Mockito.doThrow(new com.myxcomp.ice.xtree.service.exception.ForbiddenException(
                ErrorCode.NOT_IN_USER_FOLDER, "Item 50 is not under home folder of 'alice'"))
                .when(ownershipChecker).requireOwned(50L, aliceHome, "alice", "Item");

        assertThatThrownBy(() -> service.renameItem(50L, "NewName", ctx))
                .isInstanceOf(com.myxcomp.ice.xtree.service.exception.ForbiddenException.class)
                .satisfies(e -> assertThat(
                        ((com.myxcomp.ice.xtree.service.exception.ForbiddenException) e).errorCode())
                        .isEqualTo(ErrorCode.NOT_IN_USER_FOLDER));

        verify(repository, never()).updateName(anyLong(), any(), any(), any());
        verifyNoInteractions(publisher);
    }

    @Test
    void noHomeFolderRejectsWith404() {
        when(cache.getById(50L)).thenReturn(Optional.of(targetItem));
        when(ownershipChecker.requireHomeFolderExists("alice")).thenThrow(
                new NotFoundException(ErrorCode.HOME_FOLDER_NOT_FOUND,
                        "No home folder for user 'alice'"));

        assertThatThrownBy(() -> service.renameItem(50L, "NewName", ctx))
                .isInstanceOf(NotFoundException.class);

        verify(repository, never()).updateName(anyLong(), any(), any(), any());
    }

    @Test
    void itemNotFoundFiresBeforeOwnershipCheck() {
        when(cache.getById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.renameItem(999L, "NewName", ctx))
                .isInstanceOf(NotFoundException.class)
                .satisfies(e -> assertThat(((NotFoundException) e).errorCode())
                        .isEqualTo(ErrorCode.ITEM_NOT_FOUND));

        verifyNoInteractions(ownershipChecker);
    }
}
```

- [ ] **Step 2: Run the new tests to verify they fail.**

Run: `./gradlew test --tests 'com.myxcomp.ice.xtree.service.ItemServiceRenameTest$Ownership'`
Expected: FAIL.

- [ ] **Step 3: Add the ownership check to `renameItem` in `ItemService.java`.**

Locate `renameItem` (line 190). Insert the two new lines **between** the `ITEM_NOT_FOUND` guard (~line 196) and the `Instant now = ...` line (~line 198):

```java
String effectiveUser = userContext.effectiveUser();
CachedNode homeFolder = ownershipChecker.requireHomeFolderExists(effectiveUser);
ownershipChecker.requireOwned(id, homeFolder, effectiveUser, "Item");
```

Then change the existing `String stampUser = userContext.effectiveUser();` line (~199) to reuse the variable:
```java
String stampUser = effectiveUser;
```

- [ ] **Step 4: Run all `ItemServiceRenameTest` tests.**

Run: `./gradlew test --tests 'com.myxcomp.ice.xtree.service.ItemServiceRenameTest'`
Expected: PASS (new + existing).

If existing happy-path tests fail, stub `ownershipChecker.requireHomeFolderExists(anyString())` in their setup. Use `lenient()` so unused stubs don't fail other tests.

- [ ] **Step 5: Commit.**

```bash
git add src/main/java/com/myxcomp/ice/xtree/service/ItemService.java \
        src/test/java/com/myxcomp/ice/xtree/service/ItemServiceRenameTest.java
git commit -m "feat(phase16): enforce home-folder ownership on renameItem

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Task 9 — Wire `moveItem` to enforce ownership on BOTH source and new parent (TDD)

**Files:**
- Modify: `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceMoveTest.java`
- Modify: `src/main/java/com/myxcomp/ice/xtree/service/ItemService.java`

- [ ] **Step 1: Write the failing tests.**

In `ItemServiceMoveTest.java`, add `@Nested class Ownership`:

```java
@Nested
class Ownership {

    private final UserContext ctx = new UserContext("alice", null);
    private final java.time.Instant T = java.time.Instant.parse("2026-05-25T10:00:00Z");
    private final CachedNode item = new CachedNode(50L, 10L, "X", "Report", T, "sys");
    private final CachedNode newParent = new CachedNode(60L, 10L, "Folder1", "Folder", T, "sys");
    private final CachedNode aliceHome = new CachedNode(10L, 2L, "alice", "Folder", T, "sys");

    @org.junit.jupiter.api.BeforeEach
    void stubMoveCommon() {
        lenient().when(cache.getById(50L)).thenReturn(Optional.of(item));
        lenient().when(cache.getById(60L)).thenReturn(Optional.of(newParent));
        lenient().when(cache.isAncestor(50L, 60L)).thenReturn(false);
    }

    @Test
    void sourceOutOfUserHomeRejectsWith403() {
        when(ownershipChecker.requireHomeFolderExists("alice")).thenReturn(aliceHome);
        org.mockito.Mockito.doThrow(new com.myxcomp.ice.xtree.service.exception.ForbiddenException(
                ErrorCode.NOT_IN_USER_FOLDER, "Source 50 is not under home folder of 'alice'"))
                .when(ownershipChecker).requireOwned(50L, aliceHome, "alice", "Source");

        assertThatThrownBy(() -> service.moveItem(50L, 60L, ctx))
                .isInstanceOf(com.myxcomp.ice.xtree.service.exception.ForbiddenException.class)
                .hasMessageContaining("Source 50");

        verify(repository, never()).updateParent(anyLong(), anyLong(), any(), any());
        verifyNoInteractions(publisher);
    }

    @Test
    void newParentOutOfUserHomeRejectsWith403() {
        when(ownershipChecker.requireHomeFolderExists("alice")).thenReturn(aliceHome);
        // requireOwned(source) passes (default void mock), requireOwned(newParent) throws:
        org.mockito.Mockito.doThrow(new com.myxcomp.ice.xtree.service.exception.ForbiddenException(
                ErrorCode.NOT_IN_USER_FOLDER, "New parent 60 is not under home folder of 'alice'"))
                .when(ownershipChecker).requireOwned(60L, aliceHome, "alice", "New parent");

        assertThatThrownBy(() -> service.moveItem(50L, 60L, ctx))
                .isInstanceOf(com.myxcomp.ice.xtree.service.exception.ForbiddenException.class)
                .hasMessageContaining("New parent 60");

        verify(repository, never()).updateParent(anyLong(), anyLong(), any(), any());
        verifyNoInteractions(publisher);
    }

    @Test
    void bothInUserHomeAllowsMove() {
        when(ownershipChecker.requireHomeFolderExists("alice")).thenReturn(aliceHome);
        when(timeMapper.now()).thenReturn(T);
        when(instanceIdProvider.getInstanceId()).thenReturn("inst-1");
        when(sequenceGenerator.next()).thenReturn(1L);
        when(cache.getById(50L)).thenReturn(Optional.of(item), Optional.of(
                new CachedNode(50L, 60L, "X", "Report", T, "alice")));  // after move

        service.moveItem(50L, 60L, ctx);

        verify(ownershipChecker).requireOwned(50L, aliceHome, "alice", "Source");
        verify(ownershipChecker).requireOwned(60L, aliceHome, "alice", "New parent");
        verify(repository).updateParent(50L, 60L, T, "alice");
    }

    @Test
    void itemNotFoundFiresBeforeOwnership() {
        when(cache.getById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.moveItem(999L, 60L, ctx))
                .isInstanceOf(NotFoundException.class)
                .satisfies(e -> assertThat(((NotFoundException) e).errorCode())
                        .isEqualTo(ErrorCode.ITEM_NOT_FOUND));

        verifyNoInteractions(ownershipChecker);
    }

    @Test
    void moveIntoDescendantFiresBeforeOwnership() {
        when(cache.isAncestor(50L, 60L)).thenReturn(true);

        assertThatThrownBy(() -> service.moveItem(50L, 60L, ctx))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).errorCode())
                        .isEqualTo(ErrorCode.MOVE_INTO_DESCENDANT));

        verifyNoInteractions(ownershipChecker);
    }
}
```

- [ ] **Step 2: Run the new tests to verify they fail.**

Run: `./gradlew test --tests 'com.myxcomp.ice.xtree.service.ItemServiceMoveTest$Ownership'`
Expected: FAIL.

- [ ] **Step 3: Add the ownership checks to `moveItem` in `ItemService.java`.**

Locate `moveItem` (line 220). Insert the new lines **between** the descendant check (`if (cache.isAncestor(...))` ~line 239–242) and the `Instant now = ...` line (~line 244):

```java
String effectiveUser = userContext.effectiveUser();
CachedNode homeFolder = ownershipChecker.requireHomeFolderExists(effectiveUser);
ownershipChecker.requireOwned(id, homeFolder, effectiveUser, "Source");
ownershipChecker.requireOwned(newParentId, homeFolder, effectiveUser, "New parent");
```

Then change `String stampUser = userContext.effectiveUser();` (~line 245) to:
```java
String stampUser = effectiveUser;
```

- [ ] **Step 4: Run all `ItemServiceMoveTest` tests.**

Run: `./gradlew test --tests 'com.myxcomp.ice.xtree.service.ItemServiceMoveTest'`
Expected: PASS.

- [ ] **Step 5: Commit.**

```bash
git add src/main/java/com/myxcomp/ice/xtree/service/ItemService.java \
        src/test/java/com/myxcomp/ice/xtree/service/ItemServiceMoveTest.java
git commit -m "feat(phase16): enforce home-folder ownership on moveItem source+parent

Both the moved item and the destination parent must be in the
caller's home subtree. Blocks moves out (source-only check would
allow) and moves in (parent-only check would allow).

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Task 10 — Wire `updateItemData` to enforce ownership (TDD)

**Files:**
- Modify: `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceUpdateDataTest.java`
- Modify: `src/main/java/com/myxcomp/ice/xtree/service/ItemService.java`

- [ ] **Step 1: Write the failing tests.**

In `ItemServiceUpdateDataTest.java`, add `@Nested class Ownership`:

```java
@Nested
class Ownership {

    private final UserContext ctx = new UserContext("alice", null);
    private final java.time.Instant T = java.time.Instant.parse("2026-05-25T10:00:00Z");
    private final CachedNode target = new CachedNode(50L, 10L, "Report1", "Report", T, "sys");
    private final CachedNode aliceHome = new CachedNode(10L, 2L, "alice", "Folder", T, "sys");

    @org.junit.jupiter.api.BeforeEach
    void stubCommon() {
        lenient().when(cache.getById(50L)).thenReturn(Optional.of(target));
        lenient().when(policy.isKnown("Report")).thenReturn(true);
        lenient().when(policy.hasData("Report")).thenReturn(true);
        lenient().when(policy.isAlsoPersistedAsXmlOnWrite("Report")).thenReturn(false);
    }

    @Test
    void updatingItemNotInUserHomeRejectsWith403() {
        when(ownershipChecker.requireHomeFolderExists("alice")).thenReturn(aliceHome);
        org.mockito.Mockito.doThrow(new com.myxcomp.ice.xtree.service.exception.ForbiddenException(
                ErrorCode.NOT_IN_USER_FOLDER, "Item 50 is not under home folder of 'alice'"))
                .when(ownershipChecker).requireOwned(50L, aliceHome, "alice", "Item");

        assertThatThrownBy(() -> service.updateItemData(50L, "{\"x\":1}", ctx))
                .isInstanceOf(com.myxcomp.ice.xtree.service.exception.ForbiddenException.class)
                .satisfies(e -> assertThat(
                        ((com.myxcomp.ice.xtree.service.exception.ForbiddenException) e).errorCode())
                        .isEqualTo(ErrorCode.NOT_IN_USER_FOLDER));

        verify(repository, never()).updateJson(anyLong(), any(), any(), any(), any());
        verifyNoInteractions(publisher);
    }

    @Test
    void typeValidationFiresBeforeOwnership() {
        // Folder cannot have data → fires first (existing validation order)
        CachedNode folder = new CachedNode(50L, 10L, "F", "Folder", T, "sys");
        when(cache.getById(50L)).thenReturn(Optional.of(folder));
        when(policy.isKnown("Folder")).thenReturn(true);

        assertThatThrownBy(() -> service.updateItemData(50L, "{\"x\":1}", ctx))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).errorCode())
                        .isEqualTo(ErrorCode.FOLDER_CANNOT_HAVE_DATA));

        verifyNoInteractions(ownershipChecker);
    }

    @Test
    void noHomeFolderRejectsWith404() {
        when(ownershipChecker.requireHomeFolderExists("alice")).thenThrow(
                new NotFoundException(ErrorCode.HOME_FOLDER_NOT_FOUND,
                        "No home folder for user 'alice'"));

        assertThatThrownBy(() -> service.updateItemData(50L, "{\"x\":1}", ctx))
                .isInstanceOf(NotFoundException.class);

        verify(repository, never()).updateJson(anyLong(), any(), any(), any(), any());
    }
}
```

- [ ] **Step 2: Run the new tests to verify they fail.**

Run: `./gradlew test --tests 'com.myxcomp.ice.xtree.service.ItemServiceUpdateDataTest$Ownership'`
Expected: FAIL.

- [ ] **Step 3: Add the ownership check to `updateItemData` in `ItemService.java`.**

Locate `updateItemData` (line 267). Insert the new lines **between** the `DATA_REQUIRED` check (~line 293) and the `String xmlOrNull = null;` line (~line 295):

```java
String effectiveUser = userContext.effectiveUser();
CachedNode homeFolder = ownershipChecker.requireHomeFolderExists(effectiveUser);
ownershipChecker.requireOwned(id, homeFolder, effectiveUser, "Item");
```

Then change `String stampUser = userContext.effectiveUser();` (~line 307) to:
```java
String stampUser = effectiveUser;
```

- [ ] **Step 4: Run all `ItemServiceUpdateDataTest` and `ItemServiceMetricsTest` tests.**

Run:
```
./gradlew test --tests 'com.myxcomp.ice.xtree.service.ItemServiceUpdateDataTest' \
               --tests 'com.myxcomp.ice.xtree.service.ItemServiceMetricsTest'
```
Expected: PASS. Fix any happy-path existing tests by stubbing the ownership mocks (`lenient()` style).

- [ ] **Step 5: Commit.**

```bash
git add src/main/java/com/myxcomp/ice/xtree/service/ItemService.java \
        src/test/java/com/myxcomp/ice/xtree/service/ItemServiceUpdateDataTest.java \
        src/test/java/com/myxcomp/ice/xtree/service/ItemServiceMetricsTest.java
git commit -m "feat(phase16): enforce home-folder ownership on updateItemData

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Task 11 — Refactor `copyItem` to use `OwnershipChecker` and `NOT_IN_USER_FOLDER`

This task is a coordinated rename. Production + tests change together; `DESTINATION_NOT_IN_USER_FOLDER` is removed from `ErrorCode` in **Task 12** once all callers are gone.

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/service/ItemService.java`
- Modify: `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceCopyTest.java`

- [ ] **Step 1: Update `copyItem` to use the checker and the new error code.**

In `ItemService.java`, locate `copyItem` (line 460). Replace **steps 5 and 6** (lines ~492–508):

**Before:**
```java
// 5. HOME_FOLDER_NOT_FOUND
String effectiveUser = userContext.effectiveUser();
CachedNode homeFolder = cache.findHomeFolder(effectiveUser).orElseThrow(() -> {
    recordCopyRejection(ErrorCode.HOME_FOLDER_NOT_FOUND);
    return new NotFoundException(ErrorCode.HOME_FOLDER_NOT_FOUND,
            "No home folder for user '" + effectiveUser + "'");
});

// 6. DESTINATION_NOT_IN_USER_FOLDER
boolean destInUserFolder = destination.itemTreeId() == homeFolder.itemTreeId()
        || cache.isAncestor(homeFolder.itemTreeId(), destination.itemTreeId());
if (!destInUserFolder) {
    recordCopyRejection(ErrorCode.DESTINATION_NOT_IN_USER_FOLDER);
    throw new ValidationException(ErrorCode.DESTINATION_NOT_IN_USER_FOLDER,
            "Destination " + destinationFolderId
                    + " is not under home folder of '" + effectiveUser + "'");
}
```

**After:**
```java
// 5. HOME_FOLDER_NOT_FOUND
String effectiveUser = userContext.effectiveUser();
CachedNode homeFolder;
try {
    homeFolder = ownershipChecker.requireHomeFolderExists(effectiveUser);
} catch (NotFoundException e) {
    recordCopyRejection(ErrorCode.HOME_FOLDER_NOT_FOUND);
    throw e;
}

// 6. NOT_IN_USER_FOLDER (was DESTINATION_NOT_IN_USER_FOLDER, now 403)
try {
    ownershipChecker.requireOwned(destinationFolderId, homeFolder, effectiveUser, "Destination");
} catch (com.myxcomp.ice.xtree.service.exception.ForbiddenException e) {
    recordCopyRejection(ErrorCode.NOT_IN_USER_FOLDER);
    throw e;
}
```

Also update the Javadoc comment above `copyItem` (line 453–458):
```java
/**
 * Copies the subtree rooted at {@code sourceId} under {@code destinationFolderId}.
 * Validation order: ITEM_NOT_FOUND, CANNOT_COPY_ROOT, DESTINATION_NOT_FOUND,
 * DESTINATION_NOT_FOLDER, HOME_FOLDER_NOT_FOUND, NOT_IN_USER_FOLDER,
 * COPY_INTO_DESCENDANT, COPY_TOO_LARGE. Write order: DB → cache → event.
 */
```

Add the import for `ForbiddenException` at the top of the file:
```java
import com.myxcomp.ice.xtree.service.exception.ForbiddenException;
```

(Then you can drop the inline fully-qualified name in the catch.)

- [ ] **Step 2: Update `ItemServiceCopyTest.destinationNotInUserFolder` to expect the new exception + error code.**

Edit `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceCopyTest.java`, lines 220–233. The test currently stubs `cache.findHomeFolder` and `cache.isAncestor` directly. Switch to stubbing the `ownershipChecker` mock added in Task 5.

Add an import (if not present):
```java
import com.myxcomp.ice.xtree.service.exception.ForbiddenException;
```

Rewrite the test:

```java
@Test
void destinationNotInUserFolder() {
    CachedNode source = new CachedNode(50L, 99L, "X", "Report", T, "bob");
    CachedNode dest = new CachedNode(10L, 1L, "other", "Folder", T, "x");
    CachedNode home = new CachedNode(20L, 1L, "alice", "Folder", T, "alice");
    when(cache.getById(50L)).thenReturn(Optional.of(source));
    when(cache.getById(10L)).thenReturn(Optional.of(dest));
    when(ownershipChecker.requireHomeFolderExists("alice")).thenReturn(home);
    org.mockito.Mockito.doThrow(new ForbiddenException(
            ErrorCode.NOT_IN_USER_FOLDER,
            "Destination 10 is not under home folder of 'alice'"))
            .when(ownershipChecker).requireOwned(10L, home, "alice", "Destination");

    assertThatThrownBy(() -> service.copyItem(50L, 10L, ctx))
            .isInstanceOf(ForbiddenException.class)
            .satisfies(e -> assertThat(((ForbiddenException) e).errorCode())
                    .isEqualTo(ErrorCode.NOT_IN_USER_FOLDER));
}
```

Also locate the test `homeFolderNotFound` (lines 207–218) — switch it from `cache.findHomeFolder(...).thenReturn(Optional.empty())` to:
```java
when(ownershipChecker.requireHomeFolderExists("alice")).thenThrow(
        new NotFoundException(ErrorCode.HOME_FOLDER_NOT_FOUND,
                "No home folder for user 'alice'"));
```

Locate the happy-path tests (e.g. `happyPathSingleItemCopy` line 87) and existing tests that stub `cache.findHomeFolder` (`copyIntoSelf` line 236 etc.). Replace each `when(cache.findHomeFolder(...)).thenReturn(Optional.of(...))` with the equivalent `when(ownershipChecker.requireHomeFolderExists(...)).thenReturn(...)`. The `cache.isAncestor` stubs that drive ownership outcomes are no longer consulted by `copyItem` itself (the checker is mocked), so those can be removed where they were only feeding the ownership branch. Leave `cache.isAncestor` stubs for `COPY_INTO_DESCENDANT` checks alone.

- [ ] **Step 3: Run `ItemServiceCopyTest` to verify all tests pass.**

Run: `./gradlew test --tests 'com.myxcomp.ice.xtree.service.ItemServiceCopyTest'`
Expected: PASS.

- [ ] **Step 4: Commit.**

```bash
git add src/main/java/com/myxcomp/ice/xtree/service/ItemService.java \
        src/test/java/com/myxcomp/ice/xtree/service/ItemServiceCopyTest.java
git commit -m "refactor(phase16): copyItem uses OwnershipChecker + NOT_IN_USER_FOLDER

Replaces inline findHomeFolder + isAncestor + ValidationException with
ownershipChecker.requireHomeFolderExists + requireOwned. Error code
NOT_IN_USER_FOLDER (HTTP 403) replaces DESTINATION_NOT_IN_USER_FOLDER
(HTTP 400). The old enum value is removed in the next commit.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Task 12 — Remove `DESTINATION_NOT_IN_USER_FOLDER` enum value and dangling references

After Task 11, the production code no longer references the old code. Two test files still do.

**Files:**
- Modify: `src/test/java/com/myxcomp/ice/xtree/api/controller/ItemControllerTest.java`
- Modify: `src/test/java/com/myxcomp/ice/xtree/service/exception/ErrorCodeTest.java`
- Modify: `src/main/java/com/myxcomp/ice/xtree/service/exception/ErrorCode.java`

- [ ] **Step 1: Update `ItemControllerTest.CopyItem.serviceErrorCases` to use the new code.**

Edit `src/test/java/com/myxcomp/ice/xtree/api/controller/ItemControllerTest.java` around line 464–465. Replace:

```java
org.junit.jupiter.params.provider.Arguments.of(
    new ValidationException(ErrorCode.DESTINATION_NOT_IN_USER_FOLDER, "x"), 400, "DESTINATION_NOT_IN_USER_FOLDER"),
```

with:

```java
org.junit.jupiter.params.provider.Arguments.of(
    new com.myxcomp.ice.xtree.service.exception.ForbiddenException(
            ErrorCode.NOT_IN_USER_FOLDER, "x"), 403, "NOT_IN_USER_FOLDER"),
```

(If `ForbiddenException` isn't yet imported at the top of the file, add the import.)

- [ ] **Step 2: Update `ErrorCodeTest`.**

Edit `src/test/java/com/myxcomp/ice/xtree/service/exception/ErrorCodeTest.java`:

- In `EXPECTED_NAMES` (lines 15-34), remove the `"DESTINATION_NOT_IN_USER_FOLDER"` entry.
- In `copyErrorCodesExist` `@ValueSource` (line 43-50), remove the `"DESTINATION_NOT_IN_USER_FOLDER"` entry.

Final `EXPECTED_NAMES`:
```java
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
        "HOME_FOLDER_NOT_FOUND",
        "INVALID_SEARCH_PARAMS",
        "DATA_NOT_SERIALISABLE",
        "CANNOT_COPY_ROOT",
        "DESTINATION_NOT_FOUND",
        "DESTINATION_NOT_FOLDER",
        "COPY_INTO_DESCENDANT",
        "COPY_TOO_LARGE",
        "NOT_IN_USER_FOLDER"
);
```

Final `@ValueSource`:
```java
@ValueSource(strings = {
    "CANNOT_COPY_ROOT",
    "DESTINATION_NOT_FOUND",
    "DESTINATION_NOT_FOLDER",
    "COPY_INTO_DESCENDANT",
    "COPY_TOO_LARGE"
})
```

- [ ] **Step 3: Remove the enum constant from `ErrorCode.java`.**

Delete the `DESTINATION_NOT_IN_USER_FOLDER,` line. Final enum:

```java
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
    COPY_INTO_DESCENDANT,
    COPY_TOO_LARGE,
    NOT_IN_USER_FOLDER
}
```

- [ ] **Step 4: Verify no stragglers and run the full suite.**

Run: `grep -rn "DESTINATION_NOT_IN_USER_FOLDER" src/ 2>&1`
Expected: no matches.

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 5: Commit.**

```bash
git add src/main/java/com/myxcomp/ice/xtree/service/exception/ErrorCode.java \
        src/test/java/com/myxcomp/ice/xtree/service/exception/ErrorCodeTest.java \
        src/test/java/com/myxcomp/ice/xtree/api/controller/ItemControllerTest.java
git commit -m "refactor(phase16): remove DESTINATION_NOT_IN_USER_FOLDER enum value

Replaced by NOT_IN_USER_FOLDER (403/Forbidden) across all callers.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Task 13 — Add 403 mapping tests for the 5 mutation controller endpoints

**Files:**
- Modify: `src/test/java/com/myxcomp/ice/xtree/api/controller/ItemControllerTest.java`

- [ ] **Step 1: Add 5 new `@Test` methods at the class scope (one per mutation), demonstrating that a `ForbiddenException` thrown by the service produces HTTP 403 with `errorCode=NOT_IN_USER_FOLDER`.**

Add these after the existing `create*` / `delete*` / `move*` / `rename*` / `update*` tests respectively (the file is organised by endpoint — slot each new test next to its peers):

```java
// inside ItemControllerTest, top-level (not nested)

@Test
void createForbiddenReturns403WithErrorCode() throws Exception {
    when(itemService.createItem(anyLong(), any(), any(), any(), any()))
            .thenThrow(new com.myxcomp.ice.xtree.service.exception.ForbiddenException(
                    ErrorCode.NOT_IN_USER_FOLDER, "Parent 2 is not under home folder of 'alice'"));

    mvc.perform(post("/api/v1/itemtree/items")
                    .header("X-Ice-User", "alice")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"parentId\":2,\"name\":\"x\",\"type\":\"Folder\"}"))
            .andExpect(status().isForbidden())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.errorCode").value("NOT_IN_USER_FOLDER"))
            .andExpect(jsonPath("$.status").value(403))
            .andExpect(jsonPath("$.detail")
                    .value(org.hamcrest.Matchers.containsString("home folder of 'alice'")));
}

@Test
void deleteForbiddenReturns403WithErrorCode() throws Exception {
    org.mockito.Mockito.doThrow(new com.myxcomp.ice.xtree.service.exception.ForbiddenException(
            ErrorCode.NOT_IN_USER_FOLDER, "Item 99 is not under home folder of 'alice'"))
            .when(itemService).deleteItem(eq(99L), any(UserContext.class));

    mvc.perform(delete("/api/v1/itemtree/items/99")
                    .header("X-Ice-User", "alice"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("NOT_IN_USER_FOLDER"))
            .andExpect(jsonPath("$.status").value(403));
}

@Test
void renameForbiddenReturns403WithErrorCode() throws Exception {
    when(itemService.renameItem(eq(99L), any(), any(UserContext.class)))
            .thenThrow(new com.myxcomp.ice.xtree.service.exception.ForbiddenException(
                    ErrorCode.NOT_IN_USER_FOLDER, "Item 99 is not under home folder of 'alice'"));

    mvc.perform(post("/api/v1/itemtree/items/99/rename")
                    .header("X-Ice-User", "alice")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"newName\":\"x\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("NOT_IN_USER_FOLDER"));
}

@Test
void moveForbiddenReturns403WithErrorCode() throws Exception {
    when(itemService.moveItem(eq(99L), eq(2L), any(UserContext.class)))
            .thenThrow(new com.myxcomp.ice.xtree.service.exception.ForbiddenException(
                    ErrorCode.NOT_IN_USER_FOLDER, "Source 99 is not under home folder of 'alice'"));

    mvc.perform(post("/api/v1/itemtree/items/99/move")
                    .header("X-Ice-User", "alice")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"newParentId\":2}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("NOT_IN_USER_FOLDER"));
}

@Test
void updateDataForbiddenReturns403WithErrorCode() throws Exception {
    when(itemService.updateItemData(eq(99L), any(), any(UserContext.class)))
            .thenThrow(new com.myxcomp.ice.xtree.service.exception.ForbiddenException(
                    ErrorCode.NOT_IN_USER_FOLDER, "Item 99 is not under home folder of 'alice'"));

    mvc.perform(put("/api/v1/itemtree/items/99/data")
                    .header("X-Ice-User", "alice")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"data\":{\"x\":1}}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("NOT_IN_USER_FOLDER"));
}
```

Required imports (verify all present; the file uses static MockMvc imports):
```java
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
```

- [ ] **Step 2: Run all `ItemControllerTest` tests.**

Run: `./gradlew test --tests 'com.myxcomp.ice.xtree.api.controller.ItemControllerTest'`
Expected: PASS — 5 new tests green + existing tests green (the `CopyItem.serviceErrorCases` parameterized test should already include `NOT_IN_USER_FOLDER` from Task 12).

- [ ] **Step 3: Commit.**

```bash
git add src/test/java/com/myxcomp/ice/xtree/api/controller/ItemControllerTest.java
git commit -m "test(phase16): assert HTTP 403 + NOT_IN_USER_FOLDER per mutation endpoint

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Task 14 — Add E2E test `mutationsForbiddenOutsideOwnFolder`

Adds one new test to `ItemTreeApplicationE2EIT` (existing two-context fixture). Asserts that the server rejects a cross-user mutation at the HTTP layer, no DB row is created, and no event reaches the peer cache.

**Files:**
- Modify: `src/test/java/com/myxcomp/ice/xtree/e2e/ItemTreeApplicationE2EIT.java`

- [ ] **Step 1: Open `ItemTreeApplicationE2EIT.java` and review existing tests** to find the helper for issuing an HTTP request via context A (`A.mvc().perform(...)` or similar) and for cleaning up created rows in `@AfterEach`. Reuse those helpers.

- [ ] **Step 2: Add the failing test.**

Append this method to the class (next to other `@Test` methods, e.g. after `mutationPropagatesAcrossInstances`):

```java
@Test
void mutationsForbiddenOutsideOwnFolder() throws Exception {
    // testuser1's home = id 10; testuser2's home = id 11. Seed data §3.
    // Attempt: as testuser1, create an item under testuser2's home folder.

    var responseA = A.mvc().perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .post("/api/v1/itemtree/items")
                    .header("X-Ice-User", "testuser1")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .content("{\"parentId\":11,\"name\":\"hostile\",\"type\":\"Folder\"}"))
            .andReturn().getResponse();

    assertThat(responseA.getStatus()).isEqualTo(403);
    assertThat(responseA.getContentType())
            .isEqualTo(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    assertThat(responseA.getContentAsString())
            .contains("NOT_IN_USER_FOLDER")
            .contains("testuser1");

    // No DB row created (search by exact name returns nothing on either context):
    assertThat(A.searchByName("hostile")).isEmpty();
    assertThat(B.searchByName("hostile")).isEmpty();

    // No event reached peer cache (peer B should have no node named "hostile"
    // under id 11). cache.getChildren returns CachedNode list:
    assertThat(B.cache().getChildren(11L))
            .extracting(com.myxcomp.ice.xtree.cache.CachedNode::name)
            .doesNotContain("hostile");
}
```

(Adjust helper names — `A.mvc()`, `A.searchByName(...)`, `B.cache()` — to match the actual `TwoInstanceContexts` API. If `TwoInstanceContexts` exposes contexts differently, use the existing access pattern shown by `mutationPropagatesAcrossInstances`.)

- [ ] **Step 3: Run the test.**

Run: `./gradlew test --tests 'com.myxcomp.ice.xtree.e2e.ItemTreeApplicationE2EIT.mutationsForbiddenOutsideOwnFolder'`
Expected: PASS (the production code already enforces the check after Tasks 6–12).

If the cache returns a populated value for `getChildren(11L)` from seed data (e.g. testuser2 already has a child), the assertion `doesNotContain("hostile")` still holds because the create was rejected. The test is robust.

- [ ] **Step 4: Run all E2E tests as a regression check.**

Run: `./gradlew test --tests 'com.myxcomp.ice.xtree.e2e.*'`
Expected: PASS.

- [ ] **Step 5: Commit.**

```bash
git add src/test/java/com/myxcomp/ice/xtree/e2e/ItemTreeApplicationE2EIT.java
git commit -m "test(phase16): E2E test for cross-user mutation rejection (403)

testuser1 attempts to create under testuser2's home folder via context
A's HTTP layer; server returns 403 NOT_IN_USER_FOLDER; no DB row, no
event propagation to context B.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Task 15 — Update `ObservabilityExposureIT` for the renamed reason tag

The `itemtree.copy.rejected` counter previously emitted `reason=DESTINATION_NOT_IN_USER_FOLDER`. After this phase it emits `reason=NOT_IN_USER_FOLDER`. If `ObservabilityExposureIT` references the old tag, fix it.

**Files:**
- Modify (maybe): `src/test/java/com/myxcomp/ice/xtree/e2e/ObservabilityExposureIT.java`

- [ ] **Step 1: Check whether the test references the old tag.**

Run: `grep -n "DESTINATION_NOT_IN_USER_FOLDER\|NOT_IN_USER_FOLDER\|copy.rejected" src/test/java/com/myxcomp/ice/xtree/e2e/ObservabilityExposureIT.java`

- [ ] **Step 2: If a match is found, update tag value `DESTINATION_NOT_IN_USER_FOLDER` → `NOT_IN_USER_FOLDER` in the Prometheus-line assertion. If no match (the test doesn't exercise this rejection reason), skip to Step 4.**

Example edit (hypothetical):
```java
// before
.contains("itemtree_copy_rejected_total{reason=\"DESTINATION_NOT_IN_USER_FOLDER\"")
// after
.contains("itemtree_copy_rejected_total{reason=\"NOT_IN_USER_FOLDER\"")
```

- [ ] **Step 3: Run the full E2E test class.**

Run: `./gradlew test --tests 'com.myxcomp.ice.xtree.e2e.ObservabilityExposureIT'`
Expected: PASS.

- [ ] **Step 4: Commit (if any edit was made).**

```bash
git add src/test/java/com/myxcomp/ice/xtree/e2e/ObservabilityExposureIT.java
git commit -m "test(phase16): update copy.rejected reason tag in observability IT

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

If no edit was needed, skip the commit.

---

## Task 16 — Update CLAUDE.md (delete the contradicted invariant)

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Remove the contradicted line.**

Open `CLAUDE.md`. Find the bullet under "Things to NEVER do":
```
- Add per-user authorization checks — UI enforces permissions.
```

Delete the entire bullet line.

- [ ] **Step 2: Verify no other CLAUDE.md content contradicts the new policy.**

Run: `grep -in "permission\|authoriz\|enforce" CLAUDE.md 2>&1`
Skim the output; ensure nothing else asserts "UI enforces permissions" or similar as a project invariant.

- [ ] **Step 3: Commit.**

```bash
git add CLAUDE.md
git commit -m "docs(phase16): remove obsolete 'UI enforces permissions' invariant

Phase 16 introduces server-side home-folder ownership enforcement on
all 6 mutation endpoints. The 'never add per-user authorization' rule
is replaced by the new policy; see itemtree-service-design.md §3, §13.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Task 17 — Update `itemtree-service-design.md` (§3 and §13)

**Files:**
- Modify: `itemtree-service-design.md`

- [ ] **Step 1: §3 "Validation rules" — add ownership bullet, remove the obsolete bullet.**

Find the "Validation rules" subsection in §3 (around line 171). The current bullet list ends with:
```
- Cascade delete is unbounded; permissions are enforced UI-side.
```

Delete that bullet. Add this new bullet at the end of the list:

```
- **Server-side home-folder ownership.** All 6 mutation endpoints (`create`, `delete`, `rename`, `move`, `update`, `copy`) enforce that the target item and/or destination folder is inside the effective user's home subtree. Returns 403 `NOT_IN_USER_FOLDER` on violation. `move` checks both source and new parent; `copy` checks destination only (source is unrestricted). See §13 "Home-folder ownership enforcement".
```

- [ ] **Step 2: §13 (Identity) — append a new subsection.**

After the existing §13 content (ending around line 974), append:

```markdown
### Home-folder ownership enforcement

(Added in Phase 16.) All mutation endpoints enforce that the caller can only modify items inside their own home-folder subtree.

**Ownership rule.** Item X is owned by user U iff `X.itemTreeId == homeFolder(U).itemTreeId` or `homeFolder(U)` is an ancestor of X.

**Per-operation checks** (effective user = impersonated if present, else iceUser):

| Operation | Check |
|---|---|
| `create` | parent must be owned |
| `delete` | item must be owned (cache miss → silent no-op, no auth check) |
| `rename` | item must be owned |
| `move` | both source AND new parent must be owned |
| `update` | item must be owned |
| `copy` | destination must be owned (source unrestricted) |

**No home folder → no mutations.** If the effective user has no home folder, every mutation fails with 404 `HOME_FOLDER_NOT_FOUND`. There is no admin/system bypass.

**Enforcement location.** `service/OwnershipChecker` (Spring `@Component`). Throws `ForbiddenException` (403, `NOT_IN_USER_FOLDER`) or `NotFoundException` (404, `HOME_FOLDER_NOT_FOUND`).
```

- [ ] **Step 3: §3 error-code list — add NOT_IN_USER_FOLDER, remove DESTINATION_NOT_IN_USER_FOLDER.**

If §3 (or anywhere in the design) maintains an enumerated list of error codes that mirrors the `ErrorCode` enum, update it: add `NOT_IN_USER_FOLDER`, remove `DESTINATION_NOT_IN_USER_FOLDER`. Search:

```
grep -n "DESTINATION_NOT_IN_USER_FOLDER\|NOT_IN_USER_FOLDER" itemtree-service-design.md
```

Fix any matches accordingly. (If no matches in the design doc — the enum may not be enumerated there — skip.)

- [ ] **Step 4: Commit.**

```bash
git add itemtree-service-design.md
git commit -m "docs(phase16): document home-folder ownership in design §3 and §13

Adds new \"Home-folder ownership enforcement\" subsection under §13.
Adds ownership bullet to §3 Validation rules. Removes the obsolete
\"Cascade delete is unbounded; permissions are enforced UI-side\" bullet.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Task 18 — Rename existing Phase 16 → Phase 17 in IMPLEMENTATION_NOTES.md and insert new Phase 16 section

**Files:**
- Modify: `IMPLEMENTATION_NOTES.md`

- [ ] **Step 1: Rename the existing heading.**

Edit `IMPLEMENTATION_NOTES.md`. Find the heading (~line 592):
```
## Phase 16 — Work PC wiring (Phase B, user-managed)
```
Change to:
```
## Phase 17 — Work PC wiring (Phase B, user-managed)
```

(The body content is unchanged.)

- [ ] **Step 2: Insert the new Phase 16 section.**

Insert this block **immediately before** the renamed `## Phase 17 — Work PC wiring ...` heading:

```markdown
## Phase 16 — User-folder ownership enforcement ✅ COMPLETE (2026-05-25)

**Goal:** Enforce, server-side, that authenticated users can only mutate items inside their own home-folder subtree. Applies to all 6 mutation endpoints: `createItem`, `deleteItem`, `renameItem`, `moveItem`, `updateItemData`, `copyItem`. Full design in `docs/superpowers/specs/2026-05-25-user-folder-ownership-design.md`; implementation plan in `docs/superpowers/plans/2026-05-25-phase16-user-folder-ownership.md`.

**Implementable end-to-end in Phase A.** No work-PC blockers; pure service-layer + HTTP-layer change against existing cache and exception model.

### Surface

- **New** `service/OwnershipChecker` `@Component` (TreeCache-injected) with `requireHomeFolderExists(user)` and `requireOwned(itemId, home, user, label)`.
- **New** `service/exception/ForbiddenException` (HTTP 403) carrying an `ErrorCode`.
- **New** `ErrorCode.NOT_IN_USER_FOLDER`; **removed** `ErrorCode.DESTINATION_NOT_IN_USER_FOLDER` (copy now uses the new generic code at 403 instead of 400).
- **`ItemService`** — `OwnershipChecker` constructor-injected; each of 5 mutations gains 2–4 lines for the check (`copyItem` refactored to delegate to the checker, including a `recordCopyRejection(NOT_IN_USER_FOLDER)` path). `deleteItem` now probes the cache before the DB (preserves no-op for missing ids; tightens up the drift window).
- **`GlobalExceptionHandler`** — new `@ExceptionHandler(ForbiddenException.class)` → 403 + `application/problem+json`.
- **Doc updates.** `CLAUDE.md` "Things to NEVER do" bullet removed; `itemtree-service-design.md` §3 + §13 updated.

### Done when

- All §7 tests of the spec green; ~30–40 new test executions; total ~660.
- `./gradlew clean build` → BUILD SUCCESSFUL.
- Manual smoke (spec §8) passes against dev profile.
- Memory note added: `project-phase16-ownership-done.md`.
```

- [ ] **Step 3: Commit.**

```bash
git add IMPLEMENTATION_NOTES.md
git commit -m "docs(phase16): rename Phase 16 (Work PC wiring) to Phase 17; insert new Phase 16

Phase 16 is now User-folder ownership enforcement. The Work PC wiring
work moves to Phase 17 with body unchanged.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Task 19 — Full build + manual smoke test

**Files:** (none modified in this task; verification only)

- [ ] **Step 1: Run the full build to confirm everything compiles and tests pass.**

Run: `./gradlew clean build`
Expected: BUILD SUCCESSFUL; total test executions ≈ 660 (up from 627 at start of Phase 16).

If anything fails: diagnose and fix. Do **not** mark this task complete with red tests.

- [ ] **Step 2: Start the app in the dev profile.**

Run: `./gradlew bootRun` (background) or invoke via the `run` skill. Wait until logs show `Started ItemTreeApplication` and `CacheReadinessGate: READY`.

- [ ] **Step 3: Manual smoke scenarios (spec §8). Use the Phase 15 test UI at `http://localhost:8080/` or `curl`.**

Set X-Ice-User to `testuser1`. Testuser1's home folder id is `10`; testuser2's home folder id is `11`; root's id is `1`.

a) **Allowed: create under own home.**
```
curl -s -X POST http://localhost:8080/api/v1/itemtree/items \
     -H "X-Ice-User: testuser1" \
     -H "Content-Type: application/json" \
     -d '{"parentId":10,"name":"smoke-own","type":"Folder"}' | jq
```
Expected: HTTP 201 + `ItemNode` body with `itemTreeId` ≥ 100000.

b) **Forbidden: create under testuser2's home.**
```
curl -s -i -X POST http://localhost:8080/api/v1/itemtree/items \
     -H "X-Ice-User: testuser1" \
     -H "Content-Type: application/json" \
     -d '{"parentId":11,"name":"smoke-hostile","type":"Folder"}'
```
Expected: HTTP 403 + `Content-Type: application/problem+json` + `"errorCode":"NOT_IN_USER_FOLDER"` + `detail` containing `testuser1` and `11`.

c) **Forbidden: delete a node in testuser2's subtree** — create one as testuser2 first, then attempt deletion as testuser1.
```
# as testuser2 first
NEW_ID=$(curl -s -X POST http://localhost:8080/api/v1/itemtree/items \
     -H "X-Ice-User: testuser2" \
     -H "Content-Type: application/json" \
     -d '{"parentId":11,"name":"smoke-target","type":"Folder"}' | jq -r .itemTreeId)
# as testuser1 trying to delete
curl -s -i -X DELETE http://localhost:8080/api/v1/itemtree/items/$NEW_ID \
     -H "X-Ice-User: testuser1"
```
Expected: HTTP 403, `NOT_IN_USER_FOLDER`.

d) **Allowed: copy from testuser2's subtree into testuser1's subtree.** (Source unrestricted.)
```
curl -s -i -X POST http://localhost:8080/api/v1/itemtree/items/$NEW_ID/copy \
     -H "X-Ice-User: testuser1" \
     -H "Content-Type: application/json" \
     -d '{"destinationFolderId":10}'
```
Expected: HTTP 201.

e) **UI smoke.** Open the test UI; log in as `testuser1`; trigger one of the forbidden actions; verify the toast renders cleanly with the title, `errorCode` chip, and `detail` line.

- [ ] **Step 4: Stop the app.**

If started in the background: kill it (Ctrl-C if foreground, or via the run-skill stop command).

- [ ] **Step 5: No code commits in this task** — manual verification only. Proceed to Task 20.

---

## Task 20 — Add memory note `project-phase16-ownership-done.md`

**Files:**
- Create: `/home/dave/.claude/projects/-home-dave-Git-item-tree/memory/project-phase16-ownership-done.md`
- Modify: `/home/dave/.claude/projects/-home-dave-Git-item-tree/memory/MEMORY.md`

- [ ] **Step 1: Write the memory file.**

Create `/home/dave/.claude/projects/-home-dave-Git-item-tree/memory/project-phase16-ownership-done.md`:

```markdown
---
name: project-phase16-ownership-done
description: Phase 16 (server-side home-folder ownership enforcement) complete; six mutations gated by OwnershipChecker; DESTINATION_NOT_IN_USER_FOLDER removed in favour of NOT_IN_USER_FOLDER (403); CLAUDE.md "UI enforces permissions" rule reversed.
metadata:
  type: project
---

Phase 16 done as of 2026-05-25. Server-side home-folder ownership enforced on all 6 mutation endpoints (create / delete / rename / move / update-data / copy).

**Why:** the previous design held that "permissions are enforced UI-side" (CLAUDE.md "Things to NEVER do", design §3). Phase 16 explicitly reverses that — the server is now the authority. UI continues to display rejections via the existing toast.

**How to apply:** Future mutation work should pass through `service/OwnershipChecker`. New mutation endpoints (if any are added) need an ownership step. `ForbiddenException` (HTTP 403) is the new wire shape; reach for it whenever a mutation rejects on auth grounds. Read-side endpoints remain unrestricted by design.

**Test count after Phase 16:** ~660 (up from 627). `./gradlew clean build` → BUILD SUCCESSFUL.

See [[project-phase14-done]] for the related copy-item phase (the ownership pattern originated there with `DESTINATION_NOT_IN_USER_FOLDER`; Phase 16 generalised it).
```

- [ ] **Step 2: Add a one-line entry to MEMORY.md.**

Open `/home/dave/.claude/projects/-home-dave-Git-item-tree/memory/MEMORY.md`. Append at the end of the bullet list:

```
- [Phase 16 user-folder ownership complete](project-phase16-ownership-done.md) — Phase 16 done as of 2026-05-25; server-side ownership on all 6 mutations via OwnershipChecker; new ForbiddenException/NOT_IN_USER_FOLDER (403); CLAUDE.md "UI enforces permissions" rule reversed
```

- [ ] **Step 3: No git commit** — memory files live outside the project repo. Done.

---

## Task 21 — Final sanity check and wrap-up

- [ ] **Step 1: Confirm we're still on the right branch.**

Run: `git branch --show-current`
Expected: `phase-16`.

- [ ] **Step 2: Review the commit log for Phase 16.**

Run: `git log --oneline e39ec55..HEAD`
Expected: ~16 conventional-commit-style commits starting from the spec doc commit, ending with the IMPLEMENTATION_NOTES rename.

- [ ] **Step 3: Final full test run.**

Run: `./gradlew clean test`
Expected: BUILD SUCCESSFUL with ~660 test executions.

- [ ] **Step 4: Report completion to the user with: test count, branch, that all 21 tasks are done.**

No commit in this task — informational only.

---

## Self-review

Run through the spec sections against the plan:

| Spec section | Covered by |
|---|---|
| §1 Goal + policy shift | Tasks 16, 17, 18 (CLAUDE.md, design doc, IMPLEMENTATION_NOTES) |
| §2 Ownership rule | Task 4 (`OwnershipChecker`) |
| §3.1 createItem | Task 6 |
| §3.2 deleteItem (cache-probe shift) | Task 7 |
| §3.3 renameItem | Task 8 |
| §3.4 moveItem (both checks) | Task 9 |
| §3.5 updateItemData | Task 10 |
| §3.6 copyItem refactor | Tasks 11, 12 |
| §4.1 ForbiddenException, NOT_IN_USER_FOLDER, OwnershipChecker | Tasks 1, 2, 4 |
| §4.2 remove DESTINATION_NOT_IN_USER_FOLDER | Task 12 |
| §4.3 ItemService constructor + per-op | Task 5 + Tasks 6–11 |
| §4.4 GlobalExceptionHandler | Task 3 |
| §4.5 Metrics tag rename | Tasks 11, 15 |
| §5 UI minimal change | Task 19 step 3e (manual smoke verifies; no code change needed) |
| §6 Doc updates | Tasks 16, 17, 18 |
| §7 Tests | Tasks 1, 2, 3, 4, 6–14 |
| §8 Done-when | Task 19 (smoke), Task 20 (memory note) |

**Placeholder scan:** none found in tasks. Each step shows code or commands; no "TODO" / "TBD" / "similar to Task N".

**Type consistency:** `OwnershipChecker.requireHomeFolderExists(String)` and `requireOwned(long, CachedNode, String, String)` signatures are identical across Tasks 4, 5, 6–11. `ForbiddenException(ErrorCode, String)` constructor is identical across Tasks 2, 3, 6–11, 13, 14. `ErrorCode.NOT_IN_USER_FOLDER` consistently spelled. Context labels (`"Parent"`, `"Item"`, `"Source"`, `"New parent"`, `"Destination"`) consistent between production code (Tasks 6–11) and test expectations.

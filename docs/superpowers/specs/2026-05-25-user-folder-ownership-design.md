# Phase 16 — User-folder ownership enforcement

**Status:** approved 2026-05-25
**Branch:** `phase-16`
**Companion phase rename:** existing "Phase 16 — Work PC wiring" becomes "Phase 17 — Work PC wiring" (same content, new heading number).

---

## 1. Goal

Enforce, at the **service layer**, that an authenticated user can only mutate items inside their own home-folder subtree. Six mutation operations are affected: `createItem`, `deleteItem`, `renameItem`, `moveItem`, `updateItemData`, `copyItem`.

The check is **server-side** (not advisory). Violations return **HTTP 403** with `errorCode = NOT_IN_USER_FOLDER`.

This is a **policy reversal**: prior to this phase, the design held that "permissions are enforced UI-side" (design §3, CLAUDE.md "Things to NEVER do"). Phase 16 supersedes that. The UI may continue to gate its own widgets, but the server is now the source of truth.

---

## 2. Ownership rule

**Item X is owned by user U** iff one of:

- `X.itemTreeId == homeFolder(U).itemTreeId`  (X is U's home folder itself), or
- `cache.isAncestor(homeFolder(U).itemTreeId, X.itemTreeId) == true`  (X is anywhere in U's home subtree).

where:

- `homeFolder(U)` is the existing `TreeCache.findHomeFolder(effectiveUser)` lookup — a subfolder of root with `type = Folder` and `name = U`.
- `effectiveUser` is the existing `UserContext.effectiveUser()` resolution: impersonated user if header present, else `iceUser` (design §13).

If U has no home folder, **every mutation fails** with `HOME_FOLDER_NOT_FOUND` (404). No system/admin bypass in this phase.

---

## 3. Per-operation enforcement

A single new error code, `NOT_IN_USER_FOLDER`, maps to HTTP 403 via a new `ForbiddenException`. The `Problem.detail` string identifies which item/parent failed the check and for which user.

### 3.1 `createItem(parentId, name, type, data, ctx)`

Validation order (new steps **bolded**):

1. `PARENT_NOT_FOUND` (existing)
2. `PARENT_NOT_FOLDER` (existing)
3. **`HOME_FOLDER_NOT_FOUND` (effectiveUser has a home folder?)**
4. **`NOT_IN_USER_FOLDER` (parent owned by effectiveUser?)**
5. `TYPE_CANNOT_HAVE_DATA` / `DATA_REQUIRED` (existing)

### 3.2 `deleteItem(id, ctx)`

Currently fully tolerant — silent no-op when id is absent from DB. Tolerance is preserved, but the **probe point shifts from DB to cache**: today the method calls `repository.cascadeDeleteSubtree(id)` first and short-circuits if it returns empty. After this phase, the method calls `cache.getById(id)` first. Observable in normal operation: identical (cache and DB are kept in sync). Observable during drift windows: an item present in DB but absent from cache becomes un-deletable until the next refresh — that's correct, because without a cache entry we have no owner to authorize against.

1. `cache.getById(id)`
   - **Absent** → silent no-op (no auth check, no DB call, no event). Same return shape as today.
   - **Present** → continue:
2. **`HOME_FOLDER_NOT_FOUND`**
3. **`NOT_IN_USER_FOLDER` (id owned by effectiveUser?)**
4. `repository.cascadeDeleteSubtree(id)` → cache `applyDelete` → broadcast (existing path)

### 3.3 `renameItem(id, newName, ctx)`

1. `ITEM_NOT_FOUND` (existing)
2. **`HOME_FOLDER_NOT_FOUND`**
3. **`NOT_IN_USER_FOLDER` (id owned by effectiveUser?)**
4. rename (existing)

### 3.4 `moveItem(id, newParentId, ctx)`

Both the moved item **and** the destination parent must be owned. This blocks two leak directions: moving someone else's item into your folder (newParent-only check would allow this), and moving your own item out to another user's folder (source-only check would allow this).

1. `ITEM_NOT_FOUND` (existing)
2. `MOVE_INTO_DESCENDANT` (self check, existing)
3. `NEW_PARENT_NOT_FOUND` (existing)
4. `NEW_PARENT_NOT_FOLDER` (existing)
5. `MOVE_INTO_DESCENDANT` (ancestor walk, existing)
6. **`HOME_FOLDER_NOT_FOUND`**
7. **`NOT_IN_USER_FOLDER` (id owned by effectiveUser? — "source not in your folder")**
8. **`NOT_IN_USER_FOLDER` (newParentId owned by effectiveUser? — "destination not in your folder")**
9. move (existing)

### 3.5 `updateItemData(id, dataJson, ctx)`

1. `ITEM_NOT_FOUND` (existing)
2. `FOLDER_CANNOT_HAVE_DATA` (existing)
3. `TYPE_CANNOT_HAVE_DATA` (existing)
4. `DATA_REQUIRED` (existing)
5. **`HOME_FOLDER_NOT_FOUND`**
6. **`NOT_IN_USER_FOLDER` (id owned by effectiveUser?)**
7. update (existing)

### 3.6 `copyItem(sourceId, destinationFolderId, ctx)` — refactor

Source remains **unrestricted** (existing behaviour: you can copy any item into your own folder). Only the destination is gated. The existing `DESTINATION_NOT_IN_USER_FOLDER` code is **removed** and replaced with the new `NOT_IN_USER_FOLDER` (HTTP 400 → 403 status change).

1. `ITEM_NOT_FOUND` (existing)
2. `CANNOT_COPY_ROOT` (existing)
3. `DESTINATION_NOT_FOUND` (existing)
4. `DESTINATION_NOT_FOLDER` (existing)
5. **`HOME_FOLDER_NOT_FOUND` (existing check, same throw, now routed through `OwnershipChecker`)**
6. **`NOT_IN_USER_FOLDER` (destination owned by effectiveUser?)** — replaces `DESTINATION_NOT_IN_USER_FOLDER`
7. `COPY_INTO_DESCENDANT` (existing)
8. `COPY_TOO_LARGE` (existing)
9. copy (existing)

---

## 4. Code shape

### 4.1 New types

**`service/exception/ForbiddenException`** — extends `ItemTreeException`. Carries an `ErrorCode`. Mapped to HTTP 403 in `GlobalExceptionHandler`. Parallel to `NotFoundException` (404) and `ValidationException` (400).

**`ErrorCode.NOT_IN_USER_FOLDER`** — single new enum constant used by all 6 mutations.

**`service/OwnershipChecker`** — new `@Component` with `TreeCache` constructor-injected. `ItemService` gets `OwnershipChecker` constructor-injected. Two methods:

```java
public CachedNode requireHomeFolderExists(String effectiveUser)
        throws NotFoundException;
// throws NotFoundException(HOME_FOLDER_NOT_FOUND, "No home folder for user '...'")
// returns the resolved home folder so callers don't need to re-lookup

public void requireOwned(long itemId, CachedNode homeFolder, String effectiveUser, String contextLabel)
        throws ForbiddenException;
// throws ForbiddenException(NOT_IN_USER_FOLDER,
//     "<contextLabel> <itemId> is not under home folder of '<effectiveUser>'")
// 'contextLabel' is "Parent", "Item", "Source", "Destination", "New parent" — keeps Problem.detail readable
// Internally uses cache.isAncestor(homeFolder.itemTreeId(), itemId) with the itemId == home short-circuit.
```

Rationale for two methods (not one): home-folder lookup is shared across both checks in a single mutation (move uses it twice for source and newParent — one lookup, two ownership calls).

### 4.2 Removed / renamed

- `ErrorCode.DESTINATION_NOT_IN_USER_FOLDER` — deleted. All callers (`copyItem`, `recordCopyRejection` paths, `ItemControllerTest.CopyItem`, `ItemServiceCopyTest`) updated to use `NOT_IN_USER_FOLDER`.
- The inline `findHomeFolder + isAncestor` block in `copyItem` (lines ~493–508 of `ItemService.java`) is replaced by two `OwnershipChecker` calls.

### 4.3 `ItemService` changes

Each mutation method gains an `OwnershipChecker` call at the step indicated in §3. Lines added per method: ~3–4. Total ~25 lines.

### 4.4 `GlobalExceptionHandler` change

Add `@ExceptionHandler(ForbiddenException.class)` returning HTTP 403, building `ProblemDetail` via the existing `ProblemFactory`.

### 4.5 Metrics

The existing `itemtree.copy.rejected{reason}` counter continues to tick with `reason=NOT_IN_USER_FOLDER` (was `reason=DESTINATION_NOT_IN_USER_FOLDER`). No new counter needed; the renamed-reason tag is the only observable difference.

The existing `itemtree.policy.validation_rejection{reason}` counter is **not** incremented for `NOT_IN_USER_FOLDER` — that counter is for type/data validation, not authorization. A new counter is not added in this phase; the 403 rate is visible via standard HTTP status metrics.

---

## 5. UI (Phase 15 test harness)

Minimal: ensure the existing toast/error handler in `js/api.js` cleanly surfaces the `Problem.detail` text for HTTP 403. If the existing handler already renders `Problem.detail` for 4xx, this is a zero-code-change item — verified via the manual smoke step (§8).

No tree-level gating, no permission-probe in the UI. The UI tries the action and the server returns 403 with a clear `detail` string.

---

## 6. Doc updates (ship with this phase)

### 6.1 `IMPLEMENTATION_NOTES.md`

- Rename current `## Phase 16 — Work PC wiring (Phase B, user-managed)` → `## Phase 17 — Work PC wiring (Phase B, user-managed)` (heading only; body unchanged).
- Insert new `## Phase 16 — User-folder ownership enforcement` section in the old slot, with a one-paragraph summary pointing at this spec file.

### 6.2 `CLAUDE.md`

- "Things to NEVER do" line *"Add per-user authorization checks — UI enforces permissions."* is **deleted** outright. The line is now wrong as a global instruction.
- Optional: add a one-line note under "Critical invariants" saying mutation services enforce home-folder ownership via `OwnershipChecker` (§16 reference).

### 6.3 `itemtree-service-design.md`

- §3 "Validation rules": add bullet — *"All 6 mutation endpoints enforce home-folder ownership server-side: target item and/or destination folder must be inside the effective user's home subtree. Returns 403 `NOT_IN_USER_FOLDER` on violation. See §13 → 'Home-folder ownership enforcement'."*
- §3 "Validation rules": delete the existing line *"Cascade delete is unbounded; permissions are enforced UI-side."*
- §13 (Identity): append a new subsection **"Home-folder ownership enforcement"** describing the rule (per §2 of this spec), the affected operations, the `NOT_IN_USER_FOLDER` error code, and that the check lives in `service/OwnershipChecker`.
- §3 error-code list: add `NOT_IN_USER_FOLDER`; remove `DESTINATION_NOT_IN_USER_FOLDER`.

---

## 7. Tests

Following CLAUDE.md conventions (`@Nested`, `@ParameterizedTest` for input variants, AssertJ semantic assertions). Expected delta: ~30–40 new test executions; net total 627 → ~660.

### 7.1 New unit tests

- **`OwnershipCheckerTest`**:
  - `requireHomeFolderExists` happy path (home present) returns the home node.
  - `requireHomeFolderExists` throws `NotFoundException(HOME_FOLDER_NOT_FOUND)` when absent; asserts message contains user name.
  - `requireOwned` allows item == home folder.
  - `requireOwned` allows item that is descendant of home (uses `cache.isAncestor` mock).
  - `requireOwned` throws `ForbiddenException(NOT_IN_USER_FOLDER)` when item is unrelated; asserts `contextLabel` appears in detail.
  - Null guards on both methods.

### 7.2 `ItemServiceTest` — six new nested classes

For each of `Create / Delete / Rename / Move / Update / Copy`, ownership tests:

- **In-home happy path** — already covered by existing tests; assert no regression.
- **Out-of-home target** → 403 `NOT_IN_USER_FOLDER`.
- **No home folder for effectiveUser** → 404 `HOME_FOLDER_NOT_FOUND`.
- **Impersonated user** — `iceUser=alice, impersonatedUser=bob` resolves against bob's home, not alice's.

`MoveOwnership` adds the matrix:
- source out, parent in → 403
- source in, parent out → 403
- both in → ok
- both out → 403 (first failure on source)

`DeleteOwnership` adds: **missing id with no home folder** → still a no-op (cache miss short-circuits before the home-folder lookup; non-existent items don't leak the no-home-folder state).

### 7.3 `ItemControllerTest`

- One new 403 assertion per mutation endpoint (5 new): asserts status, `Problem.errorCode=NOT_IN_USER_FOLDER`, `Problem.status=403`.
- `CopyItem` nested class: update existing `DESTINATION_NOT_IN_USER_FOLDER` assertions to `NOT_IN_USER_FOLDER` + status 400 → 403.

### 7.4 `GlobalExceptionHandlerTest`

- One new test: `ForbiddenException` maps to HTTP 403 with `application/problem+json` body and correct `errorCode`.

### 7.5 `ItemTreeApplicationE2EIT`

- One new E2E test: `mutationsForbiddenOutsideOwnFolder` — testuser1 attempts to create an item under testuser2's home folder via the HTTP layer of context A; expects 403; verifies the rejection is propagated (no event published to context B, no DB row created).

### 7.6 Existing tests requiring updates (not new)

- `ItemServiceCopyTest` — replace `DESTINATION_NOT_IN_USER_FOLDER` references with `NOT_IN_USER_FOLDER`; adjust expected exception type from `ValidationException` to `ForbiddenException`.
- `ItemControllerTest.CopyItem` — same swap; status 400 → 403.
- `ObservabilityExposureIT` — `itemtree.copy.rejected{reason}` assertion tag updated.
- Any test data setup that relies on the existing `testuser1` / `testuser2` home folders to perform mutations against other users' folders may need adjusting — most existing tests use ids inside the testuser1 home subtree already (verify during implementation).

---

## 8. Done when

- All §7 tests green; total ~660.
- `./gradlew clean build` → BUILD SUCCESSFUL.
- Manual smoke test against dev profile:
  - As `testuser1`, create an item under testuser1's home folder → 201.
  - As `testuser1`, attempt to create under testuser2's home folder → 403 with `errorCode=NOT_IN_USER_FOLDER`; toast in UI renders cleanly.
  - As `testuser1`, attempt to delete/rename/move/update an item in testuser2's subtree → 403 each.
  - As `testuser1`, copy an item from testuser2's subtree into testuser1's subtree → 201 (copy source is unrestricted).
- `IMPLEMENTATION_NOTES.md`, `CLAUDE.md`, `itemtree-service-design.md` updated per §6.
- Memory note added: `project-phase16-ownership-done.md`.

---

## 9. Non-goals (out of scope this phase)

- **System / admin bypass.** No bypass user/role in this phase. If a deploy needs one later, add it as a separate phase.
- **Per-folder ACLs.** Ownership is binary (home subtree or not). No sharing, no group folders, no read-vs-write distinction.
- **Read-side gating.** `getTree`, `getItems`, `search`, `getSubtree`, `getHomeFolder` remain unrestricted — anyone can read anything. This phase is mutation-only.
- **Copy source restriction.** Source remains unrestricted by explicit choice (§3.6).
- **UI permission probing.** Test UI does not gray out forbidden actions; it surfaces 403 in the existing toast.
- **Logging / audit trail for rejections.** Standard HTTP status logging covers it; no dedicated audit log added.

---

## 10. Risks and considerations

- **Existing tests using cross-user ids may break.** The H2 seed data places most fixtures in a deep nested branch under `Users/deepuser`. Mutation tests that target ids under `Users/deepuser` will succeed only if the test sets `iceUser=deepuser`. Implementation must audit and update each test's headers; this is mechanical but voluminous.
- **The "deepuser" test fixture is also a home folder.** Confirmed in seed data (`Users/deepuser` is a `Folder` with name matching a username). Tests using it as iceUser should "just work" for items in its subtree.
- **Header drift.** Several tests construct `UserContext` directly with arbitrary user strings (e.g. `"u"`, `"testuser"`). These will now fail with `HOME_FOLDER_NOT_FOUND` unless seed data is updated or the user string is changed to match a seeded home folder.
- **`@WebMvcTest` slices** — mocked `ItemService` is unaffected; only integration / repository-touching tests need fixture review.

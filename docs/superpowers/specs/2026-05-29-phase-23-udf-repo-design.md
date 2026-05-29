# Phase 23 — `UDFRepo` type (design)

Date: 2026-05-29
Status: approved (design), pending implementation plan
Branch: `main` (work stays on the current branch per user instruction)

## Goal

Introduce a new item type, `UDFRepo`, analogous to `Report`/`Filter`/`View`,
with per-user singleton semantics rooted in the user's home folder:

1. Each user has at most **one** `UDFRepo`, living directly in the user's home folder.
2. Creating a second one is rejected with a meaningful error.
3. Its name is always the username (e.g. `testuser1`).
4. Once it exists it cannot be deleted, renamed, or moved by the user — only its
   data may be updated.
5. It must carry a JSON payload.

Terminology: **"user's root folder" = the user's home folder** — the `Folder`
whose name equals the username, resolved via `TreeCache.findHomeFolder(effectiveUser)`
(design §13). That is the only per-user root the model has.

## Decisions (resolved during brainstorming)

| Question | Decision |
|---|---|
| Name handling on create | Server **forces** name = effective username; client-sent name is overridden, never validated. |
| Operations locked on an existing UDFRepo | Block **delete + rename + move**; only data-update allowed. |
| Duplicate-create error model | **400** via `ValidationException` with new `ErrorCode`. |
| Placement | UDFRepo may only be a **direct child of the caller's home folder**. |
| Copy interaction | **Skip** UDFRepo nodes inside a copied subtree; **reject** a direct copy of a UDFRepo. |
| Cascade delete | Block **direct delete only**; an ancestor cascade (e.g. deleting the home folder) is out of scope and still removes it. |
| Extra scope | Testing web UI control + acceptance scenarios + seed-data row. |

## Type definition & policy

- New type literal `"UDFRepo"`. Add `Types.UDF_REPO` constant and
  `Types.isUdfRepo(String)` helper in `common/Types.java`.
- **No change to the three `itemtree.data.*` lists.** UDFRepo is a default
  new-format type (like `View`, `UDF.Context`, `Eval`): not in
  `types-without-data`, so `policy.hasData("UDFRepo") == true` → JSON-only
  persist, JSON to UI. This satisfies invariant 5 ("must have JSON payload"):
  `createItem`/`updateItemData` already enforce `DATA_REQUIRED` for has-data types,
  and `TYPE_CANNOT_HAVE_DATA` can never fire for it.
- UDFRepo is a **leaf** by construction: it is not a `Folder`, so `createItem`'s
  existing `PARENT_NOT_FOLDER` check already prevents anything being nested under it.
- Like `View`/`Eval`, it is not a member of any config list, so the existing
  `itemtree.policy.unknown_type` metric will tick for it — consistent with the
  other new-format types; no behavioural change.

## New error codes

All rendered as **HTTP 400** via the existing `ValidationException` path
(consistent with `TYPE_CANNOT_HAVE_DATA` / `FOLDER_CANNOT_HAVE_DATA`). No new
exception class or HTTP status; `ProblemFactory` already renders any `ErrorCode`
under the throwing exception's status.

- `UDF_REPO_ALREADY_EXISTS` — a UDFRepo already exists for this user.
- `UDF_REPO_INVALID_PARENT` — parent isn't the caller's own home folder.
- `UDF_REPO_PROTECTED` — attempted delete / rename / move (or direct copy) of an
  existing UDFRepo.

## `ItemService` changes

### `createItem`

After the existing parent resolution (`PARENT_NOT_FOUND`, `PARENT_NOT_FOLDER`)
and ownership resolution (`requireHomeFolderExists`, `requireOwned(parent)`),
when `Types.isUdfRepo(type)`:

1. **Parent must be the home folder itself** — `parentId == homeFolder.itemTreeId()`,
   else `UDF_REPO_INVALID_PARENT`.
2. **Uniqueness** — scan `cache.getChildren(homeFolder.itemTreeId())`; if any child
   has type `UDFRepo`, reject `UDF_REPO_ALREADY_EXISTS`.
3. **Force name** = `effectiveUser`.

The normal data-policy checks then apply unchanged: `hasData == true`, so a null
`dataJson` is rejected with `DATA_REQUIRED`.

### `deleteItem`

After the cache probe and ownership check, if the **targeted** node is a UDFRepo →
`UDF_REPO_PROTECTED`. A cascade through an ancestor is **not** intercepted.

### `renameItem`

If the targeted node is a UDFRepo → `UDF_REPO_PROTECTED` (placed after ownership).

### `moveItem`

If the moved source node is a UDFRepo → `UDF_REPO_PROTECTED` (placed after ownership).

### `updateItemData`

Unchanged. UDFRepo is a has-data, JSON-only type, so update already works — this is
the single allowed mutation.

### `copyItem`

- **Direct copy** (source is a UDFRepo) → reject `UDF_REPO_PROTECTED`, with the
  existing copy-rejection metric recorded.
- **Subtree copy** — filter out any UDFRepo rows before id allocation / row build.
  Safe because a UDFRepo is always a leaf, so skipping one never orphans children,
  and the subtree root is never a UDFRepo (rejected above).

## Out-of-service scope

### Seed data (`db/data.sql`)

Add one `UDFRepo` row: name = `testuser1`, type = `UDFRepo`, as a direct child of
testuser1's home folder, JSON populated, XML null. Exercises JSON-only read shaping
and gives the acceptance suite a pre-existing repo for the duplicate-reject path.

### Testing web UI (Phase 15/21 static UI)

Add a "Create / view my UDFRepo" control that resolves the caller's home folder and
POSTs a create with `type=UDFRepo` and a default JSON payload, surfacing success or
the rejection error.

### Acceptance suite (Phase 22 Cucumber)

New `udf-repo.feature` covering: create → duplicate-reject → delete-reject, plus
rename-reject and move-reject.

## Testing

Unit additions: `TypesTest` (`isUdfRepo`), `ItemServiceCreateTest` (happy path with
forced name, duplicate reject, invalid-parent reject, `DATA_REQUIRED`),
`ItemServiceDeleteTest` (direct delete reject; ancestor cascade still removes —
documents the boundary), `ItemServiceRenameTest` (reject), `ItemServiceMoveTest`
(reject), `ItemServiceCopyTest` (subtree skip; direct-copy reject). Acceptance
feature as above. Full suite must remain green.

## Invariants honoured

- DB → cache → broadcast write order unchanged (UDFRepo flows through the same paths).
- Folder literal, root convention, UTC time mapping, defensive cache copies — all unaffected.
- No persistence-framework, schema-migration, or company-library imports introduced.

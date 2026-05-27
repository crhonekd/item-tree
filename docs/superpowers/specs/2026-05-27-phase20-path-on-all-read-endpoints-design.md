# Phase 20 — `path` on all read endpoints + leading-slash format + test UI ID/path copy

**Status:** approved (brainstorm phase)
**Date:** 2026-05-27
**Branch:** `phase-20`

---

## 1. Goal

Populate a `path` field on every item returned by a **read** endpoint (`/tree`, `/tree/{rootId}/subtree`, `/tree/{rootId}/subtree-full`, `/items/get`, `/search`, `/users/{userName}/home-folder`), and standardise the path format on a leading slash, e.g. `/root/AMER/user1/Reports/Report1`.

Mutation response bodies are explicitly **unchanged** in this phase.

Add two new entries to the test UI's per-item context menu:

- `Copy ID (<itemId>)` — copies the literal numeric id to the clipboard.
- `Show and copy full path` — copies the full path and shows it via `alert()`.

---

## 2. Architecture decision — keep lazy compute

Design §9 already chose lazy compute over storing path on `CachedNode`. We **re-affirm** that decision in Phase 20:

- Storing path on `CachedNode` would force O(subtree) recomputation under the cache write lock on every rename and every move. Renaming a near-root folder containing 100k descendants would block reads for hundreds of milliseconds. This violates the cache's latency goal.
- Lazy compute costs ~1μs per node walked, ~200μs per `/tree` call. `PathResolver.pathsOf(Collection<Long>)` memoises ancestor walks within a single call, so a bulk endpoint pays the walk cost once per unique ancestor chain.

Phase 20 is therefore additive: it extends `PathResolver` usage to the remaining read endpoints without touching the cache's structural invariants.

---

## 3. Path format change

Switch the path format from `root/Users/alice` (current) to `/root/Users/alice` (leading slash).

This is a breaking change to the response shape of `/tree`, `/tree/{rootId}/subtree`, and `/tree/{rootId}/subtree-full`, but it is contained to a single formatting rule inside `DefaultPathResolver`. There is no client other than the test UI; the test UI displays the value verbatim and is updated in lockstep.

Touch points:

- `DefaultPathResolver` — the join that currently produces `root/...` produces `/root/...`.
- `DefaultPathResolverTest` — all expected strings re-anchored.
- `TreeServiceTest`, `TreeServiceSubtreeNotFoundTest` — `pathsOf` stub return values re-anchored.
- `TreeControllerTest` — JSON path assertions re-anchored.
- Any E2E or controller test asserting a path literal.
- Design doc §3 example and §9 example.

The empty-string return for unknown ids and the partial-path return for cycles / orphan walks (current `PathResolver` Javadoc behaviour) are **unchanged** — those degraded outputs do not get a leading slash. Only well-formed root-anchored paths do.

---

## 4. OpenAPI + DTO schema

`src/main/resources/openapi/itemtree-api.yaml`:

- **`ItemNode`** — `path` field already exists. Update its description from "tree endpoints only" to "populated on all read endpoints; absent on mutation responses". Update the example to `/root/Folder1/IceReport`.
- **`ItemNodeWithData`** — add `path: string` (nullable: false on read responses; in practice always populated by mappers). If `ItemNodeWithData` is modelled via `allOf: ItemNode + …`, the inherited `path` is sufficient. Otherwise add it explicitly. Recursive `children` (folder children, one level deep) also carry `path` since each child is itself `ItemNodeWithData`.
- **`SearchHit`** — add `path: string`.
- **`/users/{userName}/home-folder`** — returns `ItemNode`, already covered by the `ItemNode` change above.
- Mutation response bodies (`POST /items`, `PUT /items/{id}/data`, `POST /items/{id}/move`, `POST /items/{id}/rename`, `POST /items/{id}/copy`) — **unchanged**. `DELETE /items/{id}` continues to return 204.

The generated DTOs auto-pick up the new fields. Generated code remains confined to `api/mapper` (CLAUDE.md invariant 7).

---

## 5. Service layer

`PathResolver` and `DefaultPathResolver` retain their existing signatures. Only the output formatting changes (§3).

### `TreeService`

Already wired via `TreeNodeView(CachedNode node, String path)` and `pathResolver.pathsOf(...)`. **No structural change** beyond the format flowing through.

### `SearchService`

Currently returns `List<CachedNode>`. Change to return `List<SearchHitView>` where:

```java
public record SearchHitView(CachedNode node, String path) {}
```

After resolving the hits, call `pathResolver.pathsOf(ids)` once and zip the results.

### `ItemService.get(List<Long> ids)`

Currently returns `List<ItemWithData>`. Add a `path` field to `ItemWithData` and to each entry in its `children` list.

Implementation: after the items are assembled (including the one-level-deep `children` for folders), collect the union of top-level + child ids, call `pathResolver.pathsOf(allIds)` once, and stamp the result onto the views.

`ItemWithData` becomes:

```java
public record ItemWithData(
    CachedNode node,
    JsonNode dataJson,            // existing
    String   dataXml,             // existing
    List<ItemWithData> children,  // existing — populated for folders
    String   path                 // new
) {}
```

### `HomeFolderService`

Currently returns `CachedNode`. Change to return `TreeNodeView` so `ItemNodeMapper` can stamp path without a special case.

---

## 6. Mappers

All under `api/mapper`.

- **`ItemNodeMapper`** — no change. Already calls `dto.setPath(view.path())`. Newly used by `HomeFolderService` returning `TreeNodeView`.
- **`ItemNodeWithDataMapper`** — set `dto.setPath(item.path())` on the top-level item AND on each recursively-mapped child.
- **`SearchHitMapper`** — input becomes `SearchHitView`. Set `dto.setPath(view.path())`.

No mapper imports anything outside its existing dependency set. Generated DTO imports stay in `api/mapper` only.

---

## 7. Tests

### Tests updated (leading-slash change)

- `DefaultPathResolverTest` — every expected string now starts with `/`.
- `TreeServiceTest`, `TreeServiceSubtreeNotFoundTest` — `pathsOf` stub return values updated.
- `TreeControllerTest` — JSON path assertions updated.
- Any E2E or controller test asserting a path literal.

### Tests added (Phase 20 coverage)

- `DefaultPathResolverTest` — explicit spec: format is `/root/...`; root itself is `/root`; unknown id → empty string (no slash); partial-path degraded case unchanged.
- `SearchServiceTest` — path populated on hits; `pathsOf` called once per `search` call (memoisation contract).
- `ItemServiceTest` — path on top-level item and on each expanded child; single `pathsOf` call covering all ids.
- `HomeFolderServiceTest` — path on returned home folder; format `/root/Users/<u>` etc.
- `SearchHitMapperTest`, `ItemNodeWithDataMapperTest` — new path field mapped end-to-end.
- Controller-layer tests for `/search`, `/items/get`, `/users/{u}/home-folder` — JSON has `path` and it starts with `/`.
- One E2E test confirming roundtrip path on `/search` and `/items/get`.

### Design doc updates

- **§3 Schema summary** — `ItemNode.path` description (all read endpoints, not "tree endpoints only"); add path mention to `SearchHit` and `ItemNodeWithData`. Update example string.
- **§9 Path Computation** — update table:

| Endpoint | Path field populated? |
|---|---|
| `/tree` | Yes |
| `/tree/{rootId}/subtree` | Yes |
| `/tree/{rootId}/subtree-full` | Yes |
| `/items/get` | **Yes** (was No) |
| `/search` | **Yes** (was No) |
| `/users/{userName}/home-folder` | **Yes** (was implied via `ItemNode`) |
| Create / update / move / rename / copy / delete responses | No |

  Update format example to `/root/Folder1/IceReport`.

---

## 8. Test UI context menu

`src/main/resources/static/js/menu.js`.

### Root branch

Currently `if (!node || id === ROOT_ID) return;` short-circuits. Replace with a root branch that builds a minimal menu containing **only** the two new items:

```
┌──────────────────────────┐
│ Copy ID (1)              │
│ Show and copy full path  │
└──────────────────────────┘
```

### Non-root branch

Prepend the two new items at the top of the existing menu, followed by a thin CSS separator (`border-top` on the next `<li>`):

```
┌──────────────────────────┐
│ Copy ID (10001)          │   <-- new
│ Show and copy full path  │   <-- new
│ ──────                   │   <-- separator
│ Refresh subtree          │
│ Create child             │
│ Edit data                │
│ Rename                   │
│ Delete                   │
│ Cut                      │
│ Copy                     │
│ Paste here (...)         │
└──────────────────────────┘
```

### Wiring

- Labels are per-item: `` `Copy ID (${id})` `` and the literal `'Show and copy full path'`.
- Path source: `state.tree.nodesById.get(id).path`. The in-memory tree was loaded from `/tree`, which after Phase 20 returns a leading-slash path. No client-side walking.
- Clipboard: `await navigator.clipboard.writeText(text)`. On rejection (insecure context, permission denied), fall back to `toastError(...)` displaying the value so the user can copy manually.
- "Show" for path: after the clipboard write resolves, `alert('Path copied:\n' + path)`.
- "Copy ID" does NOT show an `alert()` — silent clipboard write plus a brief `toastSuccess('Copied id ' + id)` for confirmation.
- Separator is a single CSS rule on a marker class, applied to the third `<li>` only when the menu has more than the two new items.

### No automated tests for the static UI

Consistent with Phase 15. Manual verification only.

---

## 9. Rollout / commit shape

Single branch (`phase-20`, already current). Commit order keeps every intermediate state green:

1. **OpenAPI + DTO regeneration** — add `path` to `ItemNodeWithData`, `SearchHit`; update `ItemNode` description. Field is unpopulated until later commits — generated code compiles, existing behaviour unchanged.
2. **`DefaultPathResolver` format change** — leading slash. Update `DefaultPathResolverTest` + small set of TreeService / TreeController tests asserting path strings. Full suite green.
3. **`SearchService` + `SearchHitView` + `SearchHitMapper`** — path on `/search`. Tests added.
4. **`ItemService.get` + `ItemWithData.path` + `ItemNodeWithDataMapper`** — path on `/items/get` and children. Tests added.
5. **`HomeFolderService` returning `TreeNodeView`** — path on `/users/{u}/home-folder`. Tests added.
6. **Design doc** — §3 + §9 updated.
7. **Test UI menu** — two new context-menu items + root branch + clipboard plumbing.
8. **`IMPLEMENTATION_NOTES.md`** — Phase 20 entry.
9. **Memory** — add `project-phase20-path-everywhere-done.md` once tagged.

No DB / schema / messaging / bean-wiring changes. No new Spring profile. `PathResolver` bean is already wired.

---

## 10. Out of scope

- Mutation response bodies (`create`, `update`, `move`, `rename`, `copy`). DELETE remains 204.
- Pre-computed path on `CachedNode` — explicitly rejected (re-affirms §9).
- Path escaping for item names containing `/`. The current `PathResolver` joins names verbatim; this carries over. Not a Phase 20 deliverable; note as a known limitation.
- Automated tests for the static UI.
- Work PC / Phase B wiring (the renumbered Phase 18 remains user-managed).

---

## 11. Invariants reaffirmed

- Cache structure unchanged. Apply-method idempotency contract (§4) unchanged.
- Write path ordering DB → cache → broadcast unchanged.
- Generated DTOs only imported from `api/mapper` (CLAUDE.md invariant 7).
- `PathResolver`'s "empty string for unknown id" and "partial path with WARN log" degraded behaviours unchanged — only well-formed root-anchored paths get the leading slash.

# Phase 17 — Level-1 default for `getSubtree`; recursive becomes `getSubtreeFull`

**Date:** 2026-05-26
**Branch:** `phase-17`
**Companion phase rename:** the previously planned "Phase 17 — Work PC wiring" becomes **Phase 18 — Work PC wiring** (heading only; body unchanged).

---

## 1. Background and motivation

The `ITEMTREE` test UI currently makes a single `GET /api/v1/itemtree/tree/{rootId}/subtree` call to load the entire subtree under a folder. The implementation performs a BFS over the in-memory cache and returns root + every descendant as a flat list. For folders with many nested descendants the response payload and client-side parse cost are noticeable; chevron-expand interactions feel slow because every expand triggers a full deep walk even though the UI only needs the direct children to render the next level.

We want two operations with different latency/payload profiles, addressable by URL:

- **Level-1** (new default for `/subtree`) — root + immediate children, used for chevron-expand, lazy navigation, and refresh-after-mutation.
- **Full recursive** (preserved as `/subtree-full`) — root + every descendant in BFS order; kept for the home-folder pre-load at login and for any future caller that genuinely needs the whole subtree in one round trip.

A `?depth=` query parameter on a single endpoint was considered and rejected — see §8.

---

## 2. Scope

### In scope

- New `GET /api/v1/itemtree/tree/{rootId}/subtree` returning root + immediate children with paths.
- Renamed `GET /api/v1/itemtree/tree/{rootId}/subtree-full` returning the existing recursive payload (no behavioural change).
- Rename of the supporting `TreeService`, `TreeController`, and `TreeCache` methods.
- Updates to the static test UI: split the JS API helper, split the `ingestSubtreeResult` bookkeeping, route each call site to the correct endpoint.
- Renumbering of the previously-planned Phase 17 (Work PC wiring) to Phase 18.
- Design-doc updates (§3, §4, §9 of `itemtree-service-design.md`).

### Out of scope

- Any change to ownership, mutation, or other read endpoints.
- A `depth` query parameter or any other multi-depth mode.
- Automated UI tests (consistent with Phase 15: the static UI is a test harness; manual smoke is the test).
- Changes to ancestor walking in `navigateTo` (search-result navigation). The pre-existing limitation that ancestors of a deep search hit are not auto-loaded is unaffected by this work.
- Any new metrics or error codes.

---

## 3. API surface

### 3.1 OpenAPI (`src/main/resources/openapi/itemtree-api.yaml`)

**Rename** the existing operation:

| | Before | After |
|---|---|---|
| `operationId` | `getSubtree` | `getSubtreeFull` |
| Path | `/api/v1/itemtree/tree/{rootId}/subtree` | `/api/v1/itemtree/tree/{rootId}/subtree-full` |
| Summary | `Get subtree rooted at rootId as a flat list with paths` | `Get the full recursive subtree rooted at rootId as a flat list with paths` |
| Response schema | `array of ItemNode` | unchanged |

**Add** a new operation:

| | Value |
|---|---|
| `operationId` | `getSubtree` |
| Path | `/api/v1/itemtree/tree/{rootId}/subtree` |
| Method | `GET` |
| Tags | `[tree]` |
| Summary | `Get root + immediate children of rootId as a flat list with paths` |
| Parameters | `rootId` (path, int64), `X-Ice-User`, `X-Impersonated-User` (same as existing) |
| `200` response | `array of ItemNode` with `path` populated |
| `404` response | `$ref: '#/components/responses/NotFound'` (`errorCode = ITEM_NOT_FOUND`) |
| `503` response | `$ref: '#/components/responses/ServiceUnavailable'` |

Both operations share the same `ItemNode` schema and the same path/header parameter conventions.

### 3.2 Response shape (both endpoints)

A JSON array of `ItemNode`, root first, each with `path` populated:

- For `/subtree`: `[root, c1, c2, …, cN]` where `c1..cN` are the immediate children of `root`. Child order is unspecified (matches the existing `TreeCache.getChildren` contract — backed by a hash-based set). If `root` is a leaf or an empty folder, response is `[root]`.
- For `/subtree-full`: existing BFS order — root, then root's children, then those children's children, etc. (unchanged.)

### 3.3 Error model

Unchanged from today. Both endpoints throw `NotFoundException(ITEM_NOT_FOUND)` → HTTP 404 + RFC 7807 `application/problem+json` when `rootId` is not present in the cache. No new error codes.

---

## 4. Internal changes

### 4.1 Cache layer

- `TreeCache.getSubtreeFlat(long)` → renamed `getSubtreeFlatFull(long)`. No behavioural change. Update the interface Javadoc to read "Returns root + all descendants in BFS order …".
- `DefaultTreeCache.getSubtreeFlat` → renamed to match the interface.
- No new cache method. Level-1 is composed at the service layer from the existing `getById` + `getChildren` primitives — both already exist, are tested, and return defensive copies.

### 4.2 Service layer (`TreeService`)

- Existing `getSubtree(long rootId)` → renamed `getSubtreeFull(long rootId)`. Body unchanged except for the `cache.getSubtreeFlat` call swapping to `cache.getSubtreeFlatFull`.
- New `getSubtree(long rootId)`:

```java
public List<TreeNodeView> getSubtree(long rootId) {
    CachedNode root = cache.getById(rootId)
        .orElseThrow(() -> new NotFoundException(
            ErrorCode.ITEM_NOT_FOUND, "Item " + rootId + " not found"));
    List<CachedNode> children = cache.getChildren(rootId);
    List<CachedNode> result = new ArrayList<>(children.size() + 1);
    result.add(root);
    result.addAll(children);
    return pairWithPaths(result);
}
```

The existing `pairWithPaths(List<CachedNode>)` helper is reused as-is.

### 4.3 Controller (`TreeController`)

The generated `TreeApi` interface gains a second method (the new `getSubtree`); the existing method is renamed to `getSubtreeFull`. The controller implements both, each delegating to its `TreeService` counterpart and mapping through `itemNodeMapper`. No new mapper logic.

### 4.4 Static UI

**`js/api.js`:**
- Rename the existing `getSubtree` helper to `getSubtreeFull` (now hits `/api/v1/itemtree/tree/{rootId}/subtree-full`).
- Add a new `getSubtree(rootId)` helper hitting `/api/v1/itemtree/tree/{rootId}/subtree`.

**`js/tree.js` — split the ingest function** because the post-payload bookkeeping differs between level-1 and recursive payloads:

- Keep `ingestSubtreeResult(rootId, nodes)` for the level-1 case. Body becomes: `ingestNodes(nodes); state.tree.loadedSubtreeOf.add(rootId);`. **Remove** the loop that marks every folder in `nodes` as loaded — for a level-1 payload that loop is unsafe because child folders' children are not in the payload, so marking them loaded would suppress later lazy-load fetches.
- Add `ingestSubtreeFullResult(rootId, nodes)` for the recursive case. Body matches the current `ingestSubtreeResult` exactly: ingest nodes, mark `rootId` loaded, mark every folder in `nodes` loaded.

**Call-site routing:**

| File:line | Site | Before | After |
|---|---|---|---|
| `app.js:65` | initial home-folder load at login | `api.getSubtree` + `ingestSubtreeResult` | `api.getSubtreeFull` + `ingestSubtreeFullResult` |
| `tree.js:93` | chevron expand | `api.getSubtree` + `ingestSubtreeResult` | unchanged code, now level-1 endpoint and corrected bookkeeping |
| `tree.js:131` | `refreshSubtree` (post-mutation) | `api.getSubtree` + `ingestSubtreeResult` | unchanged |
| `search.js:37` | `navigateTo` | `api.getSubtree` + `ingestSubtreeResult` | unchanged |

`ingestSubtreeFullResult` is exported from `tree.js` so `app.js` can import it.

### 4.5 Filter / readiness gate

No change. `CacheReadinessFilter` already gates all `/api/**`; both endpoints are covered automatically.

---

## 5. Documentation changes

### 5.1 `IMPLEMENTATION_NOTES.md`

- Rename heading `## Phase 17 — Work PC wiring (Phase B, user-managed)` → `## Phase 18 — Work PC wiring (Phase B, user-managed)`. Body unchanged.
- Insert immediately above it a new `## Phase 17 — Level-1 default for getSubtree; recursive becomes getSubtreeFull` section summarising goal, surface, tests, deviations (filled in at implementation time), and done-state.

### 5.2 `itemtree-service-design.md`

- **§3 endpoint table**: rename existing `/subtree` row's description to "Full recursive subtree (flat)"; change its path to `/tree/{rootId}/subtree-full`; insert a new row `/tree/{rootId}/subtree` — "Root + immediate children (flat)".
- **§3 response-shapes paragraph**: extend the existing "`/tree` and `/tree/{rootId}/subtree`" sentence to also list `/tree/{rootId}/subtree-full`, and clarify that both subtree endpoints return flat `ItemNode` arrays with `path` populated; the difference is depth (level-1 vs full recursive).
- **§4 TreeCache interface code block**: rename `getSubtreeFlat` → `getSubtreeFlatFull`. (Adjacent prose unaffected.)
- **§9 "Lazy compute at response time"**: update the bullet that says `/tree` and `/tree/{rootId}/subtree` to also list `/tree/{rootId}/subtree-full`.
- **§3 cache-served reads list** (currently "Serve all read endpoints (`/tree`, `/tree/{rootId}/subtree`, `/search`, `/users/{userName}/home-folder`)"): add `/tree/{rootId}/subtree-full`.

### 5.3 Memory note

A new memory file at `~/.claude/projects/-home-dave-Git-item-tree/memory/project-phase17-subtree-level1-done.md` recording phase completion, test count, and the renaming of the prior Phase 17 → Phase 18.

---

## 6. Tests

### 6.1 Renamed (no count change)

| File | Tests | Change |
|---|---|---|
| `DefaultTreeCacheTest` | `getSubtreeFlatIncludesRootAndAllDescendants`, `getSubtreeFlatOnLeafReturnsOnlyThatNode`, `getSubtreeFlatOnMissingIdReturnsEmpty`, `getSubtreeFlatResultIsUnmodifiable` | rename method names → `…Full`; update `cache.getSubtreeFlat(…)` → `cache.getSubtreeFlatFull(…)` |
| `TreeServiceTest` | `getSubtreeReturnsPairsForEveryNodeInSubtree`, `getSubtreeThrowsNotFoundForUnknownRoot`, `getSubtreeFallsBackToEmptyStringPathWhenResolverOmitsId` | rename to `getSubtreeFull…`; update calls to `service.getSubtreeFull(…)` and `cache.getSubtreeFlatFull(…)` |
| `TreeServiceSubtreeNotFoundTest` | 2 existing tests | retarget to `service.getSubtreeFull(…)` (this class covers the recursive path's 404 behaviour) |
| `TreeControllerTest` | `getSubtreeReturns200WithPaths`, `getSubtreeReturns404WhenRootMissing`, `getSubtreeWithNonNumericRootReturns400` | retarget to the renamed controller method / new path `/subtree-full`; rename test methods to `…Full…` |

### 6.2 Added

**`TreeServiceTest`** — new nested `GetSubtree` class (~4 tests):
- `returnsRootAndImmediateChildrenWithPaths` — folder with 3 children; assert response size = 4, first entry is `root`, the remaining entries are exactly `{c1, c2, c3}` as a set (order unspecified), and every entry has a populated path.
- `leafRootReturnsRootAlone` — root is a non-folder item; assert `[root]`.
- `emptyFolderRootReturnsRootAlone` — root is a folder with no children; assert `[root]`.
- `throwsNotFoundForUnknownRoot` — `cache.getById` returns empty; assert `NotFoundException(ITEM_NOT_FOUND)`.

**`TreeControllerTest`** — new nested `GetSubtree` class (~3 tests):
- `returns200WithRootAndChildren` — mock service to return a two-element view list; assert HTTP 200, JSON array length 2, paths populated, response Content-Type `application/json`.
- `returns404WhenRootMissing` — mock service throws `NotFoundException(ITEM_NOT_FOUND)`; assert HTTP 404, `application/problem+json`, `errorCode = ITEM_NOT_FOUND`.
- `returns400OnNonNumericRoot` — request `/tree/abc/subtree`; assert HTTP 400.

**`ItemTreeApplicationE2EIT`** — 1 new test (`getSubtreeReturnsLevel1Only`):
- Pick a seed folder known to have multiple descendants at depth ≥ 2 (e.g. `Users` → `testuser1` → some grandchild item).
- Call the level-1 endpoint against `Users`.
- Assert response contains the `Users` node and `testuser1`/sibling-user folders, but does **not** contain any of `testuser1`'s descendants.
- Call the recursive endpoint against the same root; assert it does include the grandchildren (sanity that the full version still works after rename).

### 6.3 No new tests at

- The cache layer — level-1 reuses `getById` + `getChildren`, both already exhaustively tested.
- Ownership / authorisation — reads are not ownership-gated (design §13).
- Metrics — no new counters.
- The UI — Phase 15 design states the test UI has no automated tests.

### 6.4 Test-count projection

665 (post-Phase-16) + ~8 new executions = ~673 tests. Final count confirmed during implementation.

---

## 7. Manual verification

After implementation, against the `dev` profile:

1. Start the app; open the test UI in a browser; open DevTools → Network.
2. Log in as `testuser1`. Observe exactly two backend calls related to the tree load: one `GET /tree` (trimmed view) and one `GET /tree/{homeId}/subtree-full` (home subtree pre-load). Inspect the `subtree-full` payload — confirm it contains the user's home folder plus all descendants seeded under it.
3. Expand a folder **outside** the home subtree (e.g. one under another user's `Users` entry, or the root tree). Observe a single `GET /tree/{id}/subtree` call. Inspect the payload — confirm it contains exactly `[that folder, its direct children]` and **no** grandchildren.
4. Expand a grandchild folder. Observe a **second** `GET /tree/{id}/subtree` call (proves `loadedSubtreeOf` correctly marks only the most-recently-expanded folder, not its children).
5. Perform a `create`/`rename`/`delete` mutation. Observe a `GET /tree/{parentId}/subtree` (level-1) call from `refreshSubtree`. Confirm UI updates.
6. Search for an item under another user's folder and click the result. Observe a level-1 `GET /tree/{hitId}/subtree` call. Confirm the row is added to the tree (ancestor-walk limitation acknowledged in §2 "out of scope").

---

## 8. Rejected alternatives

### Single endpoint with `?depth=` query parameter
Considered: `GET /tree/{rootId}/subtree?depth=1|full` (default 1).

Rejected because:
1. The default value silently changes the contract for any existing caller that omits the parameter — a breaking change hidden behind a default.
2. Latency and payload profile differ by orders of magnitude between the two modes; URL-level distinction makes per-endpoint metrics, ownership rules, and payload caps cleaner.
3. YAGNI — depths 2, 3, … are not requested. Generalising to a depth parameter pays complexity cost for unrequested flexibility.

### Keep current `/subtree` URL recursive, add `/children` for level-1
Considered: avoid the breaking URL change.

Rejected because the user explicitly preferred the `/subtree` + `/subtree-full` naming. The breaking change is one-time and confined to this repo's UI (the only known caller).

### No UI changes this phase
Considered: backend-only — let the UI continue to call `/subtree` and get a level-1 payload instead.

Rejected because the UI's `ingestSubtreeResult` would mark child folders as loaded even though grandchildren are no longer in the payload, suppressing subsequent lazy-load fetches and breaking expand-after-expand navigation. UI changes are part of the phase.

---

## 9. Done state checklist

- [ ] OpenAPI spec renamed and extended; `./gradlew build` regenerates the two `TreeApi` methods cleanly.
- [ ] `TreeCache.getSubtreeFlat` renamed to `getSubtreeFlatFull` (interface + impl + Javadoc).
- [ ] `TreeService.getSubtree` renamed to `getSubtreeFull`; new `getSubtree` method added.
- [ ] `TreeController` implements both methods; both pass through `itemNodeMapper`.
- [ ] `js/api.js`, `js/tree.js`, `js/app.js`, `js/search.js` updated per §4.4.
- [ ] All renamed tests pass; new tests added per §6.2 pass; `./gradlew clean build` → BUILD SUCCESSFUL.
- [ ] Test count ~673 (665 + ~8).
- [ ] `IMPLEMENTATION_NOTES.md` updated: Phase 17 → Phase 18 rename, new Phase 17 section added.
- [ ] `itemtree-service-design.md` updated per §5.2.
- [ ] Manual verification in §7 walked through end-to-end.
- [ ] Memory note `project-phase17-subtree-level1-done.md` written.

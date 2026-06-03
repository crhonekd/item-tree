# Phase 25 — Combined home-subtree endpoint + Login/Refresh button

**Date:** 2026-06-03
**Scope:** Test UI (`src/main/resources/static/**`) and one new REST endpoint under the `Users` tag.
**Phase:** A (personal PC).

---

## 1. Motivation

The test UI's `doLogin` currently fires three calls in order to log in / refresh:

1. `GET /users/{userName}/home-folder` — resolves the home folder.
2. `GET /tree` — fetches the lazy tree (in parallel with 1).
3. `GET /tree/{homeId}/subtree-full` — fetches the home folder's subtree (sequential, after 1).

Call 3 cannot start until call 1 returns, so the user pays one round-trip of
serial latency for what is logically a single operation: "give me the user's
home folder fully expanded." Phase 25 collapses calls 1 and 3 into one new
endpoint that takes the username and returns the home folder's subtree. The UI
then runs the new endpoint in parallel with `getTree`, removing the serial
hop. The rename of the `Login` button to `Login/Refresh` reflects that the
same button already serves both purposes.

There is no visible behavioural change for the user. The ★ home-folder
marker, ancestor-chain expansion, home-folder pre-expansion and the detail-pane
greeting all behave exactly as today.

---

## 2. Out of scope / non-goals

- No change to `HomeFolderService`, `TreeService`, `TreeCache`, persistence, or
  messaging.
- No change to the existing `GET /users/{userName}/home-folder` or
  `GET /tree/{rootId}/subtree-full` endpoints, their controllers, or their tests.
  Both remain available for other clients and as primitives.
- No change to ownership rules (Phase 16), search, refresh, or the path-resolution
  contract.
- No new metrics. The new controller method is counted by the existing HTTP
  timing/metrics infrastructure with no extra wiring.
- No automated UI tests — this codebase has none for the static test UI.
  Verification is manual via `gradlew bootRun`.
- No client outside the test UI is expected to adopt the new endpoint in
  Phase 25.

---

## 3. Backend — new endpoint

### 3.1 Contract

- **Method and path:** `GET /api/v1/itemtree/users/{userName}/home-subtree`
- **Path parameter:** `userName` (string, required).
- **Headers:** `X-Ice-User` (required, per project convention), `X-Impersonated-User`
  (optional). Identical to the existing `/home-folder` endpoint.
- **Response 200 (`application/json`):** `ItemNode[]`. Same DTO shape as
  `GET /tree/{rootId}/subtree-full`. The array is the full subtree rooted at the
  user's home folder, including the home folder itself.
- **Response 404:** problem-detail body with code `HOME_FOLDER_NOT_FOUND`, when
  no folder with the given user name exists. Same body the existing
  `/home-folder` endpoint produces in this case (the same
  `NotFoundException(ErrorCode.HOME_FOLDER_NOT_FOUND, …)` is thrown by the
  same service and translated by the same `GlobalExceptionHandler`).
- **Other error responses:** standard project conventions (400 on missing/blank
  `X-Ice-User`, 503 if the cache is not ready).

### 3.2 Identifying the home folder in the response

The response is a flat `ItemNode[]`. The home folder is the *unique element*
whose `parentId` is **not** the `itemTreeId` of any other element in the same
array (i.e. it has no parent inside the returned subtree). This is a
property of the data, not a new contract: the subtree is rooted at the home
folder, so its parent necessarily lies outside the returned set.

Clients that need the home folder's id derive it from the array as above
(see the UI section). The endpoint does not separately surface the
home-folder id.

### 3.3 OpenAPI

Add the new operation under the `Users` tag in
`src/main/resources/openapi/itemtree-api.yaml`. The operation:

- `operationId: getHomeSubtree`
- Response 200: `array` of `ItemNode` (reuse the existing schema reference).
- Response 404: reuse the existing `Problem` schema reference.

`openapi-generator` produces `UsersApi.getHomeSubtree(...)` which
`UserController` then implements.

### 3.4 Controller wiring

`UserController` gains one method implementing
`UsersApi.getHomeSubtree(String userName, String xIceUser, String xImpersonatedUser)`:

1. `CachedNode home = homeFolderService.findHomeFolder(userName);`
2. `List<TreeNodeView> views = treeService.getSubtreeFull(home.itemTreeId());`
3. `return ResponseEntity.ok(itemNodeMapper.toDtos(views));`

No new service method. The composition is two-step glue with no branching,
no validation, no fan-out — consistent with the existing thin-controller
pattern for this endpoint family. If a future caller needs the same
composition, we can promote it then.

`UserController` already has `HomeFolderService` and `ItemNodeMapper` injected;
`TreeService` will be added as a new constructor dependency.

`PathResolver` is **not** needed here: `treeService.getSubtreeFull` already
returns `TreeNodeView` instances with paths populated, exactly like the existing
`/tree/{rootId}/subtree-full` controller method.

### 3.5 Headers and ownership

The `X-Ice-User` and `X-Impersonated-User` headers are forwarded by Spring as
method parameters per the generator contract but are not used for business
decisions in this endpoint, matching the behaviour of the existing
`/home-folder` endpoint. No ownership check applies — reads are not
restricted by Phase 16, which only gates mutations.

### 3.6 Failure semantics

- Unknown `userName` → 404 `HOME_FOLDER_NOT_FOUND`, body identical to the
  existing `/home-folder` 404.
- Missing/blank `X-Ice-User` → 400, via the existing `UserContextInterceptor`
  /`CacheReadinessFilter` machinery (no change here).
- Cache not ready → 503, via the existing `CacheReadinessFilter`.
- Any other exception → handled by `GlobalExceptionHandler` as today.

---

## 4. Test UI changes

### 4.1 `index.html`

Rename the `#login-btn` text from `Login` to `Login/Refresh`. No other markup
or styling changes.

### 4.2 `js/api.js`

Add one method:

```js
getHomeSubtree: (userName) =>
  request('GET', `/api/v1/itemtree/users/${encodeURIComponent(userName)}/home-subtree`,
          undefined, { retryOn503: true }),
```

`retryOn503: true` matches `getTree`, so both halves of the parallel pair behave
consistently during cache warm-up.

Keep `getHomeFolder` and `getSubtreeFull` defined. They remain available even
if no current code path uses them, in keeping with the "small, useful
primitives" stance.

### 4.3 `js/app.js` — new `doLogin`

Replace the three-call sequence with a two-call parallel fan-out:

```js
const [tree, subtree] = await Promise.all([
  api.getTree(),
  api.getHomeSubtree(state.iceUser),
]);

ingestNodes(tree);

// Derive the home folder from the subtree: it's the node whose parentId
// is not the itemTreeId of any other node in the same array.
const idsInSubtree = new Set(subtree.map(n => n.itemTreeId));
const home = subtree.find(n => !idsInSubtree.has(n.parentId));
state.homeFolderId = home.itemTreeId;

// Expand the ancestor chain from root down to the home folder.
state.tree.expanded.add(1);
let cur = state.tree.nodesById.get(home.itemTreeId);
while (cur && cur.parentId && cur.parentId !== 0) {
  state.tree.expanded.add(cur.parentId);
  cur = state.tree.nodesById.get(cur.parentId);
}

ingestSubtreeFullResult(home.itemTreeId, subtree);

// Pre-expand the home folder and any folders directly inside its subtree.
state.tree.expanded.add(home.itemTreeId);
for (const n of subtree) {
  if (n.type === 'Folder') state.tree.expanded.add(n.itemTreeId);
}

renderTree();
$('detail-root').innerHTML = '(logged in as <b></b>; click a node)';
$('detail-root').querySelector('b').textContent = state.iceUser;
```

If the new endpoint resolves first, `Promise.all` naturally waits for `getTree`
before the body runs — no extra synchronisation logic is needed.

The "could not find a home root in the subtree" case (e.g. malformed response)
is treated like any other backend error: `home` would be `undefined`, the next
line throws, the existing `catch` surfaces a toast and `(login failed — see
toast)`. No special handling is added, in line with project conventions about
not adding error handling for scenarios that cannot happen with a well-formed
backend.

### 4.4 Net behavioural change

- One fewer round trip on login/refresh.
- No visible UI difference: ★ marker, expanded ancestor chain, home folder
  pre-expansion and the detail-pane greeting are all identical to today.
- Button label reads `Login/Refresh` instead of `Login`.

---

## 5. Tests

### 5.1 `UserControllerTest` (`@WebMvcTest`)

Add two cases for the new endpoint:

1. **200 happy path.** Mock `HomeFolderService.findHomeFolder("alice")` to
   return a `CachedNode`. Mock `TreeService.getSubtreeFull(homeId)` to return
   a list of `TreeNodeView`. Assert:
   - HTTP 200.
   - JSON body equals the mapper's output for the mocked views.
   - `HomeFolderService.findHomeFolder` called with `"alice"`.
   - `TreeService.getSubtreeFull` called with the home folder's id.
2. **404 unknown user.** Mock `HomeFolderService.findHomeFolder("ghost")` to
   throw `new NotFoundException(ErrorCode.HOME_FOLDER_NOT_FOUND, …)`. Assert:
   - HTTP 404.
   - Problem-detail body matches the existing convention.
   - `TreeService.getSubtreeFull` is **not** called.

### 5.2 E2E (`@SpringBootTest`, `e2e/`)

One happy-path scenario against the seeded H2 fixture: hit
`GET /api/v1/itemtree/users/{userName}/home-subtree` for a user known to have a
home folder. Assert:

- HTTP 200.
- The returned array contains the home folder itself plus its descendants.
- Exactly one element has a `parentId` not present as an `itemTreeId` in the
  array, and that element's `name` matches the requested user.

### 5.3 What does **not** need new tests

- `HomeFolderService` — unchanged.
- `TreeService.getSubtreeFull` — unchanged.
- `ItemNodeMapper` — unchanged.
- `CacheReadinessFilter`, `UserContextInterceptor`, `GlobalExceptionHandler` —
  unchanged. The generic 503 / 400 / problem-detail behaviours of the new
  endpoint flow through the same code paths already covered by existing tests.

### 5.4 Regression

`./gradlew test` must pass with no regressions. Acceptance Cucumber suite
(`:acceptance`) is unchanged by this phase and is not required to grow a new
scenario for Phase 25.

---

## 6. Files touched

**New:** none.

**Modified:**

- `src/main/resources/openapi/itemtree-api.yaml` — new operation under `Users` tag.
- `src/main/java/com/myxcomp/ice/xtree/api/controller/UserController.java` —
  implement `getHomeSubtree`; inject `TreeService`.
- `src/test/java/.../UserControllerTest.java` — two new cases.
- `src/test/java/.../e2e/...` — one new scenario in an appropriate existing E2E
  test class (or a small new one if none fits).
- `src/main/resources/static/index.html` — button label.
- `src/main/resources/static/js/api.js` — add `getHomeSubtree`.
- `src/main/resources/static/js/app.js` — rewrite `doLogin`.
- `IMPLEMENTATION_NOTES.md` — append a short Phase 25 note.

---

## 7. Risks and mitigations

| Risk | Mitigation |
|---|---|
| Generated `UsersApi` interface drifts and breaks the controller. | The generator runs in the standard build; missing implementations are compile errors. |
| `subtree.find(n => !idsInSubtree.has(n.parentId))` returns `undefined` because the seed produced an unexpected structure. | Tests assert exactly-one root in the E2E case. UI surfaces a toast via the existing catch path. |
| Removing the `home-folder` + `subtree-full` sequence breaks something subtle about expansion order. | The new flow ingests `getTree` first, then derives `homeFolderId`, then ingests the subtree — same logical order as today. Manual bootRun verification covers it. |
| `retryOn503` on `getHomeSubtree` masks a real 404 if the cache isn't ready and the username genuinely has no home folder. | Cache-not-ready returns 503, not 404. `retryOn503` only retries on 503. Once the cache is ready, a missing home folder cleanly surfaces 404. |

---

## 8. Done state

- New endpoint implemented and tested.
- Login button renamed.
- `doLogin` uses two parallel calls.
- Full test suite passes (`./gradlew test`).
- `bootRun` smoke check: clicking `Login/Refresh` produces identical visible
  state to before, with one fewer HTTP call in the browser's network panel.
- `IMPLEMENTATION_NOTES.md` updated.
- Commits land on `main` (current working branch — no new branch this phase).

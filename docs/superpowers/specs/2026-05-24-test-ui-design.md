# Phase 15 — Testing Web UI — Design

**Date:** 2026-05-24
**Status:** Approved (pending user review of this written spec)
**Implements:** Phase 15 of `IMPLEMENTATION_NOTES.md`

---

## 1. Overview

A single-page, no-build, static web UI shipped inside the Spring Boot JAR
under `src/main/resources/static/`. Accessible at the app's root URL
(`http://localhost:8080/` for the dev profile). Pure HTML + CSS + vanilla
JavaScript — no framework, no build step, no Node toolchain, no runtime
dependencies beyond the browser. Backend base URL is configurable in the UI
so the same UI can be pointed at UAT or staging later without redeployment.

### Goal

Exercise every REST endpoint manually against any running instance — Phase A
H2-backed locally, Phase B Oracle-backed remotely — without touching curl,
Postman, or the OpenAPI Swagger UI. The UI is a developer test harness for
the service team, not the production customer-facing interface.

### Non-goals

- Production styling, theme, accessibility audit, responsive layout, mobile.
- Internationalisation.
- Automated UI tests. Manual use is the test.
- CORS configuration changes — UI is same-origin with the backend by default.
- Real-time updates (WebSocket / Server-Sent Events). Drift is recovered via
  the Refresh delta/full buttons or by re-Login.
- Pagination of large folders, drag-and-drop, undo, history, diff view, bulk
  multi-select.
- Authentication or authorization. The UI trusts the user-typed
  `X-Ice-User` header value verbatim, matching the real ICEX UI flow.

### Endpoints covered (12)

| Method | Path                                                | Triggered by                       |
|--------|-----------------------------------------------------|------------------------------------|
| GET    | `/api/v1/itemtree/users/{userName}/home-folder`     | Login + Probe button               |
| GET    | `/api/v1/itemtree/tree`                             | Login                              |
| GET    | `/api/v1/itemtree/tree/{rootId}/subtree`            | Login (home subtree) + expand      |
| POST   | `/api/v1/itemtree/items/get`                        | Click an item / folder in tree     |
| POST   | `/api/v1/itemtree/items`                            | Right-click folder → Create child  |
| POST   | `/api/v1/itemtree/items/{id}/move`                  | Cut → Paste                        |
| POST   | `/api/v1/itemtree/items/{id}/copy`                  | Copy → Paste                       |
| POST   | `/api/v1/itemtree/items/{id}/rename`                | Right-click → Rename               |
| PUT    | `/api/v1/itemtree/items/{id}/data`                  | Right pane → Edit data             |
| DELETE | `/api/v1/itemtree/items/{id}`                       | Right-click → Delete               |
| GET    | `/api/v1/itemtree/search`                           | Top-bar search                     |
| POST   | `/actuator/itemtree-refresh/{type}`                 | Refresh delta / full buttons       |

---

## 2. Layout

```
┌────────────────────────────────────────────────────────────────────────────────┐
│ ITEMTREE Test UI                                  Backend: [__________] (blank │
│                                                              = same origin)    │
│ X-Ice-User: [testuser1]  X-Impersonated: [______]  [Probe home]  [Login]       │
├────────────────────────────────────────────────────────────────────────────────┤
│ Search: [_________________]  (●name ○id)  limit:[50]  [Search]   results ▼     │
├──────────────────────────────────┬─────────────────────────────────────────────┤
│ Tree                             │ Detail                                      │
│ ▼ root                           │  id: 25                                     │
│   ▶ ConfigFolder                 │  type: Shortcut.Report                      │
│   ▼ Users                        │  name: weeklySummary                        │
│     ▼ testuser1 ★                │  parentId: 14                               │
│       ▶ MyFolder                 │  path: root/Users/testuser1/MyFolder/…      │
│       • MyShortcut               │  lastUpdate: 2026-05-22T08:14:01Z           │
│       ✱ weeklySummary  (selected)│  lastUpdateUser: testuser1                  │
│     ▶ testuser2                  │ ──────────────────────────────────────────  │
│   ▶ DeepBranch                   │  Data (JSON)  [Edit] [Copy raw]             │
│                                  │  {                                          │
│                                  │    "report": { … }                          │
│                                  │  }                                          │
│ ──────────────                   │                                             │
│ [Refresh delta] [Refresh full]   │                                             │
│ last: delta 3 rows, 2026-05-24…  │                                             │
└──────────────────────────────────┴─────────────────────────────────────────────┘
                                                     ┌─────────────────────────┐
                                                     │ toast: Item created (id │
                                                     │ 1042). [×]              │
                                                     └─────────────────────────┘
```

### Header strip

- `X-Ice-User` text input (required).
- `X-Impersonated-User` text input (optional, left blank = no impersonation).
- `[Probe home]` button → `GET /users/{X-Ice-User-value}/home-folder`. Result
  rendered inline as a chip (id + name + type) or as a red banner with the
  Problem detail.
- `[Login]` button → triggers the data-load sequence (§3).
- `Backend URL` text input. Blank = relative paths (same origin); otherwise
  prepended to all API requests. Value is persisted in `localStorage` and
  survives a page refresh.

### Search strip

- Free-text input, radio toggle (●name / ○id, name selected by default),
  integer `limit` (default 50), `[Search]` button.
- Hitting `[Search]` calls `GET /api/v1/itemtree/search` with the current
  user headers.
- Results render as a small list below the search bar. Clicking a hit
  selects that node in the tree, fetching its subtree if it has not been
  loaded yet (§3 "Select node click").

### Left pane — Tree

Icons in row prefix:

- `▶` collapsed folder
- `▼` expanded folder
- `•` non-folder shortcut
- `✱` leaf with data
- `★` home-folder marker (appended after the name)

Interactions:

- Single click on the chevron (`▶`/`▼`) → toggle expand/collapse. On first
  expand of a folder whose subtree has not been loaded, fires `GET /tree/{id}/subtree`.
- Single click on the row name:
  - Folder → `POST /items/get { ids:[id] }` and render the returned
    `children` array as a child-table in the right pane (folder mode).
  - Non-folder → `POST /items/get` and render data in the right pane based
    on which payload field is populated.
- Right click on any node → context menu (§4).

Bottom of the pane: `[Refresh delta]` / `[Refresh full]` buttons calling
`POST /actuator/itemtree-refresh/delta` and `/full`, with a status line below
showing the last result (e.g. "delta: 3 rows, 2026-05-24T08:14:01Z").

### Right pane — Detail

Metadata block (always shown for the selected node): `id`, `parentId`,
`type`, `name`, `path` (if known), `lastUpdate`, `lastUpdateUser`.

Body, one of four modes:

- **Folder mode** — table of direct children: id, name, type, lastUpdate.
- **JSON mode** — pretty-printed JSON in a `<pre>` block, `[Edit]` button
  next to the section header that opens the Edit data modal.
- **XML mode** — raw XML in a `<pre>` block. `[Edit]` button is disabled
  with tooltip "XML items are read-only via API" (the `PUT
  /items/{id}/data` endpoint only accepts JSON; items with XML payloads
  were ingested through other channels and stay XML-only until
  Phase B's silent backfill rewrites them).
- **No-data mode** — only the metadata block plus a note "this type has
  no data".

### Modals

- **Edit data** — `<textarea>` pre-filled with pretty JSON; `[Save]` calls
  `PUT /items/{id}/data`, `[Cancel]` dismisses.
- **Create item** — form with `name`, `type` (dropdown of known types plus
  free-text override), optional JSON `data` textarea. Data textarea is
  disabled and forced to `null` when the chosen type is in the UI's
  hard-coded mirror of `types-without-data` (Folder, Shortcut,
  Shortcut.Report, Shortcut.Filter, Shortcut.Filter.Nested). `[Create]`
  calls `POST /items`.
- **Rename** — single text input pre-filled with current name; `[Save]`
  calls `POST /items/{id}/rename`.
- **Delete confirmation** — "Delete X? Cascades if folder." `[Delete]`
  calls `DELETE /items/{id}`, `[Cancel]` dismisses. No pre-count of
  descendants (no endpoint exposes that cheaply).

### Toasts

Bottom-right corner stack.

- Green for success, 2 s auto-dismiss.
- Red for errors, sticky until dismissed. Body shows
  `title · status · errorCode · detail · traceId`. Full Problem JSON
  rendered under a collapsible "raw" expander.

---

## 3. Data flow + client state

The UI keeps a single in-memory model — small enough that no framework or
store library is needed.

```javascript
state = {
  iceUser, impersonatedUser, backendBaseUrl,
  homeFolderId,
  tree: {
    nodesById:        { [id]: { itemTreeId, parentId, name, type,
                                lastUpdate, lastUpdateUser, path? } },
    childrenByParent: { [parentId]: Set<id> },
    expanded:         Set<id>,            // visually open folders
    loadedSubtreeOf:  Set<id>,            // folders fetched in full
    selectedId,
  },
  clipboard: { op:'cut'|'copy', sourceId, sourceName } | null,
  lastDetail: { id, mode:'folder'|'json'|'xml'|'no-data', payload },
  lastRefresh: { type, result, when } | null,
}
```

### Login flow (Login button)

1. Validate `X-Ice-User` is non-empty (inline form error otherwise).
2. Parallel:
   - `GET /api/v1/itemtree/users/{iceUser}/home-folder` → sets
     `homeFolderId`.
   - `GET /api/v1/itemtree/tree`.
3. When the home folder resolves: `GET /api/v1/itemtree/tree/{homeFolderId}/subtree`.
4. Merge `/tree` and `/subtree` responses into `nodesById` and
   `childrenByParent`. Mark every node returned by `/subtree` (root of the
   subtree plus all descendants) in `loadedSubtreeOf`. Mark the ancestor
   chain, the home folder, and every node under it as `expanded`.
5. Render. Any failure toasts the Problem and leaves the tree empty.
   `503` from `CacheReadinessFilter` during Login is retried per §5
   before the failure is surfaced.

The effective user for `/users/{userName}/home-folder` is always the value
of the `X-Ice-User` input (the API takes the username as a path parameter).
The `X-Impersonated-User` header is still sent on every request so the
server-side logging and audit see both.

### Folder expand click

- `id ∈ loadedSubtreeOf` → toggle `expanded` only (no network).
- Otherwise → `GET /tree/{id}/subtree`, merge into the model, mark every
  returned id in `loadedSubtreeOf`, add `id` to `expanded`.

### Select node click (single click on row name)

- Non-folder → `POST /items/get { ids:[id] }`. Inspect: `dataJson` set →
  JSON mode; `dataXml` set → XML mode; both null → no-data mode. Render
  right pane.
- Folder → `POST /items/get { ids:[id] }` and use the returned `children`
  array (one level deep) to render the folder-mode child table.

### After a successful mutation

The UI applies the local effect immediately:

- Create → splice new node into `nodesById` + `childrenByParent`.
- Delete → drop id and (for folders) every descendant id we have loaded.
- Rename → update name in place.
- Move → re-parent locally.
- Copy → splice the BFS-ordered subtree into the destination folder.
- Update data → store new payload in `lastDetail` if the selected node
  is the same id.

The local mirror tolerates missing references the same way the cache does
(per CLAUDE.md "apply* idempotency contract"). Missing references log to
the toast and silently skip — never throw.

### Drift recovery

Because mutations made on instance B (or via direct H2 pokes) do not reach
this UI automatically:

- `[Refresh delta]` / `[Refresh full]` call the actuator and display the
  `RefreshResult` in the bottom strip. **These reconcile the server cache
  only — the local UI model is not auto-refetched.**
- The folder right-click menu offers `Refresh subtree`, which re-fetches
  that one subtree and replaces it locally.
- Re-Login is the catch-all reset.

### Backend URL switch

Every API call uses `${state.backendBaseUrl}${path}`. Blank means relative
(same origin). Changing the URL clears the entire tree state and forces a
fresh Login. Value is persisted in `localStorage`.

---

## 4. Mutation flows — context menu + clipboard

Right-click on any tree node opens a context menu. Items vary by node:

| Node              | Menu items                                                                                          |
|-------------------|-----------------------------------------------------------------------------------------------------|
| Root (id 1)       | *(disabled — root cannot be mutated)*                                                               |
| Folder (non-root) | Refresh subtree · Create child · Rename · Delete · Cut · Copy · Paste here *(if clipboard set)*     |
| Leaf with data    | Edit data · Rename · Delete · Cut · Copy                                                            |
| Leaf no-data      | Rename · Delete · Cut · Copy                                                                        |

### Clipboard semantics

- **Cut** → stores `{op:'cut', sourceId, sourceName}`; the source row dims
  visually to show a pending cut.
- **Copy** → stores `{op:'copy', sourceId, sourceName}`; no visual change.
- **Paste here** (only on folder contexts, and only when the clipboard is
  non-empty):
  - `op === 'cut'` → `POST /items/{sourceId}/move {newParentId: targetId}`.
    Clipboard cleared on success.
  - `op === 'copy'` → `POST /items/{sourceId}/copy {destinationFolderId: targetId}`.
    Clipboard kept (allows paste into multiple folders).
- Pressing `Escape` clears the clipboard.

### Create child (folder context)

Modal form:

- `name` (1–70 chars).
- `type` dropdown of design §10 known types: `Folder`, `Shortcut`,
  `Shortcut.Report`, `Shortcut.Filter`, `Shortcut.Filter.Nested`,
  `DrillDown.Set`, `Report`, `Filter`, `Details.Column.Collection`,
  `Numeric.Bucket.Collection`, `Discrete.Bucket.Collection`,
  `Bucket.Collection`, `View`, `UDF.Context`, `Eval`. Plus a free-text
  override slot for testing unknown types (will hit `UNKNOWN_TYPE`).
- `data` JSON textarea. Disabled and force-`null` when the chosen type
  is in the UI's hard-coded mirror of `types-without-data` (Folder,
  Shortcut, Shortcut.Report, Shortcut.Filter, Shortcut.Filter.Nested).
- `[Create]` → `POST /items {parentId:ctx, name, type, data?}`. Toast
  201; splice into local model.

### Rename

Modal with single text input pre-filled with current name. `[Save]` →
`POST /items/{id}/rename {newName}`.

### Delete

Confirmation dialog: "Delete X? Cascades if folder." `[Delete]` →
`DELETE /items/{id}`. Toast 204; drop from local model.

### Edit data

Triggered by `[Edit]` in the right-pane data section on JSON items, or by
"Edit data" in the right-click menu of a leaf-with-data. Modal with a
pretty-printed JSON textarea. On `[Save]`: JSON-validate locally first; on
failure show inline error and do not submit. On success: `PUT /items/{id}/data {data}`,
toast 200, update local `lastDetail`.

### Why no tree-picker for Move/Copy

We deliberately avoid building a tree-picker modal. The clipboard +
Paste-here flow:

1. Keeps the UI minimal.
2. Forces the tester to exercise tree navigation and expand-on-demand as
   part of every mutation test (extra coverage for free).
3. Mirrors the desktop file-manager mental model.

---

## 5. Error handling

Every backend error comes back as RFC 7807 `application/problem+json`. The
UI has a single Problem renderer.

- Red toast, sticky until dismissed, showing
  `title · status · errorCode · detail · traceId`. The full Problem JSON
  is shown under a collapsible "raw" expander.

Selected error-code-specific behaviours:

- `503` from `CacheReadinessFilter` during Login → auto-retry up to 5×
  with 2 s backoff before surfacing the error.
- `404 ITEM_NOT_FOUND` on any read or mutation → after toasting, drop the
  id from the local model (the server is telling us it's gone).
- `404 HOME_FOLDER_NOT_FOUND` on Login → tree stays empty, header banner
  stays red until the user changes the username.
- `413 COPY_TOO_LARGE` → toast quotes both the configured cap and (if
  returned in `detail`) the actual subtree size.

Client-side validation runs before any network call:

- Empty name on Create / Rename → inline form error.
- Malformed JSON in Edit data or Create data textarea → inline form
  error, no network call.

Network or non-Problem responses → red toast with `Network error` and
the raw response text.

---

## 6. File layout

All UI files live under `src/main/resources/static/`:

```
src/main/resources/static/
├── index.html
├── styles.css
└── js/
    ├── app.js       — entry; event wiring on DOMContentLoaded
    ├── state.js     — in-memory model + localStorage persistence
    ├── api.js       — fetch wrappers, header injection, Problem
    │                  detection, 503 retry
    ├── tree.js      — render model → DOM, expand/collapse, click +
    │                  right-click handlers
    ├── detail.js    — right-pane rendering (folder/json/xml/no-data)
    ├── menu.js      — context-menu builder + clipboard
    ├── modal.js     — Create / Rename / Delete-confirm / Edit-data
    ├── search.js    — search bar + result list + navigate-to-id
    └── refresh.js   — actuator delta/full + home-folder probe
```

ES modules via `<script type="module">`. No bundler, no transpiler. Targets
modern evergreen browsers only.

### Required backend change

One non-UI change is required.

`CacheReadinessFilter.shouldNotFilter` currently exempts `/actuator/`,
`/v3/api-docs`, `/swagger-ui`. Extend it to skip everything that is not
under `/api/`, so the static UI loads even while the cache is still
warming up:

```java
@Override
protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return !path.startsWith("/api/");
}
```

Test additions in `CacheReadinessFilterTest`: assert that `/`, `/index.html`,
`/js/app.js`, `/styles.css`, `/favicon.ico` are *not* gated by the readiness
check, and assert that `/api/v1/itemtree/tree` still is.

---

## 7. Out of scope

- AuthN / AuthZ. The UI trusts the typed `X-Ice-User` value as-is.
- Pagination of large folders.
- Drag-and-drop. Cut/Copy + Paste handles all move/copy flows.
- Undo, history, diff view of data changes.
- Bulk multi-select mutation.
- Real-time updates (WebSocket / SSE). Refresh buttons + Re-Login are
  the drift recovery path.
- Accessibility audit, mobile layout, internationalisation.
- Automated UI tests. Manual exercise is the test.
- Persistence beyond `localStorage` of `iceUser`, `impersonatedUser`, and
  `backendBaseUrl`.
- Raw HTTP request/response inspector panel (deferred — the toast already
  surfaces Problem JSON; if useful later, a follow-up phase can add it).

---

## 8. Manual test plan

A session checklist that hits every endpoint at least once and every
documented `errorCode` we can reach without contriving server-side state.
"Endpoint coverage" appears as a cross-reference at the end.

1. **Login (testuser1, no impersonation)** — Probe returns testuser1's
   home folder; Login renders root → Users → testuser1 fully expanded
   with home subtree populated.
2. **Folder children view** — click a folder row (not the chevron); right
   pane shows child table from `/items/get`.
3. **JSON item view** — click a `Report` leaf; observe pretty JSON.
4. **XML item view** — click the XML-only seeded row; observe raw XML,
   Edit disabled.
5. **No-data shortcut view** — click a `Shortcut`; observe "this type
   has no data" note.
6. **Create folder** — right-click testuser1 → Create child → Folder
   "TestFolderA". Toast + tree row insert.
7. **Create Report** — under TestFolderA, type `Report`, name
   "MyReport", paste a small JSON body. Toast + insert.
8. **Rename** — TestFolderA → "Renamed". Local rename only, no
   re-fetch.
9. **Edit data** — modify MyReport's JSON, Save, re-click the node,
   confirm the new payload.
10. **Cut + Paste** — Cut MyReport → expand a different folder → Paste
    here. Row moves.
11. **Copy + Paste** — Copy MyReport → Paste into two different folders.
    Each paste produces a fresh id; on sibling-name collision the name
    suffix is `(copy)` then `(copy 2)`.
12. **Delete leaf** then **Delete folder cascade** — confirm subtree
    removal in the local model.
13. **Search by name** — "weekly" → click hit → tree navigates and
    selects.
14. **Search by id** — id `1` → root returned.
15. **Refresh delta** → `RefreshResult` chip; **Refresh full** → drift
    counters chip.
16. **Impersonation** — Ice=testuser1, Impersonated=testuser2 → Login
    uses testuser2's home folder.
17. **Negative — bogus user** — type `ghost` → Probe shows 404
    `HOME_FOLDER_NOT_FOUND`.
18. **Negative — copy too large** — lower `itemtree.copy.max-nodes` in
    `application-dev.yml` to 2, restart, copy a 4-node subtree → 413
    `COPY_TOO_LARGE`.
19. **Negative — copy into descendant** — copy testuser1 → paste inside
    testuser1's own subtree → 400 `COPY_INTO_DESCENDANT`.
20. **Negative — type cannot have data** — Create child → Folder with
    JSON body forced via dev tools → 400 `TYPE_CANNOT_HAVE_DATA`.
21. **Negative — missing parent on create** — dev tools forge a request
    with non-existent `parentId` → 400 `PARENT_NOT_FOUND`.
22. **Backend URL switch** — point at a second Spring instance on a
    different port; Login; observe completely separate tree state.

### Endpoint coverage matrix

| Endpoint                                          | Steps                |
|---------------------------------------------------|----------------------|
| GET `/users/{userName}/home-folder`               | 1, 16, 17            |
| GET `/tree`                                       | 1, 16, 22            |
| GET `/tree/{rootId}/subtree`                      | 1, 13, 22            |
| POST `/items/get`                                 | 2, 3, 4, 5, 9        |
| POST `/items`                                     | 6, 7, 20, 21         |
| POST `/items/{id}/move`                           | 10                   |
| POST `/items/{id}/copy`                           | 11, 18, 19           |
| POST `/items/{id}/rename`                         | 8                    |
| PUT  `/items/{id}/data`                           | 9                    |
| DELETE `/items/{id}`                              | 12                   |
| GET `/search`                                     | 13, 14               |
| POST `/actuator/itemtree-refresh/{type}`          | 15                   |

Every endpoint is exercised at least once on a happy path. Most negative
paths and `errorCode` values are reachable through steps 17–21; the
remaining error codes (e.g. `NEW_PARENT_NOT_FOUND`, `MOVE_INTO_DESCENDANT`,
`PARENT_NOT_FOLDER`) are reachable with the same dev-tools-forge approach
as step 21 if the tester wants full coverage.

---

## 9. Open questions

None at design time. Every prompt the brainstorming surfaced has been
answered or explicitly deferred to a follow-up phase (raw HTTP inspector,
health badge — see §7).

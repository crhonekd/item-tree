# Phase 21 — Embed search results in the tree + test-UI restyle

**Date:** 2026-05-28
**Status:** Design approved (pending spec review)
**Scope:** Test UI (`src/main/resources/static/**`) primarily, plus a small, contract-level
backend enrichment of the `/search` response. No change to the persistence, cache, messaging,
or refresh layers.

---

## 1. Motivation

The test UI lists search hits in a flat list above the tree. To understand *where* a hit lives,
the user has to click it and hope the tree reveals it — which today only works if the hit's
ancestors happen to already be loaded (the `/tree` view loads only a depth-0–2 skeleton plus the
home-folder chain). Two improvements:

1. **Embed mode** — a "Embed results in tree" checkbox. When checked, every search hit is revealed
   *in place* inside the tree (ancestors expanded, hit highlighted) instead of being listed on top.
2. **Single-hit reveal from the list** — when the checkbox is **off**, clicking a hit in the list
   reveals just that one item in the tree, selects it, loads its detail, and scrolls to it.

Both rely on knowing each hit's full ancestor chain. Rather than have the client walk the parent
chain with N round-trips, the `/search` endpoint is enriched to return each hit's ancestors
directly — one request carries everything the UI needs.

Secondarily, the UI is restyled from bare default HTML to a professional **warm-neutral & teal**
look, with container-level layout polish that does **not** touch the tree-render hot path.

---

## 2. Backend change — enrich `/search`

### 2.1 Response shape

`SearchHit` gains two fields:

| Field | Type | Required | Meaning |
|---|---|---|---|
| `parentId` | int64 | yes | The hit's parent id (`0` for root). Makes the hit self-sufficient as a tree node. |
| `ancestors` | array of `ItemNode` | yes (may be empty) | The chain from **root → the hit's parent**, exclusive of the hit, ordered **root-first**. Empty when the hit is the root. |

Each `ancestors` entry is a structural `ItemNode` (`itemTreeId`, `parentId`, `name`, `type`,
`lastUpdate`, `lastUpdateUser`). Its `path` is left **null** — the client reconstructs tree
structure from `parentId`, so ancestor paths are not computed (avoids needless work).

The 200 response stays an **array of `SearchHit`** (no envelope change).

Example (`GET /search?q=report`):

```json
[
  {
    "itemTreeId": 42,
    "parentId": 9,
    "name": "Report-Q1",
    "type": "IceReport",
    "path": "/root/Users/alice/Report-Q1",
    "ancestors": [
      { "itemTreeId": 1, "parentId": 0, "name": "root",  "type": "Folder", "lastUpdate": "…", "lastUpdateUser": "…" },
      { "itemTreeId": 4, "parentId": 1, "name": "Users", "type": "Folder", "lastUpdate": "…", "lastUpdateUser": "…" },
      { "itemTreeId": 9, "parentId": 4, "name": "alice", "type": "Folder", "lastUpdate": "…", "lastUpdateUser": "…" }
    ]
  }
]
```

### 2.2 OpenAPI

In `src/main/resources/openapi/itemtree-api.yaml`, the `SearchHit` schema:

- add `parentId` (`integer`, `format: int64`) and include it in `required`;
- add `ancestors` (`type: array`, `items: $ref ItemNode`) and include it in `required`, with a
  description stating it is root→parent, exclusive of the hit, possibly empty.

Regenerating produces `SearchHit.getParentId/setParentId` and `getAncestors/setAncestors`
(`List<ItemNode>`).

### 2.3 `PathResolver.ancestorsOf`

Add to the `PathResolver` interface:

```java
/**
 * Returns the ancestor chain root→parent (exclusive of the id itself) for each id,
 * ordered root-first. Memoised within a single call so a shared ancestor chain is
 * walked once. Unknown id → empty list. Partial result (no root prefix) on a missing
 * ancestor or a suspected cycle, mirroring {@link #pathsOf}.
 */
Map<Long, List<CachedNode>> ancestorsOf(Collection<Long> ids);
```

`DefaultPathResolver` implements it with a self-contained `chainFor(id, memo)` private method that
builds the **root→node inclusive** chain (memoising each prefix), parallel in structure to the
existing `pathFor` (same `MAX_TREE_DEPTH` cap, same missing-ancestor / cycle WARN logging, same
partial-result behaviour). `ancestorsOf` returns each chain with its last element (the node itself)
dropped. **The existing `pathFor` / `pathsOf` code is left untouched** to avoid disturbing the
tested path logic; the small walk-loop similarity between `pathFor` and `chainFor` is accepted.

### 2.4 Service + mapper

- `SearchHitView` becomes `record SearchHitView(CachedNode node, String path, List<CachedNode> ancestors)`.
- `SearchService.search` computes `Map<Long,List<CachedNode>> anc = pathResolver.ancestorsOf(ids)`
  alongside the existing `pathsOf`, and builds each view with
  `anc.getOrDefault(id, List.of())`.
- `SearchHitMapper` injects `ItemNodeMapper`; in `toDto` it sets `dto.setParentId(node.parentId())`
  and `dto.setAncestors(view.ancestors().stream().map(itemNodeMapper::toDto).toList())`
  (using the `toDto(CachedNode)` overload, which leaves `path` null).

No change to `TreeCache`, `SearchController`'s validation, or any other layer.

### 2.5 Design doc

`itemtree-service-design.md`: update §3 (search response schema documents `parentId` + `ancestors`)
and add a one-line note in §9 that `ancestorsOf` shares the §9 walk semantics with `pathsOf`.

---

## 3. Frontend — embed mode

### 3.1 Markup (`index.html`)

In `.search-bar`, after the Search button, add:

```html
<label class="embed-toggle"><input id="embed-in-tree" type="checkbox"> Embed results in tree</label>
<button id="search-clear-btn" type="button" hidden>Clear</button>
<span id="search-status" class="search-status"></span>
```

### 3.2 State (`state.js`)

- Persist `embedInTree` (boolean) in `localStorage` alongside `iceUser` / `impersonatedUser` /
  `backendBaseUrl`.
- Add `state.search = { matchIds: new Set() }`. `resetTreeState()` clears `matchIds`.

### 3.3 Search behaviour (`search.js`)

`runSearch()` branches on the checkbox:

**Checkbox OFF (list mode — current behaviour, plus improved click):**
- Render the hit list as today (`id type name`), but the click handler now calls
  `revealHit(hit)` (below) with the full hit object instead of just the id.
- Clear `state.search.matchIds`; hide `#search-clear-btn`; clear `#search-status`.

**Checkbox ON (embed mode):**
- Hide the list. For each hit:
  - `ingestNodes([...hit.ancestors, { itemTreeId: hit.itemTreeId, parentId: hit.parentId, name: hit.name, type: hit.type }])`
  - `for (const a of hit.ancestors) state.tree.expanded.add(a.itemTreeId)`
  - `state.search.matchIds.add(hit.itemTreeId)`
- `renderTree()`, show `#search-clear-btn`, set `#search-status` to `"N matches"`.
- Scroll the first match into view.

**Empty result set:** `#search-status` = `"(no results)"`; in list mode also render the existing
`(no results)` list item.

### 3.4 Single-hit reveal (`revealHit`, list mode)

Reveals exactly one item using the ancestors already in the hit (no extra call to embed), then
loads its detail:

```js
async function revealHit(hit) {
  ingestNodes([...hit.ancestors, hitNode(hit)]);
  for (const a of hit.ancestors) state.tree.expanded.add(a.itemTreeId);
  await selectAndLoad(hit.itemTreeId);   // exported from tree.js: select + getItems + render detail
  scrollToNode(hit.itemTreeId);
}
```

`selectAndLoad(id)` is the existing body of `tree.js`'s internal `onNameClick`, extracted and
exported so both a tree-label click and a search-list click share it (sets `selectedId`, renders
the tree, fetches `getItems([id])`, renders folder/item detail). `scrollToNode(id)` is the existing
`row?.scrollIntoView(...)` snippet, factored into a tiny helper.

### 3.5 Highlight rendering (`tree.js`)

In `renderNode`, add class `search-match` to `row` when
`state.search.matchIds.has(id)`. One class toggle inside the existing loop — no extra pass, no
new listeners.

### 3.6 Clear

`#search-clear-btn` click: `state.search.matchIds.clear()`, hide the button, clear
`#search-status`, `renderTree()`. Expansion produced by the embed is **left in place** (simpler,
no extra bookkeeping). Toggling the checkbox off and searching again also returns to list mode.

---

## 4. Frontend — restyle (warm neutral & teal)

### 4.1 Palette (CSS custom properties in `:root`)

| Token | Value | Use |
|---|---|---|
| `--surface` | `#fffefb` | pane / card background |
| `--page-bg` | `#f5f3ee` | body background, hover tint |
| `--text` | `#3a3631` | primary text |
| `--muted` | `#7a736a` | secondary text, headings |
| `--border` | `#e7e2d8` | borders, dividers |
| `--accent` | `#0d9488` | primary buttons, focus ring, chevrons |
| `--accent-hover` | `#0f766e` | button hover |
| `--selected` | `#ccfbf1` | selected tree row |
| `--match-bg` | `#fef3c7` | search-match row background (amber — distinct from selection) |
| `--match-bar` | `#f59e0b` | left accent bar on a match row |

`--match-*` (amber) is deliberately different from `--selected` (teal) so a highlighted search
match is visually distinct from a click-selected row.

### 4.2 Layout polish (container-level, CSS-only)

- **Header**: sticky top, `--surface` background, bottom border + subtle shadow, refined brand/title.
- **Search bar**: defined strip with the embed toggle, clear button, and status; consistent inputs.
- **Tree & detail panes**: "card" treatment — `--surface` background, rounded corners, `--border`
  border, subtle shadow on the *pane* only.
- **Buttons**: teal primary (Search, Login) with white text; ghost/secondary (Probe, Refresh,
  Clear) with `--border` outline; consistent padding, radius, hover.
- **Inputs**: consistent border + teal focus ring.
- **Toasts / modals / context-menu**: restyled to match (kept functional; colors and radii only).
- **Detail pane**: tidier spacing; the existing metadata grid and `pre`/table styling reskinned.

### 4.3 Performance guardrails (hard requirements)

- `.tree-row`, `.tree-label`, `.tree-chevron`, `.tree-icon` carry **only** `background-color`
  changes for hover / `.selected` / `.search-match`. **No `box-shadow`, no `transition`, no
  `filter`, no gradients on per-row selectors.**
- All shadows / rounded cards live on the **header and the two pane containers** (constant element
  count), never on tree nodes.
- `renderTree()` / `renderNode()` algorithm is unchanged; the only addition is the `search-match`
  class toggle. No new per-node event listeners.

This keeps option-2 layout polish at zero added cost to tree rendering.

---

## 5. Files touched

**Backend**
- `src/main/resources/openapi/itemtree-api.yaml` — `SearchHit` gains `parentId` + `ancestors`.
- `service/PathResolver.java` — add `ancestorsOf`.
- `service/DefaultPathResolver.java` — implement `ancestorsOf` via `chainFor`.
- `service/SearchHitView.java` — add `ancestors`.
- `service/SearchService.java` — attach ancestors.
- `api/mapper/SearchHitMapper.java` — inject `ItemNodeMapper`; map `parentId` + `ancestors`.

**Frontend**
- `static/index.html` — embed checkbox, clear button, status; minor card wrappers.
- `static/styles.css` — full re-skin with CSS variables.
- `static/js/state.js` — `embedInTree` persistence; `state.search.matchIds`.
- `static/js/search.js` — embed branch, `revealHit`, status/clear wiring.
- `static/js/tree.js` — export `selectAndLoad` / `scrollToNode`; add `search-match` class.
- `static/js/app.js` — bind checkbox (persist) and clear button.
- `static/js/api.js` — **unchanged** (`getItems` already exists; ancestors arrive on the hit).

**Docs**
- `itemtree-service-design.md` — §3 + §9 updates.
- `IMPLEMENTATION_NOTES.md` — Phase 21 section.

---

## 6. Testing

**Backend (unit + E2E):**
- `DefaultPathResolverTest` — new `@Nested AncestorsOf`: deep chain (root→parent, correct order,
  excludes the node); root id → empty; unknown id → empty; missing-ancestor mid-walk → partial (no
  root prefix) + WARN; cycle cap → partial; memoised sharing of a common prefix across two ids.
- `SearchServiceTest` — hits carry ancestors (mock `pathResolver.ancestorsOf`); root hit → empty
  ancestors; ordering preserved; existing tests updated for the 3-arg `SearchHitView`.
- `SearchHitMapperTest` — `parentId` set; `ancestors` mapped in order with correct fields; ancestor
  `path` is null; empty ancestors → empty list.
- `SearchControllerTest` — response DTOs carry `parentId` + `ancestors` (mock service).
- `ItemTreeApplicationE2EIT` — one roundtrip: search a deep item, assert the `ancestors` chain
  (ids + names, root→parent) matches the dummy-data tree.
- `ApiContractTest` — still asserts 11 operations (no new operation).

**Frontend:** no automated UI tests (consistent with the Phase 15 precedent — the test UI is a
manual harness). Manual smoke checklist is the UI done-when (§7).

---

## 7. Done when

- `./gradlew clean build` → BUILD SUCCESSFUL; full suite green (new backend tests added).
- Manual smoke against the dev profile:
  1. Checkbox **off**: search lists hits; clicking a hit deep outside the home subtree reveals it
     in the tree, selects it, shows its detail, and scrolls to it.
  2. Checkbox **on**: search reveals **all** matches in place (ancestors expanded, amber highlight),
     list hidden, status shows `"N matches"`, scrolls to first; **Clear** removes the highlight.
  3. Restyle: warm-neutral & teal look applied across header / search / tree / detail / toasts /
     modals / context-menu; match highlight (amber) is visually distinct from selection (teal).
  4. Tree expand/collapse and selection remain visibly snappy (no per-row perf regression).
- `itemtree-service-design.md` (§3, §9) and `IMPLEMENTATION_NOTES.md` (Phase 21) updated.
- Memory note added on completion.

---

## 8. Out of scope

- No new search semantics (still single `q`, numeric-then-name).
- No backend change beyond the `SearchHit` enrichment (no new endpoints, no envelope change).
- No automated UI tests.
- No persistence / cache / messaging / refresh changes.

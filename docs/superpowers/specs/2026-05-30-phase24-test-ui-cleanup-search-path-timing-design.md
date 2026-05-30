# Phase 24 — Test-UI cleanup, search-path fix, controller timing logs

**Date:** 2026-05-30
**Status:** Approved (design)
**Phase:** 24

## Summary

Three small, independent changes:

1. **Test UI cleanup** — remove the "Backend" textbox and the "Probe home" button;
   hide the Name field when creating a `UDFRepo`.
2. **Search "copy full path" fix** — make the test UI carry the `path` already
   returned by the backend so "Show and copy full path" works on search results.
3. **Controller call-timing logging** — log HTTP method, URI, status, and
   duration for every request.

This is Phase A work (personal PC). No Oracle, no company libraries, no schema
changes.

## Background / problem statement

- The test UI still exposes a **"Backend" base-URL textbox** and a **"Probe home"
  button** that are no longer wanted. The UI is only ever used same-origin.
- Creating a `UDFRepo` (Phase 23) forces the name to the username server-side
  (`ItemService.createItem`, `name = effectiveUser`). The create modal still
  shows a Name field and enforces "Name required", which is pointless and
  confusing for this type.
- Right-clicking a **search result** and choosing "Show and copy full path"
  reports *"No path was returned by the server for this node."* This looked like
  a backend gap but is **not**: `SearchHitMapper.toDto` already calls
  `dto.setPath(view.path())`, and the OpenAPI `SearchHit` schema has a nullable
  `path`. The real cause is in the UI: `search.js`'s `hitNode(hit)` rebuilds the
  node from only `{itemTreeId, parentId, name, type}` and drops `path` before it
  is ingested into the tree state.
- There is currently no per-request timing log. The user wants to see how long
  each controller call took.

## Goals

- Remove the "Backend" textbox and "Probe home" button and their now-dead
  plumbing from the static test UI.
- Hide the Name field in the create modal when the effective type is `UDFRepo`,
  skip the "Name required" check, and send a placeholder name.
- Preserve `path` on search-result nodes so "copy full path" works.
- Emit an INFO log line per HTTP request with method, URI, status, and duration.

## Non-goals

- No backend change to search path resolution (already correct).
- No new automated test layer for the static JS UI (none exists; manual verify).
- No change to time handling — request timing uses `System.nanoTime()`, a
  monotonic duration source, not a wall-clock API, so `TimeMapper` is not
  involved.
- No schema, OpenAPI-contract, or dependency changes.

## Design

### 1. Remove the "Backend" textbox

- `index.html`: delete the `<label>Backend: …</label>` wrapper and the
  `#backend-url` input.
- `app.js`: remove the line that sets `#backend-url`'s value and the `change`
  listener that mutated `state.backendBaseUrl`.
- `state.js`: remove `backendBaseUrl` from both the initial state (the
  `persisted.backendBaseUrl ?? ''`) and the `save()` serialisation.
- `api.js`: simplify `url(path)` to return `path` directly (same origin).

Net behaviour: the UI always talks to the same origin it was served from. The
`backendBaseUrl` plumbing is fully removed (not left inert).

### 2. Remove the "Probe home" button

- `index.html`: delete `#probe-btn` and the adjacent `#probe-result` span.
- `app.js`: remove the `probe-btn` click registration and drop `runProbe` from
  the `./refresh.js` import (keep `runRefresh`).
- `refresh.js`: delete the now-unused `runProbe` export and any helper used only
  by it. `runRefresh` and the refresh-status wiring remain unchanged.

### 3. Hide Name on `UDFRepo` create

In `modal.js` `openCreateModal`:

- The modal already has a `recompute()` that derives the **effective type**
  (`customType.value.trim() || typeSel.value`) and toggles the data textarea.
  Extend it to also show/hide the Name label + `#cm-name` input based on whether
  the effective type is `UDFRepo`. Reuse a `UDF_REPO = 'UDFRepo'` constant.
- On the Create click handler: when the effective type is `UDFRepo`, skip the
  `if (!name) { … 'Name required' … }` guard and send a non-empty placeholder
  name (`'UDFRepo'`). The backend overrides it to the username regardless
  (`ItemService.createItem` line ~138), and the OpenAPI contract requires
  `name` length ≥ 1, so a non-empty placeholder satisfies request validation.
- Triggering is on the **effective** type, mirroring the existing data-disable
  logic — so typing `UDFRepo` in the custom-type box hides Name too.

### 4. Carry `path` on search hits (UI-only)

In `search.js`:

- `hitNode(hit)` must include `path: hit.path` so the ingested node retains the
  path the backend already returned.
- No other change needed: `ingestHit` → `ingestNodes` stores the node, and
  `menu.js`'s "Show and copy full path" reads `node.path`.

### 5. Controller call-timing logging

- New `RequestTimingInterceptor implements HandlerInterceptor` in
  `com.myxcomp.ice.xtree.api.filter`.
  - `preHandle`: stash `System.nanoTime()` as a request attribute
    (e.g. `RequestTimingInterceptor.START_NS`); return `true`.
  - `afterCompletion`: read the start attribute (guard against absence), compute
    elapsed ms, and log at **INFO**:
    `log.info("{} {} -> {} ({} ms)", method, requestURI, status, ms)`.
    `afterCompletion` is chosen over `postHandle` so timing still logs when the
    handler threw and the exception was translated by `GlobalExceptionHandler`.
- Register it in `WebMvcConfig.addInterceptors` (which already registers
  `UserContextInterceptor`). Add it **before** `UserContextInterceptor` so it is
  outermost and captures true end-to-end duration, scoped to the same
  `/api/v1/itemtree/**` path pattern. (Actuator/refresh endpoints are out of
  scope — the request is "each controller call" on the REST API.)

## Testing

- **`RequestTimingInterceptor` unit test** (mirrors existing interceptor tests):
  `preHandle` sets the start attribute and returns `true`; `afterCompletion`
  with a start attribute present completes without error; `afterCompletion`
  with no start attribute (defensive path) does not throw.
- **Search-path backend contract test**: assert `SearchService` /
  `SearchHitMapper` yields a populated `path` on a hit (locks the contract, since
  the symptom looked backend-shaped). Extend an existing search test if one fits.
- **Interceptor wiring**: confirm via the existing HTTP-slice/WebMvc test harness
  that the interceptor is registered (light assertion; do not test Spring's own
  interceptor machinery).
- **UI changes** (items 1–4): static ES-module JS with no automated harness —
  verify manually against the running app (create-UDFRepo hides Name; search →
  copy full path works; Backend box and Probe button gone).
- Run the **full suite** and confirm green before committing.

## Invariants / constraints honoured

- Controllers stay thin: timing lives in an interceptor, not in controller
  methods (CLAUDE.md "no business logic in controllers").
- No wall-clock API used — `System.nanoTime()` is monotonic duration, outside
  the `TimeMapper` rule's scope.
- No imports from `generated.*` outside `api/mapper`, no `com.barcap.*` imports,
  no schema/migration tooling.

## Open implementation details (decided)

- `backendBaseUrl` plumbing is **fully removed**, not left inert.
- Log format: `"{} {} -> {} ({} ms)"`.

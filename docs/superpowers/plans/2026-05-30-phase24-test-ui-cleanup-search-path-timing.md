# Phase 24 — Test-UI cleanup, search-path fix, controller timing logs — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Tidy the static test UI (drop the Backend textbox + Probe-home button, hide the Name field for UDFRepo creation), fix "copy full path" on search results, and add per-request timing logs to the REST API.

**Architecture:** Items 1–4 are static ES-module JavaScript / HTML / CSS edits under `src/main/resources/static/` — no automated test layer exists, so they are edit → manual-verify → commit. Item 5 adds a `HandlerInterceptor` in `api/filter` (TDD with a unit test) registered in the existing `WebMvcConfig`. The search-path backend contract is already correct and already covered by `SearchServiceTest`, so no backend code change is needed there.

**Tech Stack:** Java 21, Spring MVC `HandlerInterceptor`, JUnit 5 + AssertJ + Spring `MockHttpServletRequest/Response`, vanilla ES-module JS.

**Spec:** `docs/superpowers/specs/2026-05-30-phase24-test-ui-cleanup-search-path-timing-design.md`

---

## File map

| File | Change |
|---|---|
| `src/main/resources/static/index.html` | Remove `#backend-url` label/input; remove `#probe-btn` and `#probe-result` span |
| `src/main/resources/static/js/app.js` | Remove backend-url binding + change listener; remove probe-btn binding; drop `runProbe` import |
| `src/main/resources/static/js/state.js` | Remove `backendBaseUrl` from state init and `savePersisted` |
| `src/main/resources/static/js/api.js` | `url(path)` returns `path` directly; drop `state` import if unused |
| `src/main/resources/static/js/refresh.js` | Delete `runProbe` export |
| `src/main/resources/static/styles.css` | Delete `.probe-result` rules |
| `src/main/resources/static/js/modal.js` | Hide Name field + skip name-required for UDFRepo in `openCreateModal` |
| `src/main/resources/static/js/search.js` | `hitNode` carries `path` |
| `src/main/java/com/myxcomp/ice/xtree/api/filter/RequestTimingInterceptor.java` | **Create** — timing interceptor |
| `src/test/java/com/myxcomp/ice/xtree/api/filter/RequestTimingInterceptorTest.java` | **Create** — unit test |
| `src/main/java/com/myxcomp/ice/xtree/api/filter/WebMvcConfig.java` | Register the interceptor (outermost) |

---

## Task 1: Remove the "Backend" textbox

**Files:**
- Modify: `src/main/resources/static/index.html`
- Modify: `src/main/resources/static/js/app.js`
- Modify: `src/main/resources/static/js/state.js`
- Modify: `src/main/resources/static/js/api.js`

- [ ] **Step 1: Remove the Backend label/input from `index.html`**

Delete these two lines from the `.header-controls` block:

```html
      <label>Backend:
        <input id="backend-url" type="text" size="22" placeholder="(blank = same origin)">
      </label>
```

- [ ] **Step 2: Remove the backend-url wiring from `app.js` `bindHeader()`**

Delete the value-set line:

```js
  $('backend-url').value = state.backendBaseUrl;
```

and delete the entire `change` listener block:

```js
  $('backend-url').addEventListener('change', (e) => {
    const newUrl = e.target.value.trim();
    if (newUrl !== state.backendBaseUrl) {
      state.backendBaseUrl = newUrl;
      savePersisted();
      resetTreeState();
      renderTree();
      $('detail-root').innerHTML = '(backend URL changed — log in again)';
    }
  });
```

`resetTreeState` was imported only for this listener. After Task 2 also removes nothing else using it, check the `app.js` import line `import { state, savePersisted, resetTreeState, ingestNodes } from './state.js';` — `resetTreeState` is still used in `doLogin()`, so **leave the import as-is**.

- [ ] **Step 3: Remove `backendBaseUrl` from `state.js`**

In the `state` object, delete the line:

```js
  backendBaseUrl: persisted.backendBaseUrl ?? '',
```

In `savePersisted()`, delete the line:

```js
    backendBaseUrl: state.backendBaseUrl,
```

- [ ] **Step 4: Simplify `url()` in `api.js`**

Replace:

```js
import { state } from './state.js';
```
```js
function url(path) {
  return `${state.backendBaseUrl}${path}`;
}
```

with:

```js
import { state } from './state.js';
```
```js
function url(path) {
  return path;
}
```

(Keep the `state` import — `buildHeaders` still reads `state.iceUser` / `state.impersonatedUser`.)

- [ ] **Step 5: Manual verify**

Run the app (`./gradlew bootRun`), open the UI. Confirm: the "Backend" textbox is gone from the header, and login/tree/search still work (all calls hit same origin). Check the browser console for no `backend-url` reference errors.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/static/index.html src/main/resources/static/js/app.js src/main/resources/static/js/state.js src/main/resources/static/js/api.js
git commit -m "feat(phase24): remove Backend base-URL textbox from test UI

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: Remove the "Probe home" button

**Files:**
- Modify: `src/main/resources/static/index.html`
- Modify: `src/main/resources/static/js/app.js`
- Modify: `src/main/resources/static/js/refresh.js`
- Modify: `src/main/resources/static/styles.css`

- [ ] **Step 1: Remove the probe button and result span from `index.html`**

Delete this line:

```html
      <button id="probe-btn" type="button">Probe home</button>
```

and this line:

```html
      <span id="probe-result" class="probe-result"></span>
```

- [ ] **Step 2: Remove probe wiring from `app.js`**

Change the import line from:

```js
import { runRefresh, runProbe } from './refresh.js';
```

to:

```js
import { runRefresh } from './refresh.js';
```

Delete the probe binding line in `bindHeader()`:

```js
  $('probe-btn').addEventListener('click', runProbe);
```

- [ ] **Step 3: Delete `runProbe` from `refresh.js`**

Remove the entire `export async function runProbe() { … }` function (the whole block from `export async function runProbe() {` to its closing `}`). Leave `runRefresh` and the imports (`state`, `api`, `ProblemError`, `toastError`, `toastSuccess`) — all are still used by `runRefresh`.

- [ ] **Step 4: Delete the `.probe-result` CSS rules**

In `styles.css`, delete these three lines:

```css
.probe-result { margin-left: 8px; font-family: monospace; }
.probe-result.ok { color: var(--accent); }
.probe-result.err { color: #b00020; }
```

- [ ] **Step 5: Manual verify**

Reload the UI. Confirm the "Probe home" button is gone, no console error referencing `probe-btn` / `probe-result`, and the "Login" button + refresh strip still work.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/static/index.html src/main/resources/static/js/app.js src/main/resources/static/js/refresh.js src/main/resources/static/styles.css
git commit -m "feat(phase24): remove Probe-home button from test UI

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: Hide the Name field when creating a UDFRepo

**Files:**
- Modify: `src/main/resources/static/js/modal.js`

Context: `openCreateModal` already computes the **effective type** as
`customType.value.trim() || typeSel.value` inside `recompute()` and uses it to
disable the data textarea. We extend that same flow. The backend forces
`name = effectiveUser` for UDFRepo (`ItemService.createItem`), and the OpenAPI
contract requires `name` length ≥ 1, so we send a non-empty placeholder.

- [ ] **Step 1: Add a `UDF_REPO` constant**

Near the top of `modal.js`, below the existing `TYPES_WITHOUT_DATA` set, add:

```js
const UDF_REPO = 'UDFRepo';
```

- [ ] **Step 2: Give the Name label/input stable ids in the create modal markup**

In `openCreateModal`, change the Name markup from:

```html
    <label>Name</label>
    <input id="cm-name" type="text" maxlength="70">
```

to:

```html
    <label id="cm-name-label">Name</label>
    <input id="cm-name" type="text" maxlength="70">
```

- [ ] **Step 3: Toggle the Name field inside `recompute()`**

In the `onMount` callback, capture the new elements alongside the existing ones:

```js
    const typeSel = root.querySelector('#cm-type');
    const customType = root.querySelector('#cm-custom-type');
    const dataArea = root.querySelector('#cm-data');
    const nameInput = root.querySelector('#cm-name');
    const nameLabel = root.querySelector('#cm-name-label');
    const err = root.querySelector('#cm-error');
```

and extend `recompute()` to hide/show the Name field for UDFRepo:

```js
    const recompute = () => {
      const effective = customType.value.trim() || typeSel.value;
      const noData = TYPES_WITHOUT_DATA.has(effective);
      dataArea.disabled = noData;
      if (noData) dataArea.value = '';
      const isUdfRepo = effective === UDF_REPO;
      nameLabel.hidden = isUdfRepo;
      nameInput.hidden = isUdfRepo;
      if (isUdfRepo) nameInput.value = '';
    };
```

- [ ] **Step 4: Skip the name-required check and send a placeholder on submit**

In the `#cm-create` click handler, change:

```js
      const name = root.querySelector('#cm-name').value.trim();
      const type = customType.value.trim() || typeSel.value;
      if (!name) { err.textContent = 'Name required'; return; }
```

to:

```js
      const type = customType.value.trim() || typeSel.value;
      const isUdfRepo = type === UDF_REPO;
      const name = isUdfRepo ? UDF_REPO : root.querySelector('#cm-name').value.trim();
      if (!isUdfRepo && !name) { err.textContent = 'Name required'; return; }
```

(The rest of the handler — JSON parse, `api.createItem`, ingest — is unchanged. For UDFRepo, `dataArea.disabled` is false since UDFRepo is not in `TYPES_WITHOUT_DATA`, matching Phase 23: a UDFRepo carries JSON data.)

- [ ] **Step 5: Manual verify**

Reload the UI, log in as `testuser1`, right-click the home folder → Create child.
- Selecting `UDFRepo` in the Type dropdown hides the Name label + input.
- Typing `UDFRepo` in the custom-type box also hides them.
- Switching back to another type re-shows the Name field.
- Note `testuser1` already has a seeded UDFRepo (Phase 23), so an actual create will return `UDF_REPO_ALREADY_EXISTS` — that toast confirms the request reached the backend with a valid (placeholder) name and was rejected for the right reason, not for a blank name. To see a successful create, use a fresh user whose home folder has no UDFRepo yet.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/static/js/modal.js
git commit -m "feat(phase24): hide Name field when creating a UDFRepo in test UI

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 4: Carry `path` on search-result nodes

**Files:**
- Modify: `src/main/resources/static/js/search.js`

Context: the backend already returns `path` on each `SearchHit` (verified:
`SearchHitMapper.toDto` calls `dto.setPath(view.path())`; covered by
`SearchServiceTest.searchByNamePopulatesPath` / `searchByIdPopulatesPath`).
`hitNode(hit)` drops it before ingest, so `menu.js`'s "Show and copy full path"
sees `node.path === undefined`.

- [ ] **Step 1: Include `path` in `hitNode`**

Change:

```js
function hitNode(hit) {
  return { itemTreeId: hit.itemTreeId, parentId: hit.parentId, name: hit.name, type: hit.type };
}
```

to:

```js
function hitNode(hit) {
  return { itemTreeId: hit.itemTreeId, parentId: hit.parentId, name: hit.name, type: hit.type, path: hit.path };
}
```

- [ ] **Step 2: Manual verify**

Reload the UI, run a search that returns a non-root item (e.g. search a name).
In **list mode** click a hit to reveal it, then right-click it in the tree →
"Show and copy full path". Confirm it copies a `/root/...` path and shows the
success toast (no "No path was returned by the server" error). Repeat with
**Embed results in tree** checked.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/js/search.js
git commit -m "fix(phase24): retain path on search-result nodes so copy-path works

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 5: Controller call-timing logging via a HandlerInterceptor

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/api/filter/RequestTimingInterceptor.java`
- Test: `src/test/java/com/myxcomp/ice/xtree/api/filter/RequestTimingInterceptorTest.java`
- Modify: `src/main/java/com/myxcomp/ice/xtree/api/filter/WebMvcConfig.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/myxcomp/ice/xtree/api/filter/RequestTimingInterceptorTest.java`:

```java
package com.myxcomp.ice.xtree.api.filter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class RequestTimingInterceptorTest {

    private static final String START_NS = "com.myxcomp.ice.xtree.api.filter.RequestTimingInterceptor.START_NS";

    private final RequestTimingInterceptor interceptor = new RequestTimingInterceptor();

    @Test
    void preHandleStashesStartTimeAndProceeds() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/itemtree/tree");
        HttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = interceptor.preHandle(request, response, new Object());

        assertThat(proceed).isTrue();
        assertThat(request.getAttribute(START_NS)).isInstanceOf(Long.class);
    }

    @Test
    void afterCompletionWithStartAttributeLogsWithoutError() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/itemtree/tree");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);
        interceptor.preHandle(request, response, new Object());

        assertThatCode(() ->
                interceptor.afterCompletion(request, response, new Object(), null))
                .doesNotThrowAnyException();
    }

    @Test
    void afterCompletionWithoutStartAttributeDoesNotThrow() {
        // Defensive: preHandle may not have run (e.g. an earlier interceptor returned false).
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/itemtree/tree");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatCode(() ->
                interceptor.afterCompletion(request, response, new Object(), null))
                .doesNotThrowAnyException();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'com.myxcomp.ice.xtree.api.filter.RequestTimingInterceptorTest'`
Expected: FAIL — compilation error, `RequestTimingInterceptor` does not exist.

- [ ] **Step 3: Create the interceptor**

Create `src/main/java/com/myxcomp/ice/xtree/api/filter/RequestTimingInterceptor.java`:

```java
package com.myxcomp.ice.xtree.api.filter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.concurrent.TimeUnit;

/**
 * Logs one INFO line per request with the HTTP method, URI, response status, and
 * end-to-end duration in milliseconds. Registered outermost in {@link WebMvcConfig}
 * so the measured span wraps the whole handler chain. Uses {@link System#nanoTime()}
 * (a monotonic duration source, not a wall-clock API) so the {@code TimeMapper}
 * UTC-clock rule does not apply.
 */
public class RequestTimingInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RequestTimingInterceptor.class);
    static final String START_NS = RequestTimingInterceptor.class.getName() + ".START_NS";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(START_NS, System.nanoTime());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        Object start = request.getAttribute(START_NS);
        if (!(start instanceof Long startNs)) {
            return;
        }
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
        log.info("{} {} -> {} ({} ms)",
                request.getMethod(), request.getRequestURI(), response.getStatus(), ms);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests 'com.myxcomp.ice.xtree.api.filter.RequestTimingInterceptorTest'`
Expected: PASS (3 tests).

- [ ] **Step 5: Register the interceptor in `WebMvcConfig`**

In `src/main/java/com/myxcomp/ice/xtree/api/filter/WebMvcConfig.java`, change
`addInterceptors` so the timing interceptor is added **first** (outermost),
sharing the same path pattern:

```java
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RequestTimingInterceptor())
                .addPathPatterns("/api/v1/itemtree/**");
        registry.addInterceptor(new UserContextInterceptor())
                .addPathPatterns("/api/v1/itemtree/**");
    }
```

- [ ] **Step 6: Run the full suite**

Run: `./gradlew test`
Expected: PASS — all existing tests plus the 3 new ones (green, 0 failures).

- [ ] **Step 7: Manual verify (optional but recommended)**

Run `./gradlew bootRun`, exercise the UI (login/search), and confirm the
application log shows lines like:
`GET /api/v1/itemtree/tree -> 200 (12 ms)`

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/api/filter/RequestTimingInterceptor.java src/test/java/com/myxcomp/ice/xtree/api/filter/RequestTimingInterceptorTest.java src/main/java/com/myxcomp/ice/xtree/api/filter/WebMvcConfig.java
git commit -m "feat(phase24): log per-request method, URI, status, and duration

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Wrap-up

- [ ] **Update `IMPLEMENTATION_NOTES.md`** with a short Phase 24 entry (what changed, why the search-path fix was UI-only).
- [ ] **Run the full suite once more** (`./gradlew test`) and confirm green.
- [ ] **Update memory:** add a Phase 24 done entry to `MEMORY.md` once merged.

---

## Self-review notes

- **Spec coverage:** §1 Backend textbox → Task 1; §2 Probe button → Task 2; §3 UDFRepo name hide → Task 3; §4 search path → Task 4; §5 timing logging → Task 5. All spec sections mapped.
- **Search-path backend test:** the spec listed a possible new backend contract test; it already exists (`SearchServiceTest.searchByNamePopulatesPath` / `searchByIdPopulatesPath`), so no new backend test is added — noted in Task 4 rather than left as a gap.
- **Type consistency:** `START_NS` constant value in the test matches the fully-qualified `RequestTimingInterceptor.class.getName() + ".START_NS"`; `UDF_REPO` constant value `'UDFRepo'` matches `KNOWN_TYPES` and the backend `Types.isUdfRepo` literal.
- **No placeholders:** every code step shows the exact before/after content.

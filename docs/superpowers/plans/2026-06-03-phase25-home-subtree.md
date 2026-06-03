# Phase 25 — Home-subtree endpoint + Login/Refresh button — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Collapse the test UI's three-call login (`/home-folder` + `/tree` + `/subtree-full`) into two parallel calls by introducing `GET /users/{userName}/home-subtree`, and rename the `Login` button to `Login/Refresh`. No visible UI behavioural change.

**Architecture:** A new REST endpoint backed by glue in `UserController` (composes the existing `HomeFolderService.findHomeFolder` and `TreeService.getSubtreeFull` — no new service method). The test UI replaces the serial pair `home-folder → subtree-full` with the new single call, running it in parallel with `getTree`. Derives the home folder id from the flat subtree by finding the unique element whose `parentId` is not present as any other element's `itemTreeId`.

**Tech Stack:** Java 21, Spring Boot, Spring MVC, `JdbcClient`, openapi-generator (`com.myxcomp.ice.xtree.generated`), JUnit 5 + Mockito + AssertJ, `@WebMvcTest`, `@SpringBootTest` against H2 in Oracle compatibility mode, static JS test UI (no build).

**Reference spec:** `docs/superpowers/specs/2026-06-03-phase25-home-subtree-design.md`. Read it before starting.

**Branch:** Work directly on `main`. The user has explicitly asked to stay on the current branch. Commit per task.

**Tooling reminder (from `CLAUDE.md`):**
- The `Edit` tool requires a prior `Read` of the same file. Always `Read` before `Edit`. `cat`/`tail`/`sed` does *not* satisfy this.
- Do not batch many independent tool calls in one turn. Issue one load-bearing call, wait for its result, then proceed.

---

## File Structure

**Modified files:**

- `src/main/resources/openapi/itemtree-api.yaml` — add `getHomeSubtree` operation under the `users` tag (sibling of `getHomeFolder`).
- `src/main/java/com/myxcomp/ice/xtree/api/controller/UserController.java` — implement the new generated method; inject `TreeService` in addition to `HomeFolderService`, `PathResolver`, `ItemNodeMapper`.
- `src/test/java/com/myxcomp/ice/xtree/api/controller/UserControllerTest.java` — add `@MockitoBean TreeService` and two cases (200 happy path, 404 unknown user).
- `src/test/java/com/myxcomp/ice/xtree/e2e/PathOnReadEndpointsE2ETest.java` — extend with one happy-path scenario for the new endpoint, reusing the existing seed (`testuser1`, home folder id `10`).
- `src/main/resources/static/index.html` — rename button text.
- `src/main/resources/static/js/api.js` — add `getHomeSubtree`.
- `src/main/resources/static/js/app.js` — rewrite `doLogin`.
- `IMPLEMENTATION_NOTES.md` — append a short Phase 25 completion note.

**New files:** none.

Each task below is self-contained, follows TDD where applicable, and ends in a commit.

---

## Task 1: Add `getHomeSubtree` to the OpenAPI spec

**Files:**
- Modify: `src/main/resources/openapi/itemtree-api.yaml` (add new path immediately after the existing `/users/{userName}/home-folder` block, around line 372)

- [ ] **Step 1: Read the existing `home-folder` operation as the template**

Run: `Read src/main/resources/openapi/itemtree-api.yaml`

Find the block starting at the line containing `/api/v1/itemtree/users/{userName}/home-folder:` (around line 344). Confirm the surrounding context (the `users` tag is used, the response schema is `ItemNode`, `NotFound`/`ServiceUnavailable` responses are shared `$ref` components).

- [ ] **Step 2: Insert the new operation immediately after the `home-folder` block**

Use `Edit`. The `old_string` is the closing `503` block of `home-folder` plus the trailing blank line and the `components:` line:

```yaml
        '503':
          $ref: '#/components/responses/ServiceUnavailable'

components:
```

The `new_string` adds the new path before `components:`:

```yaml
        '503':
          $ref: '#/components/responses/ServiceUnavailable'

  /api/v1/itemtree/users/{userName}/home-subtree:
    get:
      tags: [users]
      operationId: getHomeSubtree
      summary: Resolve the home folder for the given user and return its full subtree
      description: |
        Equivalent to calling getHomeFolder followed by
        getSubtreeFull on the resolved home folder, in a single round trip.
        The home folder itself is the unique element in the returned array
        whose parentId is not the itemTreeId of any other element in the array.
      parameters:
        - name: userName
          in: path
          required: true
          schema:
            type: string
        - $ref: '#/components/parameters/XIceUser'
        - $ref: '#/components/parameters/XImpersonatedUser'
      responses:
        '200':
          description: Flat list of ItemNode for the home folder's full subtree, each with path populated
          content:
            application/json:
              schema:
                type: array
                items:
                  $ref: '#/components/schemas/ItemNode'
        '404':
          description: Home folder not found
          content:
            application/problem+json:
              schema:
                $ref: '#/components/schemas/Problem'
        '503':
          $ref: '#/components/responses/ServiceUnavailable'

components:
```

- [ ] **Step 3: Verify the spec generates by running a compile**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL. The openapi-generator runs as part of `compileJava` and produces a new `UsersApi.getHomeSubtree(String, String, String)` default method. `UserController` does not yet override it — that's fine, the default returns `501 Not Implemented`. The compile must still succeed.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/openapi/itemtree-api.yaml
git commit -m "feat(phase25): add getHomeSubtree OpenAPI operation

Adds GET /users/{userName}/home-subtree returning a flat ItemNode[]
for the user's home folder and its full subtree. Controller wiring
follows in the next commit."
```

---

## Task 2: Test-drive the controller method (200 happy path)

**Files:**
- Modify: `src/test/java/com/myxcomp/ice/xtree/api/controller/UserControllerTest.java`
- Modify (later in this task): `src/main/java/com/myxcomp/ice/xtree/api/controller/UserController.java`

- [ ] **Step 1: Read the test file**

Run: `Read src/test/java/com/myxcomp/ice/xtree/api/controller/UserControllerTest.java`

Note the existing imports, the `@WebMvcTest(UserController.class)` annotation, the `@Import({GlobalExceptionHandler.class, ProblemFactory.class, ItemNodeMapper.class, TimeMapper.class})` list, and the existing `@MockitoBean` fields for `HomeFolderService`, `PathResolver`, `CacheReadinessGate`, `SecurityProperties`.

- [ ] **Step 2: Add `TreeService` import and `@MockitoBean` field**

Use `Edit`. The `old_string` is the existing imports block (or specifically a unique import line followed by another). Replace by adding two imports near the existing `service.*` imports:

Add to the imports (after `import com.myxcomp.ice.xtree.service.PathResolver;`):

```java
import com.myxcomp.ice.xtree.service.TreeNodeView;
import com.myxcomp.ice.xtree.service.TreeService;
```

Add `java.util.List` import if not already present.

Add a `@MockitoBean` field next to the existing ones (find the line `@MockitoBean PathResolver pathResolver;`):

```java
@MockitoBean PathResolver pathResolver;
@MockitoBean TreeService treeService;
```

- [ ] **Step 3: Add the failing 200 test**

Append before the closing `}` of the class:

```java
    @Test
    void getHomeSubtreeReturns200AndFlatArrayWithPaths() throws Exception {
        CachedNode home = new CachedNode(
                42L, 2L, "alice", "Folder", Instant.parse("2026-05-16T12:00:00Z"), "sys");
        CachedNode child = new CachedNode(
                43L, 42L, "Notes", "Folder", Instant.parse("2026-05-16T12:00:00Z"), "sys");
        when(homeFolderService.findHomeFolder("alice")).thenReturn(home);
        when(treeService.getSubtreeFull(42L)).thenReturn(List.of(
                new TreeNodeView(home,  "/root/Users/alice"),
                new TreeNodeView(child, "/root/Users/alice/Notes")
        ));

        mvc.perform(get("/api/v1/itemtree/users/alice/home-subtree")
                        .header("X-Ice-User", "caller"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].itemTreeId").value(42))
                .andExpect(jsonPath("$[0].parentId").value(2))
                .andExpect(jsonPath("$[0].name").value("alice"))
                .andExpect(jsonPath("$[0].path").value("/root/Users/alice"))
                .andExpect(jsonPath("$[1].itemTreeId").value(43))
                .andExpect(jsonPath("$[1].parentId").value(42))
                .andExpect(jsonPath("$[1].path").value("/root/Users/alice/Notes"));
    }
```

- [ ] **Step 4: Run the test and confirm it fails**

Run: `./gradlew test --tests com.myxcomp.ice.xtree.api.controller.UserControllerTest.getHomeSubtreeReturns200AndFlatArrayWithPaths`
Expected: FAIL. The generated `UsersApi.getHomeSubtree` default method returns `501 Not Implemented`, so the assertion `status().isOk()` fails.

- [ ] **Step 5: Read the controller**

Run: `Read src/main/java/com/myxcomp/ice/xtree/api/controller/UserController.java`

- [ ] **Step 6: Add `TreeService` constructor dependency and implement `getHomeSubtree`**

Use `Edit` to add the import:

```java
import com.myxcomp.ice.xtree.service.TreeService;
```

(Place near `HomeFolderService`/`PathResolver` imports.)

Use `Edit` to extend the field list. `old_string`:

```java
    private final HomeFolderService homeFolderService;
    private final PathResolver pathResolver;
    private final ItemNodeMapper itemNodeMapper;

    public UserController(HomeFolderService homeFolderService,
                          PathResolver pathResolver,
                          ItemNodeMapper itemNodeMapper) {
        this.homeFolderService = homeFolderService;
        this.pathResolver = pathResolver;
        this.itemNodeMapper = itemNodeMapper;
    }
```

`new_string`:

```java
    private final HomeFolderService homeFolderService;
    private final PathResolver pathResolver;
    private final TreeService treeService;
    private final ItemNodeMapper itemNodeMapper;

    public UserController(HomeFolderService homeFolderService,
                          PathResolver pathResolver,
                          TreeService treeService,
                          ItemNodeMapper itemNodeMapper) {
        this.homeFolderService = homeFolderService;
        this.pathResolver = pathResolver;
        this.treeService = treeService;
        this.itemNodeMapper = itemNodeMapper;
    }
```

Use `Edit` to add `getHomeSubtree` immediately after the existing `getHomeFolder` method. First add imports near the top if needed:

```java
import com.myxcomp.ice.xtree.service.TreeNodeView;
import java.util.List;
```

Then append the method. `old_string` is the closing brace of `getHomeFolder` plus the class closing brace — pick a unique block:

```java
        return ResponseEntity.ok(itemNodeMapper.toDto(new TreeNodeView(folder, path)));
    }
}
```

`new_string`:

```java
        return ResponseEntity.ok(itemNodeMapper.toDto(new TreeNodeView(folder, path)));
    }

    @Override
    public ResponseEntity<List<ItemNode>> getHomeSubtree(String userName, String xIceUser, String xImpersonatedUser) {
        CachedNode home = homeFolderService.findHomeFolder(userName);
        List<TreeNodeView> views = treeService.getSubtreeFull(home.itemTreeId());
        return ResponseEntity.ok(itemNodeMapper.toDtos(views));
    }
}
```

- [ ] **Step 7: Run the test and confirm it passes**

Run: `./gradlew test --tests com.myxcomp.ice.xtree.api.controller.UserControllerTest.getHomeSubtreeReturns200AndFlatArrayWithPaths`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/api/controller/UserController.java \
        src/test/java/com/myxcomp/ice/xtree/api/controller/UserControllerTest.java
git commit -m "feat(phase25): implement /users/{userName}/home-subtree controller

Glue endpoint composing HomeFolderService.findHomeFolder and
TreeService.getSubtreeFull. Adds @WebMvcTest covering the
200 happy path with two-element subtree and path population."
```

---

## Task 3: Add the 404 controller test

**Files:**
- Modify: `src/test/java/com/myxcomp/ice/xtree/api/controller/UserControllerTest.java`

- [ ] **Step 1: Read the test file**

Run: `Read src/test/java/com/myxcomp/ice/xtree/api/controller/UserControllerTest.java`

- [ ] **Step 2: Add the 404 test**

Use `Edit`. Append before the class closing brace:

```java
    @Test
    void getHomeSubtreeReturns404WhenUserHasNoHomeFolder() throws Exception {
        when(homeFolderService.findHomeFolder("ghost"))
                .thenThrow(new NotFoundException(
                        ErrorCode.HOME_FOLDER_NOT_FOUND,
                        "No home folder for user 'ghost'"));

        mvc.perform(get("/api/v1/itemtree/users/ghost/home-subtree")
                        .header("X-Ice-User", "caller"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("HOME_FOLDER_NOT_FOUND"));

        org.mockito.Mockito.verifyNoInteractions(treeService);
    }
```

- [ ] **Step 3: Run the test**

Run: `./gradlew test --tests com.myxcomp.ice.xtree.api.controller.UserControllerTest.getHomeSubtreeReturns404WhenUserHasNoHomeFolder`
Expected: PASS. The existing `GlobalExceptionHandler` already maps `NotFoundException(HOME_FOLDER_NOT_FOUND)` to a 404 problem-detail with `errorCode: HOME_FOLDER_NOT_FOUND`. `TreeService` is not called because the exception is thrown before it would be invoked.

- [ ] **Step 4: Run the whole `UserControllerTest` class to confirm no regressions**

Run: `./gradlew test --tests com.myxcomp.ice.xtree.api.controller.UserControllerTest`
Expected: PASS (5 tests total: 3 existing + 2 new).

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/myxcomp/ice/xtree/api/controller/UserControllerTest.java
git commit -m "test(phase25): cover home-subtree 404 path

When HomeFolderService throws HOME_FOLDER_NOT_FOUND, the controller
must surface the same RFC 7807 body as the existing home-folder
endpoint, and must not invoke TreeService."
```

---

## Task 4: E2E happy path against seeded H2

**Files:**
- Modify: `src/test/java/com/myxcomp/ice/xtree/e2e/PathOnReadEndpointsE2ETest.java`

The seed data (`data.sql`, used by the `dev` profile and by `@SpringBootTest` here) has `id=10, name="testuser1", parentId=2 (Users), type=Folder`. `testuser1` has children in the seed (verifiable by inspecting the seeded fixtures already used by `PathOnReadEndpointsE2ETest`); the home subtree therefore contains at least one element.

- [ ] **Step 1: Read the test file**

Run: `Read src/test/java/com/myxcomp/ice/xtree/e2e/PathOnReadEndpointsE2ETest.java`

- [ ] **Step 2: Add a URL constant and a new test**

Use `Edit`. Add the constant next to the existing ones:

`old_string`:

```java
    private static final String SEARCH_URL    = "/api/v1/itemtree/search";
    private static final String ITEMS_GET_URL = "/api/v1/itemtree/items/get";
    private static final String HEADER_USER   = "X-Ice-User";
```

`new_string`:

```java
    private static final String SEARCH_URL        = "/api/v1/itemtree/search";
    private static final String ITEMS_GET_URL    = "/api/v1/itemtree/items/get";
    private static final String HOME_SUBTREE_URL = "/api/v1/itemtree/users/testuser1/home-subtree";
    private static final String HEADER_USER      = "X-Ice-User";
```

Append before the class closing brace:

```java
    @Test
    void homeSubtreeReturnsHomeFolderAndDescendantsWithPaths() throws Exception {
        // Seed: id=10, name="testuser1", parentId=2 (Users → root). Home folder
        // exists and has at least itself plus seeded descendants in the array.
        mockMvc.perform(get(HOME_SUBTREE_URL)
                        .header(HEADER_USER, "testuser1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0]").exists())
                // The home folder itself (id=10) must appear, with leading-slash path.
                .andExpect(jsonPath(
                        "$[?(@.itemTreeId == 10)].name").value(org.hamcrest.Matchers.hasItem("testuser1")))
                .andExpect(jsonPath(
                        "$[?(@.itemTreeId == 10)].path").value(
                                org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.startsWith("/root"))))
                // Its parentId (=2 "Users") must NOT itself appear as any element's itemTreeId
                // — that is the invariant the UI relies on to derive the home folder.
                .andExpect(jsonPath("$[?(@.itemTreeId == 2)]").value(org.hamcrest.Matchers.empty()));
    }

    @Test
    void homeSubtreeReturns404ForUnknownUser() throws Exception {
        mockMvc.perform(get("/api/v1/itemtree/users/no-such-user-xyz/home-subtree")
                        .header(HEADER_USER, "testuser1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("HOME_FOLDER_NOT_FOUND"));
    }
```

- [ ] **Step 3: Run the E2E test**

Run: `./gradlew test --tests com.myxcomp.ice.xtree.e2e.PathOnReadEndpointsE2ETest`
Expected: PASS (4 tests total: 2 existing + 2 new). If the home-folder-id-2-absence check fails, inspect the seed — the home folder's parent ("Users", id=2) must not be part of the home subtree by definition; this assertion is the load-bearing one for the UI invariant.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/myxcomp/ice/xtree/e2e/PathOnReadEndpointsE2ETest.java
git commit -m "test(phase25): e2e coverage for /users/{userName}/home-subtree

Asserts the seed user's home folder appears with a leading-slash path,
and that its parent ('Users', id=2) is not present in the returned
subtree — the invariant the test UI relies on to derive the home id."
```

---

## Task 5: Rename the test UI button to `Login/Refresh`

**Files:**
- Modify: `src/main/resources/static/index.html`

- [ ] **Step 1: Read the file**

Run: `Read src/main/resources/static/index.html`

- [ ] **Step 2: Rename the button text**

Use `Edit`. `old_string`:

```html
<button id="login-btn" type="button">Login</button>
```

`new_string`:

```html
<button id="login-btn" type="button">Login/Refresh</button>
```

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/index.html
git commit -m "feat(phase25): rename test UI button to Login/Refresh

Reflects that the same button already serves both the initial login
and subsequent refreshes."
```

---

## Task 6: Add `api.getHomeSubtree` to the test UI client

**Files:**
- Modify: `src/main/resources/static/js/api.js`

- [ ] **Step 1: Read the file**

Run: `Read src/main/resources/static/js/api.js`

- [ ] **Step 2: Add the new method to the `api` object**

Use `Edit`. `old_string`:

```javascript
  getHomeFolder: (userName) =>
    request('GET', `/api/v1/itemtree/users/${encodeURIComponent(userName)}/home-folder`),
  getTree: () =>
    request('GET', '/api/v1/itemtree/tree', undefined, { retryOn503: true }),
```

`new_string`:

```javascript
  getHomeFolder: (userName) =>
    request('GET', `/api/v1/itemtree/users/${encodeURIComponent(userName)}/home-folder`),
  getHomeSubtree: (userName) =>
    request('GET', `/api/v1/itemtree/users/${encodeURIComponent(userName)}/home-subtree`,
            undefined, { retryOn503: true }),
  getTree: () =>
    request('GET', '/api/v1/itemtree/tree', undefined, { retryOn503: true }),
```

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/js/api.js
git commit -m "feat(phase25): add api.getHomeSubtree client method

retryOn503 mirrors getTree so both halves of the parallel login
fan-out behave consistently during cache warm-up."
```

---

## Task 7: Rewrite `doLogin` to use the parallel pair

**Files:**
- Modify: `src/main/resources/static/js/app.js`

- [ ] **Step 1: Read the file**

Run: `Read src/main/resources/static/js/app.js`

- [ ] **Step 2: Replace the body of `doLogin`**

Use `Edit`. `old_string` is the full current `doLogin` function:

```javascript
async function doLogin() {
  if (!state.iceUser) {
    toastError({ title: 'X-Ice-User required', detail: 'Type a user name first.' });
    return;
  }
  resetTreeState();
  renderTree();
  $('detail-root').innerHTML = '(loading…)';
  try {
    const [home, tree] = await Promise.all([
      api.getHomeFolder(state.iceUser),
      api.getTree(),
    ]);
    state.homeFolderId = home.itemTreeId;
    ingestNodes(tree);

    // expand only the ancestor chain from root down to the home folder
    state.tree.expanded.add(1);
    let cur = state.tree.nodesById.get(home.itemTreeId);
    while (cur && cur.parentId && cur.parentId !== 0) {
      state.tree.expanded.add(cur.parentId);
      cur = state.tree.nodesById.get(cur.parentId);
    }

    const subtree = await api.getSubtreeFull(home.itemTreeId);
    ingestSubtreeFullResult(home.itemTreeId, subtree);

    // expand the home folder itself and any folders directly within its loaded subtree
    state.tree.expanded.add(home.itemTreeId);
    for (const n of subtree) {
      if (n.type === 'Folder') state.tree.expanded.add(n.itemTreeId);
    }

    renderTree();
    $('detail-root').innerHTML = '(logged in as <b></b>; click a node)';
    $('detail-root').querySelector('b').textContent = state.iceUser;
  } catch (e) {
    if (e instanceof ProblemError) toastError(e.problem); else toastError(String(e));
    $('detail-root').innerHTML = '(login failed — see toast)';
  }
}
```

`new_string`:

```javascript
async function doLogin() {
  if (!state.iceUser) {
    toastError({ title: 'X-Ice-User required', detail: 'Type a user name first.' });
    return;
  }
  resetTreeState();
  renderTree();
  $('detail-root').innerHTML = '(loading…)';
  try {
    const [tree, subtree] = await Promise.all([
      api.getTree(),
      api.getHomeSubtree(state.iceUser),
    ]);
    ingestNodes(tree);

    // Derive the home folder from the flat subtree: it is the unique element
    // whose parentId is not the itemTreeId of any other element in the array.
    const idsInSubtree = new Set(subtree.map((n) => n.itemTreeId));
    const home = subtree.find((n) => !idsInSubtree.has(n.parentId));
    state.homeFolderId = home.itemTreeId;

    // expand only the ancestor chain from root down to the home folder
    state.tree.expanded.add(1);
    let cur = state.tree.nodesById.get(home.itemTreeId);
    while (cur && cur.parentId && cur.parentId !== 0) {
      state.tree.expanded.add(cur.parentId);
      cur = state.tree.nodesById.get(cur.parentId);
    }

    ingestSubtreeFullResult(home.itemTreeId, subtree);

    // expand the home folder itself and any folders directly within its loaded subtree
    state.tree.expanded.add(home.itemTreeId);
    for (const n of subtree) {
      if (n.type === 'Folder') state.tree.expanded.add(n.itemTreeId);
    }

    renderTree();
    $('detail-root').innerHTML = '(logged in as <b></b>; click a node)';
    $('detail-root').querySelector('b').textContent = state.iceUser;
  } catch (e) {
    if (e instanceof ProblemError) toastError(e.problem); else toastError(String(e));
    $('detail-root').innerHTML = '(login failed — see toast)';
  }
}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/js/app.js
git commit -m "feat(phase25): doLogin uses parallel getTree + getHomeSubtree

Replaces the three-call sequence (home-folder, tree in parallel,
then subtree-full) with a two-call parallel fan-out. The home folder
id is derived from the flat subtree as the unique element whose
parentId is not the itemTreeId of any other element. Visible
behaviour is unchanged; one fewer round trip."
```

---

## Task 8: Manual UI verification via `bootRun`

Phase A test UI has no automated coverage. This step is required and the engineer must perform it themselves.

- [ ] **Step 1: Start the app**

Run (in a separate terminal): `./gradlew bootRun`
Wait for `Started ItemTreeApplication`.

- [ ] **Step 2: Open the UI and verify the button label**

Open `http://localhost:8080/` in a browser. Confirm the header button reads `Login/Refresh` (with slash, no spaces).

- [ ] **Step 3: Verify the login flow against `testuser1`**

In the `X-Ice-User` field type `testuser1` (it may already be pre-filled). Click `Login/Refresh`. Confirm:
- The tree renders.
- `testuser1` is marked with the ★.
- The chain from root (`root` → `Users` → `testuser1`) is expanded.
- The detail pane shows `(logged in as testuser1; click a node)`.

- [ ] **Step 4: Verify the parallel fan-out in DevTools**

Open the browser's DevTools Network panel and click `Login/Refresh` again. Confirm:
- Exactly **two** XHR requests fire for the login: one to `…/tree`, one to `…/users/testuser1/home-subtree`.
- The previous third request to `…/tree/{id}/subtree-full` is gone.

- [ ] **Step 5: Verify the 404 path**

Change `X-Ice-User` to `no-such-user-xyz` and click `Login/Refresh`. Confirm:
- A toast appears with `HOME_FOLDER_NOT_FOUND` (or its rendered title).
- The detail pane shows `(login failed — see toast)`.

- [ ] **Step 6: Stop the app**

`Ctrl+C` in the `bootRun` terminal.

- [ ] **Step 7: No commit** — this step is verification only.

If any check fails, fix the offending task before continuing. Do **not** claim Phase 25 complete with a failing manual step.

---

## Task 9: Full regression test pass

- [ ] **Step 1: Run the whole suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL. Test count goes up by exactly 4 (2 in `UserControllerTest`, 2 in `PathOnReadEndpointsE2ETest`). Confirm no prior tests fail.

- [ ] **Step 2: If anything fails — stop and diagnose**

Per `CLAUDE.md`: never `@Disabled` or comment out a failing test. Fix the offender (likely the controller or a brittle assertion in a sibling test), re-run, and confirm green before continuing.

- [ ] **Step 3: No commit** — this step is verification only.

---

## Task 10: Update `IMPLEMENTATION_NOTES.md`

**Files:**
- Modify: `IMPLEMENTATION_NOTES.md`

- [ ] **Step 1: Read the file**

Run: `Read IMPLEMENTATION_NOTES.md`
Find the end of the file (or the last phase section, currently Phase 24).

- [ ] **Step 2: Append the Phase 25 section**

Use `Edit`. `old_string` is the last paragraph of the existing Phase 24 section (whichever block ends the file). Append after it. If the file ends with the "Suggested first session prompt" / closing block, insert the new section *before* that closing block so the closing block stays last.

Section to insert:

```markdown
## Phase 25 — Combined home-subtree endpoint + Login/Refresh button ✅ COMPLETE (2026-06-03)

**Scope:**
- New endpoint `GET /api/v1/itemtree/users/{userName}/home-subtree` returning
  the flat `ItemNode[]` subtree of the user's home folder. Glue in
  `UserController` composes `HomeFolderService.findHomeFolder` and
  `TreeService.getSubtreeFull`; no new service method.
- Test UI: `Login` button renamed to `Login/Refresh`. `doLogin` replaces the
  three-call sequence (`home-folder` + `tree` in parallel, then sequential
  `subtree-full`) with a two-call parallel fan-out (`tree` + `home-subtree`).
  Derives the home folder id from the flat subtree as the unique element whose
  `parentId` is not the `itemTreeId` of any other element. No visible UI
  behavioural change.

**Tests added:**
- `UserControllerTest.getHomeSubtreeReturns200AndFlatArrayWithPaths`
- `UserControllerTest.getHomeSubtreeReturns404WhenUserHasNoHomeFolder`
- `PathOnReadEndpointsE2ETest.homeSubtreeReturnsHomeFolderAndDescendantsWithPaths`
- `PathOnReadEndpointsE2ETest.homeSubtreeReturns404ForUnknownUser`

**Existing endpoints unchanged:** `/users/{userName}/home-folder` and
`/tree/{rootId}/subtree-full` remain available as primitives.

**Manual verification:** confirmed via `gradlew bootRun` — exactly two XHRs
on login (`/tree` and `/home-subtree`), ★ marker and expansion chain
identical to pre-Phase-25.
```

- [ ] **Step 3: Commit**

```bash
git add IMPLEMENTATION_NOTES.md
git commit -m "docs(phase25): record completion of home-subtree endpoint phase"
```

---

## Task 11: Final verification

- [ ] **Step 1: Confirm working tree is clean**

Run: `git status`
Expected: `nothing to commit, working tree clean`.

- [ ] **Step 2: Confirm commit log shows the phase work in order**

Run: `git log --oneline -10`
Expected: top of the log shows (in this order, top is newest):
1. `docs(phase25): record completion …`
2. `feat(phase25): doLogin uses parallel …`
3. `feat(phase25): add api.getHomeSubtree client method`
4. `feat(phase25): rename test UI button …`
5. `test(phase25): e2e coverage …`
6. `test(phase25): cover home-subtree 404 path`
7. `feat(phase25): implement /users/{userName}/home-subtree controller`
8. `feat(phase25): add getHomeSubtree OpenAPI operation`
9. `docs(phase25): add design spec …` (already on `main` before this plan started)

If commits are reordered or some commit fails to land cleanly, do not amend prior commits — make a follow-up commit.

- [ ] **Step 3: One last full-test pass**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL. Phase 25 is complete.

---

## Notes for the executing engineer

- This codebase uses `JdbcClient` only — no JPA. The new endpoint adds no SQL. It re-uses `TreeService.getSubtreeFull`, which already walks the in-memory cache.
- `CachedNode` is a record with the constructor `(long itemTreeId, long parentId, String name, String type, Instant lastUpdate, String lastUpdateUser)`. Use exactly this shape in the controller test.
- `TreeNodeView` is a record `(CachedNode node, String path)` from `com.myxcomp.ice.xtree.service`.
- `ItemNodeMapper.toDtos(List<TreeNodeView>)` already exists and is what `TreeController` uses for `/subtree-full`. Use it as-is.
- `GlobalExceptionHandler` already translates `NotFoundException(ErrorCode.HOME_FOLDER_NOT_FOUND, …)` into HTTP 404 with body `{"errorCode": "HOME_FOLDER_NOT_FOUND", …}`. The new endpoint inherits this behaviour for free.
- `retryOn503: true` on the UI client means the browser retries up to 5 times on 503 with a 2-second backoff (see `api.js`'s `request` function). 503 is only returned when the cache is not ready — a real 404 (`HOME_FOLDER_NOT_FOUND`) surfaces immediately without retry.
- The UI's home-id derivation `subtree.find((n) => !idsInSubtree.has(n.parentId))` returns `undefined` if the array is empty or malformed; the subsequent `home.itemTreeId` then throws, the surrounding `catch` toasts the error, and the user sees `(login failed — see toast)`. This is the desired behaviour — no extra guard needed (per CLAUDE.md: "Don't add error handling … for scenarios that can't happen with a well-formed backend").

# Phase 22 — Cucumber Acceptance Suite (design)

**Date:** 2026-05-29
**Branch:** `phase-22`
**Status:** Approved, pending implementation plan

---

## 1. Goal

A black-box BDD acceptance suite that exercises **all** ITEMTREE REST functionality
against a **running** instance (local or remote, hostname configurable). The suite
talks only over the exposed HTTP endpoints — it does not boot the app in-JVM and has
no compile dependency on the application code.

Steps are written **generically** so a single step backs many cases — e.g.
`When I create a {string} of type {word}` creates a Report, Filter, View, Shortcut,
etc. The headline scenario is the full mutation lifecycle of a newly-created item:
**create → verify → rename → move → copy → update-data → delete → verify-gone**.

Everything the suite does is sandboxed inside a dedicated, self-cleaning test folder
under one user's home folder, so it can be run repeatedly against the same instance
without leaving residue.

---

## 2. Module & build wiring

A new **standalone Gradle subproject** `:acceptance`.

- Added to `settings.gradle.kts` via `include("acceptance")`.
- Own `acceptance/build.gradle.kts`. **No dependency on the root app project.**
- **Not** part of `./gradlew test` — the 705 hermetic unit/IT tests stay isolated.
- Invoked explicitly:

  ```
  ./gradlew :acceptance:cucumber -Ditemtree.baseUrl=http://somehost:8080
  ```

- A custom `cucumber` task (JavaExec or a `Test` task running the JUnit Platform
  Cucumber engine). System properties are forwarded from the Gradle invocation.

### Dependencies (all Maven Central — Phase A public-repo rule holds)

- `io.cucumber:cucumber-java`
- `io.cucumber:cucumber-junit-platform-engine`
- `io.cucumber:cucumber-picocontainer` (scenario-scoped DI for the shared `World`)
- `org.junit.platform:junit-platform-suite` (suite launcher)
- `io.rest-assured:rest-assured`
- `org.assertj:assertj-core`
- `com.fasterxml.jackson.core:jackson-databind` (request/response (de)serialisation)

Java 21 toolchain, matching the root project.

---

## 3. Configuration

A `TestConfig` (plain singleton, read once) resolves, in precedence order:

| Setting          | Source                                                        | Default                 |
|------------------|---------------------------------------------------------------|-------------------------|
| Base URL         | `-Ditemtree.baseUrl` → env `ITEMTREE_BASE_URL`                | `http://localhost:8080` |
| User             | `-Ditemtree.user` → env `ITEMTREE_USER`                       | `crhonekd`              |
| Readiness wait   | `-Ditemtree.readinessTimeoutSeconds`                          | `60`                    |

All API paths are rooted at `/api/v1/itemtree`.

### Readiness gate

Before any scenario runs, a `@BeforeAll` (JUnit Platform `@BeforeSuite`-equivalent
via a Cucumber `@Before` guarded by a one-shot flag, or a static initialiser in the
`World` provider) polls `GET {baseUrl}/actuator/health/readiness` until the body
reports `UP`, bounded by `readinessTimeoutSeconds`. This avoids racing the cache
load — the `CacheReadinessFilter` returns 503 until the tree is in memory.

---

## 4. Identity & sandboxing

- Every request carries header `X-Ice-User: <configured user>` (default `crhonekd`).
  `X-Impersonated-User` is exercised only where a scenario explicitly needs it
  (not required for the core lifecycle).
- **Home-folder resolution:** `GET /users/{user}/home-folder` → the user's home
  folder `itemTreeId`. Resolved once and cached in the suite scope.
- **Suite test folder:** a folder named `acceptance-{epochMillis}` (unique per run)
  is created inside the home folder at suite start. Its id is the *sandbox root* for
  every scenario.
- **Sub-folders for move/copy:** scenarios that need a move/copy destination create
  their own sub-folders inside the sandbox root (via `Background`), never touching
  anything outside it.

### Cleanup (idempotent, failure-safe)

- **Per scenario:** a `@After` hook deletes ids the scenario registered as
  "created" in the `World` (best-effort; ignores already-deleted 404s).
- **Suite teardown:** the `acceptance-{epochMillis}` folder is deleted. Because
  `DELETE /items/{id}` cascades to all descendants for a folder, this removes any
  leftover items in one call. Teardown tolerates a 404 (already gone).

The suite therefore leaves the target instance exactly as it found it.

---

## 5. Shared state — the `World`

A pico-container-injected, scenario-scoped object holding:

- `ApiClient` — thin RestAssured wrapper that injects the base URI and `X-Ice-User`
  header on every request and exposes `post/get/delete` returning the raw
  `Response` for assertions.
- `sandboxRootId` — the suite test-folder id (shared across scenarios via a static
  holder initialised once).
- `lastResponse` — the most recent `Response`, for `Then the response status is …`.
- `currentItemId` / `currentParentId` — "it" in steps like `When I rename it …`.
- `created` — list of ids to clean up after the scenario.
- `named` — map of logical names → ids, so a scenario can refer back to earlier
  items ("move it into {string}").

Keeping HTTP concerns in `ApiClient` and conversation state in `World` keeps step
definitions thin and declarative.

---

## 6. Step definitions (generic & reusable)

Grouped by concern; all operate relative to the sandbox.

### `ItemSteps` (mutations)

- `Given a folder named {string}` — create a Folder in the sandbox root, register
  under `named`.
- `When I create a {word} named {string}` — POST `/items` with
  `{parentId: sandboxRoot, name, type}`. Stash id+parentId as "current".
- `When I create a {word} named {string} with data <docstring/JSON>` — same, with a
  `data` object (data-bearing types).
- `When I rename it to {string}` — POST `/items/{id}/rename`.
- `When I move it into {string}` — resolve destination from `named`, POST
  `/items/{id}/move`.
- `When I copy it into {string}` — POST `/items/{id}/copy` with `destinationFolderId`.
- `When I replace its data with <JSON>` — POST `/items/{id}/data`.
- `When I delete it` — DELETE `/items/{id}`.

### `ReadSteps` (queries & assertions)

- `When I fetch it` / `When I fetch items {ids}` — POST `/items/get`.
- `When I get the tree` / `When I get the subtree of {string}` /
  `When I get the full subtree of {string}`.
- `When I resolve the home folder for {string}`.
- `When I search for {string}` (optionally `with limit {int}`).
- `Then the item's name is {string}` / `… type is {word}` /
  `… path is {string}` / `… parent is {string}`.
- `Then it no longer exists` — `getItems([id])` returns it absent **and** the parent's
  subtree does not list it.
- `Then the response is empty` / `Then the results include {string}` /
  `Then the search hit has ancestors {list}`.

### `ErrorSteps` (RFC-7807)

- `Then the response status is {int}`.
- `Then the error code is {word}` — asserts the `errorCode` field of the
  `application/problem+json` body.

---

## 7. Feature files

### `item-lifecycle.feature`

`Background`: create a destination sub-folder `dest` and a `copies` folder in the
sandbox. A `Scenario Outline` drives the full lifecycle per type:

```
Scenario Outline: full lifecycle of a <type>
  When I create a <type> named "<name>"
  When I fetch it
  Then the item's type is <type>
  When I rename it to "<name>-renamed"
  When I fetch it
  Then the item's name is "<name>-renamed"
  When I move it into "dest"
  When I fetch it
  Then the item's parent is "dest"
  When I copy it into "copies"
  Then the response status is 201
  When I delete it
  Then it no longer exists

  Examples:
    | type     | name        |
    | Report   | acc-report  |
    | Filter   | acc-filter  |
    | View     | acc-view    |
    | Shortcut | acc-shortcut|
```

A separate outline (or a tagged subset) covers `update-data` for data-bearing types
only, since `types-without-data` (e.g. Shortcut) must reject `data` with 400.

### `tree-reads.feature`

- `/tree` returns the skeleton + the user's home-folder children including the
  sandbox folder.
- `/tree/{sandbox}/subtree` lists the sandbox + immediate children only.
- `/tree/{sandbox}/subtree-full` lists the full recursive sandbox subtree.
- `/users/crhonekd/home-folder` returns the home folder.
- `/items/get` returns created items with correct `path` (leading-slash, root-anchored)
  and, for folders, one level of `children`.

### `search.feature`

- Create a uniquely-named item; search by **name** → hit present with correct
  `path` and root-first `ancestors` chain.
- Search by **numeric id** → resolves the item.
- `limit` honoured.

### `errors.feature`

- `404 ITEM_NOT_FOUND` — fetch/rename a bogus id.
- `403 NOT_IN_USER_FOLDER` — attempt to create/move targeting a node outside the
  user's home subtree (e.g. root `1` or `Users` `2`).
- `400 TYPE_CANNOT_HAVE_DATA` — create a `Shortcut` with `data`.
- `400` validation — create with an empty name.
- Every error asserts the RFC-7807 `errorCode` extension field.

---

## 8. H2 seed change (local testing only)

`crhonekd` is **not** in `src/main/resources/db/data.sql`, and
`HomeFolderService.findHomeFolder` resolves by name from the cache and does **not**
create a missing folder. To let the suite run green against the **local dev**
instance, add one row to `data.sql`:

```sql
-- crhonekd home folder (acceptance-suite user; real instance already has it)
INSERT INTO ITEMTREE (...) VALUES
  (13, 2, 'crhonekd', 'Folder', NULL, 'system', TIMESTAMP '2026-05-01 10:00:00', NULL);
```

Id `13` is free (home folders occupy 10–12). This is the **only** change to main
application resources. The remote/real instance already provisions `crhonekd`, so no
prod-side change is implied.

---

## 9. Out of scope

- No changes to application Java code, controllers, services, or the OpenAPI spec.
- No messaging / multi-instance convergence checks (covered by `e2e/` IT suite).
- No metrics/actuator assertions beyond the readiness probe.
- No automated UI testing (consistent with Phase 15/21 precedent).
- The suite assumes the target instance is already deployed and reachable; it does
  not start or stop the app.

---

## 10. Definition of done

- `:acceptance` subproject builds and `./gradlew :acceptance:cucumber` runs green
  against a locally-running dev instance (`./gradlew bootRun`).
- All four feature files pass; the sandbox folder is created and fully removed each
  run (verified by re-running twice with no residue).
- Suite is hostname-configurable and defaults to `localhost:8080`.
- One-row `data.sql` addition for `crhonekd`; no other main-tree changes.
- `IMPLEMENTATION_NOTES.md` Phase 22 section + memory entry written.
```

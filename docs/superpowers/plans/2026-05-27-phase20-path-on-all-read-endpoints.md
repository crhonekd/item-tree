# Phase 20 — `path` on all read endpoints + leading-slash format + test UI ID/path copy

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Populate a `path` field on every item returned by a read endpoint (`/tree`, `/subtree`, `/subtree-full`, `/items/get`, `/search`, `/users/{u}/home-folder`), standardise the format on a leading slash (`/root/...`), and add `Copy ID` / `Show and copy full path` items to the test UI context menu.

**Architecture:** Re-affirms design §9 — lazy compute via existing `PathResolver`, not stored on `CachedNode`. This phase is additive: the resolver is plumbed into `SearchService`, `ItemService.getItemsWithData`, and `UserController`; format change is contained inside `DefaultPathResolver`. Mutation response bodies are untouched. Test UI consumes the server-provided `path` value.

**Tech Stack:** Java 21, Spring Boot, OpenAPI-generator, JUnit 5, Mockito, AssertJ, MockMvc, plain ES modules in static UI.

**Spec:** `docs/superpowers/specs/2026-05-27-phase20-path-on-all-read-endpoints-design.md`

**Build commands:**
- Full suite: `./gradlew test`
- Single class: `./gradlew test --tests com.myxcomp.ice.xtree.service.DefaultPathResolverTest`
- Single method: `./gradlew test --tests 'com.myxcomp.ice.xtree.service.DefaultPathResolverTest.knownIds'`
- Compile only: `./gradlew compileJava` (auto-regenerates DTOs via `tasks.compileJava.dependsOn(tasks.openApiGenerate)`)

---

## File map

| File | Action |
|---|---|
| `src/main/resources/openapi/itemtree-api.yaml` | Modify — add `path` to `ItemNodeWithData` + `SearchHit`; update `ItemNode.path` description |
| `src/main/java/com/myxcomp/ice/xtree/service/DefaultPathResolver.java` | Modify — leading-slash join |
| `src/test/java/com/myxcomp/ice/xtree/service/DefaultPathResolverTest.java` | Modify — expected strings re-anchored |
| `src/test/java/com/myxcomp/ice/xtree/service/TreeServiceTest.java` | Modify — expected strings re-anchored |
| `src/test/java/com/myxcomp/ice/xtree/service/TreeServiceSubtreeNotFoundTest.java` | Modify — expected strings re-anchored |
| `src/test/java/com/myxcomp/ice/xtree/api/controller/TreeControllerTest.java` | Modify — path JSON assertions re-anchored (if any) |
| `src/main/java/com/myxcomp/ice/xtree/service/SearchHitView.java` | Create — new record |
| `src/main/java/com/myxcomp/ice/xtree/service/SearchService.java` | Modify — returns `List<SearchHitView>` |
| `src/test/java/com/myxcomp/ice/xtree/service/SearchServiceTest.java` | Modify — adapt to new return shape; assert paths |
| `src/main/java/com/myxcomp/ice/xtree/api/mapper/SearchHitMapper.java` | Modify — input `SearchHitView`, sets `path` |
| `src/test/java/com/myxcomp/ice/xtree/api/mapper/SearchHitMapperTest.java` | Modify — path field |
| `src/main/java/com/myxcomp/ice/xtree/api/controller/SearchController.java` | Modify — passes `SearchHitView` to mapper |
| `src/test/java/com/myxcomp/ice/xtree/api/controller/SearchControllerTest.java` | Modify — assert `path` in JSON |
| `src/main/java/com/myxcomp/ice/xtree/service/ItemWithData.java` | Modify — add `path` field |
| `src/main/java/com/myxcomp/ice/xtree/service/ItemService.java` | Modify — compute paths in `getItemsWithData`, pass through `shape()` |
| `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceGetItemsWithDataTest.java` (or its actual file) | Modify — assert path on item + child |
| `src/main/java/com/myxcomp/ice/xtree/api/mapper/ItemNodeWithDataMapper.java` | Modify — set `path` on dto + recursive children |
| `src/test/java/com/myxcomp/ice/xtree/api/mapper/ItemNodeWithDataMapperTest.java` | Modify — assert path |
| `src/test/java/com/myxcomp/ice/xtree/api/controller/ItemControllerTest.java` | Modify — flip the `$[0].path doesNotExist` assertion + add positive assertions |
| `src/main/java/com/myxcomp/ice/xtree/api/controller/UserController.java` | Modify — inject `PathResolver`, build `TreeNodeView` |
| `src/test/java/com/myxcomp/ice/xtree/api/controller/UserControllerTest.java` | Modify — assert `path` in JSON |
| `src/main/resources/static/js/menu.js` | Modify — two new menu items + root branch |
| `itemtree-service-design.md` | Modify — §3 + §9 |
| `IMPLEMENTATION_NOTES.md` | Modify — Phase 20 entry |
| `/home/dave/.claude/projects/-home-dave-Git-item-tree/memory/project-phase20-path-everywhere-done.md` | Create — memory file |
| `/home/dave/.claude/projects/-home-dave-Git-item-tree/memory/MEMORY.md` | Modify — add index entry |

---

## Notes for the implementer

- This branch is already `phase-20`. **Do not** create a worktree or switch branch.
- Each task ends with a single commit. Run the full suite (`./gradlew test`) before every commit; no intermediate commit may be red.
- All assertions in this codebase use AssertJ. Prefer `isEqualTo("…")` for path strings (no semantic shortcut applies).
- All time at JDBC boundaries goes through `TimeMapper`. Not relevant to this phase but a reminder per CLAUDE.md invariant 1.
- Generated DTO classes live under `com.myxcomp.ice.xtree.generated.model.*`. Imports of generated types are confined to `api/mapper` (CLAUDE.md invariant 7) — keep it that way.

---

## Task 1 — OpenAPI spec: extend schemas, regenerate DTOs

**Files:**
- Modify: `src/main/resources/openapi/itemtree-api.yaml`

The two DTOs and the description on `ItemNode.path` need updating. `ItemNode` already has `path`; just relax its description. Add `path` to `ItemNodeWithData` and `SearchHit`. Field is nullable so it stays optional in the schema — populated by mappers on read endpoints, omitted on mutation responses.

- [ ] **Step 1: Edit `ItemNode.path` description**

In `src/main/resources/openapi/itemtree-api.yaml`, find the `ItemNode` schema and replace the existing `path` block:

```yaml
        path:
          type: string
          nullable: true
          description: "Root-anchored slash-separated path, e.g. root/Folder1/IceReport; populated only on /tree, /subtree, and /subtree-full responses"
```

with:

```yaml
        path:
          type: string
          nullable: true
          description: "Root-anchored slash-separated path starting with a leading slash, e.g. /root/Folder1/IceReport. Populated on all read endpoints; absent on mutation response bodies."
```

- [ ] **Step 2: Add `path` to `ItemNodeWithData`**

In the same file, find the `ItemNodeWithData` schema. Insert the `path` property just after the existing `lastUpdateUser` property (matching the placement in `ItemNode`):

```yaml
        lastUpdateUser:
          type: string
        path:
          type: string
          nullable: true
          description: "Root-anchored slash-separated path starting with a leading slash, e.g. /root/Folder1/IceReport. Populated on /items/get responses (including expanded children)."
        dataJson:
```

- [ ] **Step 3: Add `path` to `SearchHit`**

In the same file, replace the `SearchHit` schema:

```yaml
    SearchHit:
      type: object
      required: [itemTreeId, name, type]
      properties:
        itemTreeId:
          type: integer
          format: int64
        name:
          type: string
        type:
          type: string
```

with:

```yaml
    SearchHit:
      type: object
      required: [itemTreeId, name, type]
      properties:
        itemTreeId:
          type: integer
          format: int64
        name:
          type: string
        type:
          type: string
        path:
          type: string
          nullable: true
          description: "Root-anchored slash-separated path starting with a leading slash, e.g. /root/Folder1/IceReport."
```

- [ ] **Step 4: Regenerate DTOs and confirm compile**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL. Generated `ItemNodeWithData` now has `setPath/getPath`; `SearchHit` now has `setPath/getPath`; `ItemNode.path` description updated in Javadoc.

- [ ] **Step 5: Confirm full suite still green**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL. Mappers don't set the new field yet — nullable in schema, defaults to `null` on DTO, JSON serialisation omits it (or emits `null`), and existing tests do not assert its absence yet (we'll flip the one place that does in Task 4).

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/openapi/itemtree-api.yaml
git commit -m "$(cat <<'EOF'
feat(phase20): add path field to ItemNodeWithData and SearchHit DTOs

Updates the OpenAPI schema; ItemNode.path description relaxed to "all
read endpoints" and the new path format with leading slash documented.
Mappers populate the field in later commits.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2 — Leading-slash path format

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/service/DefaultPathResolver.java`
- Modify: `src/test/java/com/myxcomp/ice/xtree/service/DefaultPathResolverTest.java`
- Modify: `src/test/java/com/myxcomp/ice/xtree/service/TreeServiceTest.java`
- Modify: `src/test/java/com/myxcomp/ice/xtree/service/TreeServiceSubtreeNotFoundTest.java`
- Modify: `src/main/java/com/myxcomp/ice/xtree/service/PathResolver.java` (Javadoc only)

The current join is `accum + SEPARATOR + name`, which only prepends a slash between names. To produce `/root/.../leaf`, prepend the separator unconditionally — but **only** when the chain reaches the root (`parentId == ROOT_PARENT_ID`) and **not** when anchored by a memoised partial path that itself already starts with `/` (memoised entries already carry the leading slash from the same loop). The cleanest rule: always start `accum` with `"/"` when there is no anchor; never auto-prepend when an anchor exists (anchor already carries the slash). Degraded paths (missing-ancestor or cap-reached, where the walk did not reach root) remain slash-less so they keep their existing semantic of "this is a partial path".

That logic is realised by initialising the `StringBuilder` to either `anchorPath` (anchor present — it already has a leading slash from when it was memoised) or `""` followed by an unconditional first slash for the root step. Concretely: detect "walk reached root" and, when true, treat the loop entry-zero append as the slash-bearing root name.

The minimal patch: when the walk terminated because `parentId == ROOT_PARENT_ID` (i.e. the leaf-first chain contains the root at its tail), the `for (int i = size-1; i >= 0; i--)` loop must prepend `/` before the very first name (the root). Use a boolean flag set in the parent walk and consume it once in the join.

- [ ] **Step 1: Add the failing test (root path starts with slash)**

In `src/test/java/com/myxcomp/ice/xtree/service/DefaultPathResolverTest.java`, find the existing test class for known-ids. Locate the `Arguments.of(1L, "root")` line (around line 57). Replace the four argument lines:

```java
                    Arguments.of(1L,   "root"),
                    Arguments.of(2L,   "root/Users"),
                    Arguments.of(10L,  "root/Users/testuser1"),
                    Arguments.of(210L, "root/Users/deepuser/L2/L3/L4/DeepReport")
```

with:

```java
                    Arguments.of(1L,   "/root"),
                    Arguments.of(2L,   "/root/Users"),
                    Arguments.of(10L,  "/root/Users/testuser1"),
                    Arguments.of(210L, "/root/Users/deepuser/L2/L3/L4/DeepReport")
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `./gradlew test --tests com.myxcomp.ice.xtree.service.DefaultPathResolverTest`
Expected: FAIL — every parameterised assertion fails with `expected "/root" but was "root"` (and so on).

- [ ] **Step 3: Update `DefaultPathResolver` join logic**

In `src/main/java/com/myxcomp/ice/xtree/service/DefaultPathResolver.java`, locate the `pathFor` method. Replace this block (currently around the end of the method):

```java
        List<CachedNode> chainLeafFirst = new ArrayList<>();
        String anchorPath = null;
        CachedNode cursor = startOpt.get();
        int steps = 0;
        while (cursor != null) {
            chainLeafFirst.add(cursor);
            if (cursor.parentId() == TreeConstants.ROOT_PARENT_ID) {
                break;
            }
            String memoForParent = memo.get(cursor.parentId());
            if (memoForParent != null) {
                anchorPath = memoForParent;
                break;
            }
            if (++steps > MAX_TREE_DEPTH) {
                log.warn("PathResolver: walk cap reached at id={}, possible cycle", itemTreeId);
                break;
            }
            CachedNode parent = cache.getById(cursor.parentId()).orElse(null);
            if (parent == null) {
                log.warn("PathResolver: missing ancestor parentId={} for id={} (originating id={})",
                        cursor.parentId(), cursor.itemTreeId(), itemTreeId);
                break;
            }
            cursor = parent;
        }

        StringBuilder accum = new StringBuilder(anchorPath == null ? "" : anchorPath);
        for (int i = chainLeafFirst.size() - 1; i >= 0; i--) {
            CachedNode node = chainLeafFirst.get(i);
            if (accum.length() > 0) accum.append(SEPARATOR);
            accum.append(node.name());
            memo.put(node.itemTreeId(), accum.toString());
        }

        return memo.getOrDefault(itemTreeId, "");
    }
```

with:

```java
        List<CachedNode> chainLeafFirst = new ArrayList<>();
        String anchorPath = null;
        boolean reachedRoot = false;
        CachedNode cursor = startOpt.get();
        int steps = 0;
        while (cursor != null) {
            chainLeafFirst.add(cursor);
            if (cursor.parentId() == TreeConstants.ROOT_PARENT_ID) {
                reachedRoot = true;
                break;
            }
            String memoForParent = memo.get(cursor.parentId());
            if (memoForParent != null) {
                anchorPath = memoForParent;
                break;
            }
            if (++steps > MAX_TREE_DEPTH) {
                log.warn("PathResolver: walk cap reached at id={}, possible cycle", itemTreeId);
                break;
            }
            CachedNode parent = cache.getById(cursor.parentId()).orElse(null);
            if (parent == null) {
                log.warn("PathResolver: missing ancestor parentId={} for id={} (originating id={})",
                        cursor.parentId(), cursor.itemTreeId(), itemTreeId);
                break;
            }
            cursor = parent;
        }

        StringBuilder accum = new StringBuilder(anchorPath == null ? "" : anchorPath);
        boolean rootPrefixPending = reachedRoot && anchorPath == null;
        for (int i = chainLeafFirst.size() - 1; i >= 0; i--) {
            CachedNode node = chainLeafFirst.get(i);
            if (rootPrefixPending) {
                accum.append(SEPARATOR);
                rootPrefixPending = false;
            } else if (accum.length() > 0) {
                accum.append(SEPARATOR);
            }
            accum.append(node.name());
            memo.put(node.itemTreeId(), accum.toString());
        }

        return memo.getOrDefault(itemTreeId, "");
    }
```

Semantics:
- Walk reached root → `reachedRoot=true`, anchor is `null`, `rootPrefixPending=true`. First name (the root, since chain is leaf-first and we iterate reversed) gets `/` prepended → `/root`. Subsequent steps append `/<name>` as before.
- Walk hit a memoised anchor → `anchorPath != null` (anchor already starts with `/` because it was produced by this same loop). The new `else if (accum.length() > 0)` branch handles separators. No change to anchor-based behaviour beyond the initial value.
- Walk hit cap or missing-ancestor → `reachedRoot=false`, `anchorPath=null`. `rootPrefixPending=false`. Output is slash-less — matches the existing degraded-path contract (no spurious leading slash on a partial path).

- [ ] **Step 4: Confirm the parameterised test now passes**

Run: `./gradlew test --tests com.myxcomp.ice.xtree.service.DefaultPathResolverTest`
Expected: Most tests pass for the known-id parameters. Other tests in the class still fail because their expected strings haven't been re-anchored yet (next step).

- [ ] **Step 5: Re-anchor remaining `DefaultPathResolverTest` expectations**

Update every remaining `"root` literal in this file to `"/root` and re-check empty-string / partial-path cases. The known partial-path test (`OrphanA` at line 84, 157) must stay slash-less. The pathsOf tests (lines 116, 126-130, 137, 145-146, 195-196) must gain leading slashes:

```java
            assertThat(result).containsExactly(Map.entry(10L, "/root/Users/testuser1"));
```
…
```java
                    Map.entry(1L,   "/root"),
                    Map.entry(2L,   "/root/Users"),
                    Map.entry(10L,  "/root/Users/testuser1"),
                    Map.entry(110L, "/root/Users/testuser1/MyReport"),
                    Map.entry(210L, "/root/Users/deepuser/L2/L3/L4/DeepReport"));
```
…
```java
            assertThat(result).containsExactly(Map.entry(10L, "/root/Users/testuser1"));
```
…
```java
                    Map.entry(10L,  "/root/Users/testuser1"),
                    Map.entry(999L, ""));
```
…
```java
            assertThat(result.get(50L)).isEqualTo("OrphanA");   // UNCHANGED: degraded path stays slash-less
```
…
```java
            assertThat(paths.get(100L)).isEqualTo("/root/A/B/C/D/leaf100");
            assertThat(paths.get(149L)).isEqualTo("/root/A/B/C/D/leaf149");
```

If you grep `"root` in the file and don't find any remaining unprefixed cases (excluding the `"OrphanA"` and similar degraded-path tests), you're done.

- [ ] **Step 6: Add a positive degraded-path-no-slash test**

Add a new `@Test` method at the bottom of `DefaultPathResolverTest` proving the degraded paths do NOT get a leading slash. Place it inside the appropriate existing `@Nested` class for partial-path behaviour (or, if there isn't one, just append before the closing brace of the test class):

```java
    @Test
    void partialPathFromMissingAncestorDoesNotGetLeadingSlash() {
        // Two-node chain where the parent is missing in the cache.
        cache.applyCreate(folder(900L, 800L, "Orphan"));
        // parentId=800 is not in the cache.
        assertThat(resolver.pathOf(900L)).isEqualTo("Orphan");
    }

    @Test
    void emptyStringForUnknownIdHasNoSlash() {
        assertThat(resolver.pathOf(999_999L)).isEmpty();
    }
```

The first test needs `folder(...)` helper and the `cache` field — copy the constructor pattern from existing tests in this class. If a test for missing-ancestor already exists (look for "missing" or "Orphan" in the file), skip the first new test and only add the second.

- [ ] **Step 7: Re-anchor `TreeServiceTest` expectations**

In `src/test/java/com/myxcomp/ice/xtree/service/TreeServiceTest.java`, replace every `"root` literal with `"/root` for the path strings. Specifically (line numbers from the current file):

- Line 50: `Map.of(10L, "root/Users/alice")` → `Map.of(10L, "/root/Users/alice")`
- Line 54: `new TreeNodeView(home, "root/Users/alice")` → `new TreeNodeView(home, "/root/Users/alice")`
- Line 62, 66, 76–79, 85–87, 97–99, 105–106, 142–146, 152, 154–156, 165 — each `"root` becomes `"/root`. Use Find/Replace inside the file with caution (don't touch comments or unrelated strings).

- [ ] **Step 8: Re-anchor `TreeServiceSubtreeNotFoundTest`**

In `src/test/java/com/myxcomp/ice/xtree/service/TreeServiceSubtreeNotFoundTest.java`, change:

```java
        when(pathResolver.pathsOf(List.of(99L))).thenReturn(Map.of(99L, "root/n"));
```

to:

```java
        when(pathResolver.pathsOf(List.of(99L))).thenReturn(Map.of(99L, "/root/n"));
```

and:

```java
        assertThat(result.get(0).path()).isEqualTo("root/n");
```

to:

```java
        assertThat(result.get(0).path()).isEqualTo("/root/n");
```

- [ ] **Step 9: Search for any remaining stale path literals**

Run from repo root:

```bash
grep -rn '"root/' src/test/java src/main/java | grep -v -- '-> "root/' | grep -v 'TODO\|//.*root/'
```

Expected: empty output (or only matches inside comments / Javadoc / strings unrelated to PathResolver output). Investigate any hits.

Also grep `'root/Folder1/IceReport'` (the legacy example in source comments) and update to `/root/Folder1/IceReport`:

```bash
grep -rn 'root/Folder1/IceReport\|root/Users/testuser' src/main/java src/test/java
```

Update any source-code Javadoc to the new format. (`PathResolver.java` line 7 example must become `"/root/Users/testuser1"`.)

- [ ] **Step 10: Run full suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL. If any test fails with a `root/...` vs `/root/...` mismatch, fix that single test by adding the leading slash to its expectation; do not change resolver code.

- [ ] **Step 11: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/service/DefaultPathResolver.java \
        src/main/java/com/myxcomp/ice/xtree/service/PathResolver.java \
        src/test/java/com/myxcomp/ice/xtree/service/DefaultPathResolverTest.java \
        src/test/java/com/myxcomp/ice/xtree/service/TreeServiceTest.java \
        src/test/java/com/myxcomp/ice/xtree/service/TreeServiceSubtreeNotFoundTest.java
git commit -m "$(cat <<'EOF'
feat(phase20): PathResolver emits leading-slash paths (/root/...)

DefaultPathResolver prepends the separator on the first step of the
root-anchored walk. Degraded paths (missing ancestor, cap reached) and
empty-string returns are unchanged. Tests and Javadoc re-anchored.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3 — `/search`: `SearchHitView`, service, mapper, controller

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/service/SearchHitView.java`
- Modify: `src/main/java/com/myxcomp/ice/xtree/service/SearchService.java`
- Modify: `src/test/java/com/myxcomp/ice/xtree/service/SearchServiceTest.java`
- Modify: `src/main/java/com/myxcomp/ice/xtree/api/mapper/SearchHitMapper.java`
- Modify: `src/test/java/com/myxcomp/ice/xtree/api/mapper/SearchHitMapperTest.java`
- Modify: `src/main/java/com/myxcomp/ice/xtree/api/controller/SearchController.java`
- Modify: `src/test/java/com/myxcomp/ice/xtree/api/controller/SearchControllerTest.java`

- [ ] **Step 1: Create `SearchHitView` record**

Create `src/main/java/com/myxcomp/ice/xtree/service/SearchHitView.java`:

```java
package com.myxcomp.ice.xtree.service;

import com.myxcomp.ice.xtree.cache.CachedNode;

/**
 * Service-layer pairing of a search hit and its lazily-resolved path. Mirrors the
 * {@link TreeNodeView} pattern: keeps the cache node intact, attaches a path computed
 * by {@link PathResolver} at response time, and lets {@link
 * com.myxcomp.ice.xtree.api.mapper.SearchHitMapper} project to the generated DTO.
 */
public record SearchHitView(CachedNode node, String path) {}
```

- [ ] **Step 2: Add failing `SearchServiceTest` for path on hits**

In `src/test/java/com/myxcomp/ice/xtree/service/SearchServiceTest.java`, add a `@Mock PathResolver pathResolver;` field if not already there, and pass it to the `new SearchService(...)` construction. Then add a new test (place at end of class, before closing brace):

```java
    @Test
    void searchByNamePopulatesPathOnEachHit() {
        CachedNode alice = leaf(10L, 2L, "alice", "Folder");
        CachedNode bob   = leaf(11L, 2L, "bobReport", "IceReport");

        when(cache.searchByName("b", OptionalInt.empty())).thenReturn(List.of(bob));
        when(pathResolver.pathsOf(List.of(11L)))
                .thenReturn(Map.of(11L, "/root/Users/bob/bobReport"));

        List<SearchHitView> hits = service.search("b", OptionalInt.empty());

        assertThat(hits).containsExactly(new SearchHitView(bob, "/root/Users/bob/bobReport"));
    }

    @Test
    void searchByIdPopulatesPath() {
        CachedNode node = leaf(42L, 1L, "thing", "IceReport");
        when(cache.getById(42L)).thenReturn(Optional.of(node));
        when(pathResolver.pathsOf(List.of(42L))).thenReturn(Map.of(42L, "/root/thing"));

        List<SearchHitView> hits = service.search("42", OptionalInt.empty());

        assertThat(hits).containsExactly(new SearchHitView(node, "/root/thing"));
    }
```

Add the necessary imports if missing: `com.myxcomp.ice.xtree.service.PathResolver`, `com.myxcomp.ice.xtree.service.SearchHitView`, `java.util.Map`, `java.util.Optional`. Also update any existing tests in this class that assert on the old `List<CachedNode>` return — they need to switch to `List<SearchHitView>` and either `.node()` to grab the underlying node or assert against full views with stubbed paths. Stub `pathResolver.pathsOf(...)` with empty maps where path is irrelevant.

- [ ] **Step 3: Confirm the new tests fail**

Run: `./gradlew test --tests com.myxcomp.ice.xtree.service.SearchServiceTest`
Expected: COMPILATION FAILURE (since `SearchService.search` still returns `List<CachedNode>`) or test failure once compilation is fixed.

- [ ] **Step 4: Update `SearchService` to return `List<SearchHitView>`**

Open `src/main/java/com/myxcomp/ice/xtree/service/SearchService.java`. Inject `PathResolver` via constructor (mirror the `TreeService` pattern). Change the return type of `search` from `List<CachedNode>` to `List<SearchHitView>`. After the existing hit-collection logic, resolve paths once and zip:

```java
package com.myxcomp.ice.xtree.service;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.TreeCache;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

@Service
public class SearchService {

    private final TreeCache cache;
    private final PathResolver pathResolver;

    public SearchService(TreeCache cache, PathResolver pathResolver) {
        this.cache = cache;
        this.pathResolver = pathResolver;
    }

    public List<SearchHitView> search(String q, OptionalInt limit) {
        if (q == null || q.isBlank()) return List.of();

        List<CachedNode> hits;
        try {
            long id = Long.parseLong(q.trim());
            Optional<CachedNode> byId = cache.getById(id);
            hits = byId.map(List::of).orElseGet(() -> cache.searchByName(q, limit));
        } catch (NumberFormatException e) {
            hits = cache.searchByName(q, limit);
        }

        if (hits.isEmpty()) return List.of();
        List<Long> ids = hits.stream().map(CachedNode::itemTreeId).toList();
        Map<Long, String> paths = pathResolver.pathsOf(ids);
        List<SearchHitView> out = new ArrayList<>(hits.size());
        for (CachedNode n : hits) {
            out.add(new SearchHitView(n, paths.getOrDefault(n.itemTreeId(), "")));
        }
        return List.copyOf(out);
    }
}
```

Adapt the body to match whatever the current `search(...)` implementation does — preserve all existing branching (id-first parse, fallback to name search, blank-input handling, limit application). The only new behaviour is the path-zip at the end.

If the current file already has any other helper methods or fields, retain them; only the return type, the resolver injection, and the final zip change.

- [ ] **Step 5: Update `SearchHitMapper` to accept `SearchHitView`**

Replace `src/main/java/com/myxcomp/ice/xtree/api/mapper/SearchHitMapper.java` with:

```java
package com.myxcomp.ice.xtree.api.mapper;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.generated.model.SearchHit;
import com.myxcomp.ice.xtree.service.SearchHitView;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class SearchHitMapper {

    public SearchHit toDto(SearchHitView view) {
        CachedNode node = view.node();
        SearchHit dto = new SearchHit(node.itemTreeId(), node.name(), node.type());
        dto.setPath(view.path());
        return dto;
    }

    public List<SearchHit> toDtos(List<SearchHitView> views) {
        List<SearchHit> out = new ArrayList<>(views.size());
        for (SearchHitView v : views) out.add(toDto(v));
        return out;
    }
}
```

- [ ] **Step 6: Update `SearchHitMapperTest`**

Open `src/test/java/com/myxcomp/ice/xtree/api/mapper/SearchHitMapperTest.java`. Adapt every test to pass `SearchHitView` instead of bare `CachedNode`. Add at least one assertion confirming `dto.getPath()` carries the view's path:

```java
    @Test
    void toDtoCopiesNameTypeAndPath() {
        CachedNode n = leaf(42L, 1L, "thing", "IceReport");
        SearchHitView view = new SearchHitView(n, "/root/thing");

        SearchHit dto = mapper.toDto(view);

        assertThat(dto.getItemTreeId()).isEqualTo(42L);
        assertThat(dto.getName()).isEqualTo("thing");
        assertThat(dto.getType()).isEqualTo("IceReport");
        assertThat(dto.getPath()).isEqualTo("/root/thing");
    }

    @Test
    void toDtosMapsEachView() {
        SearchHitView a = new SearchHitView(leaf(1L, 0L, "root", "Folder"), "/root");
        SearchHitView b = new SearchHitView(leaf(2L, 1L, "child", "IceReport"), "/root/child");

        List<SearchHit> out = mapper.toDtos(List.of(a, b));

        assertThat(out).hasSize(2);
        assertThat(out.get(0).getPath()).isEqualTo("/root");
        assertThat(out.get(1).getPath()).isEqualTo("/root/child");
    }
```

Replace any existing tests whose call signature was the old `mapper.toDto(node)` form. Use existing helper methods (`leaf(...)`) — copy from the existing file if needed.

- [ ] **Step 7: Update `SearchController`**

Replace `src/main/java/com/myxcomp/ice/xtree/api/controller/SearchController.java` with:

```java
package com.myxcomp.ice.xtree.api.controller;

import com.myxcomp.ice.xtree.api.mapper.SearchHitMapper;
import com.myxcomp.ice.xtree.generated.api.SearchApi;
import com.myxcomp.ice.xtree.generated.model.SearchHit;
import com.myxcomp.ice.xtree.service.SearchHitView;
import com.myxcomp.ice.xtree.service.SearchService;
import com.myxcomp.ice.xtree.service.exception.ErrorCode;
import com.myxcomp.ice.xtree.service.exception.ValidationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.OptionalInt;

@RestController
public class SearchController implements SearchApi {

    private final SearchService searchService;
    private final SearchHitMapper searchHitMapper;

    public SearchController(SearchService searchService, SearchHitMapper searchHitMapper) {
        this.searchService = searchService;
        this.searchHitMapper = searchHitMapper;
    }

    @Override
    public ResponseEntity<List<SearchHit>> search(String xIceUser, String q,
                                                  String xImpersonatedUser, Integer limit) {
        if (limit != null && limit <= 0) {
            throw new ValidationException(ErrorCode.INVALID_SEARCH_PARAMS,
                    "limit must be a positive integer");
        }
        OptionalInt limitOpt = limit != null ? OptionalInt.of(limit) : OptionalInt.empty();
        List<SearchHitView> hits = searchService.search(q, limitOpt);
        return ResponseEntity.ok(searchHitMapper.toDtos(hits));
    }
}
```

- [ ] **Step 8: Update `SearchControllerTest`**

Open `src/test/java/com/myxcomp/ice/xtree/api/controller/SearchControllerTest.java`. Adapt mocked stubs from `searchService.search(...)` returning `List<CachedNode>` to returning `List<SearchHitView>`. Add `path` JSON assertions to the existing happy-path test, e.g.:

```java
        when(searchService.search("alice", OptionalInt.empty()))
                .thenReturn(List.of(new SearchHitView(
                        leaf(10L, 2L, "alice", "Folder"), "/root/Users/alice")));

        mockMvc.perform(get("/api/v1/itemtree/search")
                        .header("X-Ice-User", "tester")
                        .param("q", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].itemTreeId").value(10))
                .andExpect(jsonPath("$[0].name").value("alice"))
                .andExpect(jsonPath("$[0].path").value("/root/Users/alice"));
```

Adjust to whatever the controller test's existing helpers and DSL look like.

- [ ] **Step 9: Run the full suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/service/SearchHitView.java \
        src/main/java/com/myxcomp/ice/xtree/service/SearchService.java \
        src/main/java/com/myxcomp/ice/xtree/api/mapper/SearchHitMapper.java \
        src/main/java/com/myxcomp/ice/xtree/api/controller/SearchController.java \
        src/test/java/com/myxcomp/ice/xtree/service/SearchServiceTest.java \
        src/test/java/com/myxcomp/ice/xtree/api/mapper/SearchHitMapperTest.java \
        src/test/java/com/myxcomp/ice/xtree/api/controller/SearchControllerTest.java
git commit -m "$(cat <<'EOF'
feat(phase20): /search populates path via PathResolver + SearchHitView

SearchService returns List<SearchHitView>, pairing each hit with its
lazily-resolved path via a single memoised pathsOf call. Mapper now
takes SearchHitView and sets dto.path. Controller adapted.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4 — `/items/get`: extend `ItemWithData`, service, mapper, controller test

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/service/ItemWithData.java`
- Modify: `src/main/java/com/myxcomp/ice/xtree/service/ItemService.java`
- Modify: `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceGetItemsWithDataTest.java` (or whatever the actual test file is called — search for `getItemsWithData` in the `service` test package)
- Modify: `src/main/java/com/myxcomp/ice/xtree/api/mapper/ItemNodeWithDataMapper.java`
- Modify: `src/test/java/com/myxcomp/ice/xtree/api/mapper/ItemNodeWithDataMapperTest.java`
- Modify: `src/test/java/com/myxcomp/ice/xtree/api/controller/ItemControllerTest.java`

- [ ] **Step 1: Extend `ItemWithData` with a `path` field**

Replace `src/main/java/com/myxcomp/ice/xtree/service/ItemWithData.java` with:

```java
package com.myxcomp.ice.xtree.service;

import java.time.Instant;
import java.util.List;

/**
 * Service-layer shape for {@code POST /items/get} response items.
 * For folder nodes: {@code dataJson} and {@code dataXml} are null, and {@code children} is
 * a non-null list populated one level deep (each child's {@code children} is null, because
 * child-of-folder nodes are never themselves expanded).
 * For non-folder, data-bearing nodes: at most one of {@code dataJson}/{@code dataXml} is
 * populated, and {@code children} is {@code null} (never an empty list).
 * Callers must use {@code children() == null} to distinguish non-folder nodes from
 * empty-folder nodes.
 *
 * <p>{@code path} is the root-anchored, slash-prefixed path of this node (e.g.
 * {@code "/root/Users/alice/MyReport"}). It is populated by
 * {@link ItemService#getItemsWithData(java.util.List)} via the {@link PathResolver}.
 */
public record ItemWithData(
        long itemTreeId,
        Long parentId,
        String name,
        String type,
        Instant lastUpdate,
        String lastUpdateUser,
        String dataJson,
        String dataXml,
        List<ItemWithData> children,
        String path
) {}
```

This is a breaking change: every constructor call now needs a 10th arg. The compiler will list them all.

- [ ] **Step 2: Compile and let the compiler list the call sites**

Run: `./gradlew compileJava compileTestJava`
Expected: COMPILATION FAILURE with `constructor ItemWithData ... cannot be applied to given types` at each call site. Note every file/line — there should be approximately 5 in `ItemService.shape(...)` and one in each test that builds an `ItemWithData` directly.

- [ ] **Step 3: Update `ItemService.shape(...)` and `getItemsWithData(...)`**

Open `src/main/java/com/myxcomp/ice/xtree/service/ItemService.java`. We need two changes:

**A. Pre-compute the path map in `getItemsWithData`.**

Locate the second pass loop that builds `out` (currently around line 405 in the file, with the loop `for (CachedNode n : requested)`). Just before that loop, gather the id union for top-level + child ids and call `pathResolver.pathsOf(...)` once. Inject `PathResolver` into the service constructor if not already present.

Constructor — add `PathResolver pathResolver` parameter and assign to a new `private final PathResolver pathResolver` field. (Spring auto-wires by type.)

`getItemsWithData` body, around the line that currently reads `List<ItemWithData> out = new ArrayList<>(requested.size());`:

```java
        // Phase 20: pre-compute paths for top-level items + expanded folder children.
        java.util.LinkedHashSet<Long> pathIds = new java.util.LinkedHashSet<>();
        for (CachedNode n : requested) {
            pathIds.add(n.itemTreeId());
            if (Types.isFolder(n.type())) {
                for (CachedNode c : folderChildren.get(n.itemTreeId())) {
                    pathIds.add(c.itemTreeId());
                }
            }
        }
        Map<Long, String> pathByid = pathResolver.pathsOf(pathIds);

        List<JsonBackfillRow> backfillBatch = new ArrayList<>();
        List<ItemWithData> out = new ArrayList<>(requested.size());
        for (CachedNode n : requested) {
            String path = pathByid.getOrDefault(n.itemTreeId(), "");
            if (Types.isFolder(n.type())) {
                List<ItemWithData> shapedChildren = new ArrayList<>();
                for (CachedNode c : folderChildren.get(n.itemTreeId())) {
                    String childPath = pathByid.getOrDefault(c.itemTreeId(), "");
                    shapedChildren.add(shape(c, payloadById, backfillBatch, null, childPath));
                }
                out.add(shape(n, payloadById, backfillBatch, List.copyOf(shapedChildren), path));
            } else {
                out.add(shape(n, payloadById, backfillBatch, null, path));
            }
        }
```

**B. Update `shape(...)` signature.**

Add `String path` as the trailing parameter, and append `, path` to every `new ItemWithData(...)` constructor call inside `shape(...)`. There are five constructions (each branch). Each becomes (using the first branch as a model):

```java
    private ItemWithData shape(CachedNode n,
                               Map<Long, PayloadRow> payloadById,
                               List<JsonBackfillRow> backfillBatch,
                               List<ItemWithData> children,
                               String path) {
        if (Types.isFolder(n.type()) || !policy.hasData(n.type())) {
            return new ItemWithData(n.itemTreeId(), n.parentId(), n.name(), n.type(),
                    n.lastUpdate(), n.lastUpdateUser(), null, null, children, path);
        }
        // … other branches: append `, path` as the last argument to each `new ItemWithData(...)`.
    }
```

Apply this to all five (or however many) constructor sites within `shape(...)`. Do NOT change ordering of any other argument.

- [ ] **Step 4: Update `ItemNodeWithDataMapper` to set `path` on the DTO and recurse**

Replace the `toDto` body in `src/main/java/com/myxcomp/ice/xtree/api/mapper/ItemNodeWithDataMapper.java`:

```java
    public ItemNodeWithData toDto(ItemWithData src) {
        ItemNodeWithData dto = new ItemNodeWithData(
                src.itemTreeId(),
                src.parentId(),
                src.name(),
                src.type(),
                timeMapper.toOffsetDateTime(src.lastUpdate()),
                src.lastUpdateUser());

        dto.setPath(src.path());

        if (src.dataJson() != null) {
            dto.setDataJson(parseJson(src.dataJson(), src.itemTreeId()));
        }
        if (src.dataXml() != null) {
            dto.setDataXml(src.dataXml());
        }
        if (src.children() == null) {
            dto.setChildren(null);
        } else {
            List<ItemNodeWithData> shaped = new ArrayList<>(src.children().size());
            for (ItemWithData c : src.children()) shaped.add(toDto(c));
            dto.setChildren(shaped);
        }
        return dto;
    }
```

The recursive call to `toDto(c)` handles children's path automatically.

- [ ] **Step 5: Fix every `ItemServiceGetItemsWithDataTest` (or equivalent) constructor**

Open the test file(s) that build `ItemWithData` instances directly. For each `new ItemWithData(...)` constructor call, append `, "/some/test/path"` (or `, ""`) as the 10th argument.

Find them with:

```bash
grep -rln 'new ItemWithData(' src/test
```

Update every match. Where a test asserts behaviour that does NOT involve paths, passing an empty string is fine. Where the test asserts path behaviour (the new tests in Step 6), use a meaningful value.

- [ ] **Step 6: Add `ItemService.getItemsWithData` path tests**

Locate the appropriate ItemService test class for `getItemsWithData` (likely `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceGetItemsWithDataTest.java`; if not, find with `grep -rln 'getItemsWithData' src/test`). Add `@Mock PathResolver pathResolver;` if missing; pass it to the `new ItemService(...)` constructor. Append the new constructor argument to every existing `new ItemService(...)` site in this test (and any other ItemService test that constructs the service).

Add the following test methods to the test class (adjust helpers to match the existing patterns in the file — `folder(...)`, `leaf(...)`, `whenCacheGetById(...)` etc):

```java
    @Test
    void getItemsWithDataPopulatesPathOnTopLevelItem() {
        CachedNode node = leaf(10L, 2L, "alice", "IceReport");
        seedCache(node);  // adapt to whatever the test helper is
        when(pathResolver.pathsOf(argThat(set -> set.contains(10L))))
                .thenReturn(Map.of(10L, "/root/Users/alice"));

        List<ItemWithData> out = service.getItemsWithData(List.of(10L));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).path()).isEqualTo("/root/Users/alice");
    }

    @Test
    void getItemsWithDataPopulatesPathOnEachExpandedChild() {
        CachedNode folder = folder(2L, 1L, "Users");
        CachedNode child  = leaf(10L, 2L, "alice", "Folder");
        seedCacheWithChildren(folder, List.of(child));
        when(pathResolver.pathsOf(argThat(set -> set.contains(2L) && set.contains(10L))))
                .thenReturn(Map.of(
                        2L,  "/root/Users",
                        10L, "/root/Users/alice"));

        List<ItemWithData> out = service.getItemsWithData(List.of(2L));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).path()).isEqualTo("/root/Users");
        assertThat(out.get(0).children()).hasSize(1);
        assertThat(out.get(0).children().get(0).path()).isEqualTo("/root/Users/alice");
    }

    @Test
    void getItemsWithDataCallsPathsOfAtMostOnce() {
        CachedNode folder = folder(2L, 1L, "Users");
        CachedNode child  = leaf(10L, 2L, "alice", "Folder");
        seedCacheWithChildren(folder, List.of(child));
        when(pathResolver.pathsOf(any()))
                .thenReturn(Map.of(2L, "/root/Users", 10L, "/root/Users/alice"));

        service.getItemsWithData(List.of(2L));

        verify(pathResolver, times(1)).pathsOf(any());
    }
```

Add imports as needed: `static org.mockito.ArgumentMatchers.any`, `static org.mockito.ArgumentMatchers.argThat`, `static org.mockito.Mockito.times`, `static org.mockito.Mockito.verify`, `org.mockito.Mock`, `com.myxcomp.ice.xtree.service.PathResolver`.

- [ ] **Step 7: Update `ItemNodeWithDataMapperTest`**

In `src/test/java/com/myxcomp/ice/xtree/api/mapper/ItemNodeWithDataMapperTest.java`, every `new ItemWithData(...)` in the test bodies gains a 10th argument. Add at least one test confirming the DTO's `path` is set from the source:

```java
    @Test
    void toDtoCopiesPath() {
        ItemWithData src = new ItemWithData(
                42L, 1L, "thing", "IceReport",
                Instant.parse("2026-05-27T10:00:00Z"), "tester",
                "{\"k\":1}", null, null,
                "/root/Folder1/thing");

        ItemNodeWithData dto = mapper.toDto(src);

        assertThat(dto.getPath()).isEqualTo("/root/Folder1/thing");
    }

    @Test
    void toDtoCopiesPathOnFolderChildrenRecursively() {
        ItemWithData child = new ItemWithData(
                10L, 2L, "alice", "Folder",
                Instant.parse("2026-05-27T10:00:00Z"), "tester",
                null, null, List.of(), "/root/Users/alice");
        ItemWithData folder = new ItemWithData(
                2L, 1L, "Users", "Folder",
                Instant.parse("2026-05-27T10:00:00Z"), "tester",
                null, null, List.of(child), "/root/Users");

        ItemNodeWithData dto = mapper.toDto(folder);

        assertThat(dto.getPath()).isEqualTo("/root/Users");
        assertThat(dto.getChildren()).hasSize(1);
        assertThat(dto.getChildren().get(0).getPath()).isEqualTo("/root/Users/alice");
    }
```

- [ ] **Step 8: Flip the `$[0].path doesNotExist` assertion in `ItemControllerTest`**

In `src/test/java/com/myxcomp/ice/xtree/api/controller/ItemControllerTest.java`, locate line ~499 with `.andExpect(jsonPath("$[0].path").doesNotExist())`. Replace that exact assertion with a positive assertion against the stubbed `ItemWithData`'s path.

Look at the test method context: it stubs `itemService.getItemsWithData(...)` to return an `ItemWithData`. Update that stub to include a path, then change the assertion:

```java
        when(itemService.getItemsWithData(List.of(42L))).thenReturn(List.of(item));   // existing — `item` now built with path "/root/Folder1/Report"
```

```java
        .andExpect(jsonPath("$[0].path").value("/root/Folder1/Report"))
```

For other `getItems`-related tests in this file, add a single positive `$[0].path` assertion to the happy-path test.

- [ ] **Step 9: Run the full suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL. If a constructor-arity test still uses 9 arguments, fix it; if a test asserts an old `null` path, switch to the stubbed value.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/service/ItemWithData.java \
        src/main/java/com/myxcomp/ice/xtree/service/ItemService.java \
        src/main/java/com/myxcomp/ice/xtree/api/mapper/ItemNodeWithDataMapper.java \
        src/test/java/com/myxcomp/ice/xtree/service/ \
        src/test/java/com/myxcomp/ice/xtree/api/mapper/ItemNodeWithDataMapperTest.java \
        src/test/java/com/myxcomp/ice/xtree/api/controller/ItemControllerTest.java
git commit -m "$(cat <<'EOF'
feat(phase20): /items/get populates path on items and folder children

ItemWithData gains a path field. ItemService.getItemsWithData calls
pathResolver.pathsOf once on the id union (top-level + expanded folder
children) and stamps each shape. ItemNodeWithDataMapper sets dto.path,
recursion handles children. ItemControllerTest flip + new mapper tests.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5 — `/users/{userName}/home-folder`: path via controller

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/api/controller/UserController.java`
- Modify: `src/test/java/com/myxcomp/ice/xtree/api/controller/UserControllerTest.java`

Keep `HomeFolderService.findHomeFolder` returning `CachedNode` — many other callers (ownership checks in ItemService, /tree path in TreeService) don't need the path. Compute the path in the controller and build a `TreeNodeView` there, then map via the existing `ItemNodeMapper`.

- [ ] **Step 1: Add failing controller test**

In `src/test/java/com/myxcomp/ice/xtree/api/controller/UserControllerTest.java`, modify (or add) the happy-path test for `getHomeFolder` to assert `path`:

```java
    @Test
    void getHomeFolderReturnsItemNodeWithPath() throws Exception {
        CachedNode folder = folder(20L, 2L, "alice");
        when(homeFolderService.findHomeFolder("alice")).thenReturn(folder);
        when(pathResolver.pathOf(20L)).thenReturn("/root/Users/alice");

        mockMvc.perform(get("/api/v1/itemtree/users/alice/home-folder")
                        .header("X-Ice-User", "tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemTreeId").value(20))
                .andExpect(jsonPath("$.name").value("alice"))
                .andExpect(jsonPath("$.path").value("/root/Users/alice"));
    }
```

Add `@MockBean PathResolver pathResolver;` to the test class if not present. Use whatever `folder(...)` helper or `CachedNode` builder the test already provides.

- [ ] **Step 2: Confirm the test fails**

Run: `./gradlew test --tests com.myxcomp.ice.xtree.api.controller.UserControllerTest`
Expected: FAIL — either Spring cannot wire the mock yet (if the bean isn't injected into the controller), or the JSON has no `path` field.

- [ ] **Step 3: Wire `PathResolver` into `UserController`**

Replace `src/main/java/com/myxcomp/ice/xtree/api/controller/UserController.java` with:

```java
package com.myxcomp.ice.xtree.api.controller;

import com.myxcomp.ice.xtree.api.mapper.ItemNodeMapper;
import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.generated.api.UsersApi;
import com.myxcomp.ice.xtree.generated.model.ItemNode;
import com.myxcomp.ice.xtree.service.HomeFolderService;
import com.myxcomp.ice.xtree.service.PathResolver;
import com.myxcomp.ice.xtree.service.TreeNodeView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserController implements UsersApi {

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

    @Override
    public ResponseEntity<ItemNode> getHomeFolder(String userName, String xIceUser, String xImpersonatedUser) {
        CachedNode folder = homeFolderService.findHomeFolder(userName);
        String path = pathResolver.pathOf(folder.itemTreeId());
        return ResponseEntity.ok(itemNodeMapper.toDto(new TreeNodeView(folder, path)));
    }
}
```

`HomeFolderService.findHomeFolder` already throws when the user has no home folder, so the existing 404 path is preserved.

- [ ] **Step 4: Run controller tests, then full suite**

Run: `./gradlew test --tests com.myxcomp.ice.xtree.api.controller.UserControllerTest`
Expected: PASS.

Then: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/api/controller/UserController.java \
        src/test/java/com/myxcomp/ice/xtree/api/controller/UserControllerTest.java
git commit -m "$(cat <<'EOF'
feat(phase20): /users/{u}/home-folder returns path

UserController injects PathResolver, builds a TreeNodeView from the
CachedNode + resolved path, and lets ItemNodeMapper stamp dto.path.
HomeFolderService signature is unchanged — other callers (ownership
checks, /tree home resolution) don't need the path.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 6 — E2E roundtrip test for `/search` and `/items/get`

**Files:**
- Modify: an existing E2E test class under `src/test/java/com/myxcomp/ice/xtree/e2e/` (locate with `find src/test/java/com/myxcomp/ice/xtree/e2e -name '*Test.java'`)
- Or create: `src/test/java/com/myxcomp/ice/xtree/e2e/PathOnReadEndpointsE2ETest.java`

A single E2E test exercising the real Spring stack confirms the path field flows end-to-end against the H2-backed cache.

- [ ] **Step 1: Inspect existing E2E setup**

Run: `find src/test/java/com/myxcomp/ice/xtree/e2e -name '*Test.java' | head -20`
Read one of the existing E2E tests to learn the conventions (which annotations, how `TestRestTemplate` or `MockMvc` is used, how auth headers are added).

- [ ] **Step 2: Add the E2E test**

If an existing test class is a natural fit (search-related E2E or items-get-related E2E), add new test methods. Otherwise create a new file at `src/test/java/com/myxcomp/ice/xtree/e2e/PathOnReadEndpointsE2ETest.java`. Inside, write two tests that hit real endpoints via the real Spring `MockMvc` (or `TestRestTemplate`) and assert the JSON response contains a `path` field starting with `/root`:

```java
package com.myxcomp.ice.xtree.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class PathOnReadEndpointsE2ETest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void searchHitsCarryLeadingSlashPath() throws Exception {
        MvcResult res = mockMvc.perform(get("/api/v1/itemtree/search")
                        .header("X-Ice-User", "testuser1")
                        .param("q", "testuser1"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(res.getResponse().getContentAsString());
        assertThat(body.isArray()).isTrue();
        assertThat(body.size()).isGreaterThan(0);
        for (JsonNode hit : body) {
            assertThat(hit.get("path").asText()).startsWith("/root");
        }
    }

    @Test
    void itemsGetReturnsPathOnItemAndChildren() throws Exception {
        // Pick a known folder id from the dev-seed dataset. Adjust to whatever
        // seed exists; consult src/main/resources/db/data.sql or the dev-seed file.
        MvcResult res = mockMvc.perform(post("/api/v1/itemtree/items/get")
                        .header("X-Ice-User", "testuser1")
                        .contentType("application/json")
                        .content("{\"ids\":[2]}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(res.getResponse().getContentAsString());
        assertThat(body.isArray()).isTrue();
        assertThat(body.size()).isEqualTo(1);
        JsonNode folder = body.get(0);
        assertThat(folder.get("path").asText()).startsWith("/root");
        if (folder.has("children")) {
            for (JsonNode c : folder.get("children")) {
                assertThat(c.get("path").asText()).startsWith("/root");
            }
        }
    }
}
```

If the existing seed dataset uses different ids, adapt the request bodies and the user name. Check `src/main/resources/db/data.sql` (or whatever schema-init script your codebase uses) to find a folder id with children.

- [ ] **Step 3: Run the new test**

Run: `./gradlew test --tests com.myxcomp.ice.xtree.e2e.PathOnReadEndpointsE2ETest`
Expected: PASS.

If the test fails because `/api/v1/itemtree/items/get` returns nothing for the seed ids, switch the id or skip that assertion. The goal is the path-format assertion, not the seed contents.

- [ ] **Step 4: Run the full suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/myxcomp/ice/xtree/e2e/
git commit -m "$(cat <<'EOF'
test(phase20): e2e roundtrip confirms /search and /items/get carry path

End-to-end Spring stack with H2 seed data — asserts every search hit
and every /items/get item (and any expanded children) has a path field
starting with /root.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 7 — Test UI: `Copy ID` and `Show and copy full path` context-menu items

**Files:**
- Modify: `src/main/resources/static/js/menu.js`
- Optionally modify: `src/main/resources/static/styles.css` (to style the separator)

The current `openContextMenu(id, clientX, clientY)` returns early on root. Replace the early return with a dedicated root branch that shows only the two new items. For non-root, prepend the two new items plus a separator.

- [ ] **Step 1: Refactor `openContextMenu`**

Open `src/main/resources/static/js/menu.js`. Replace the entire `openContextMenu` function with:

```javascript
export function openContextMenu(id, clientX, clientY) {
  closeMenu();
  const node = state.tree.nodesById.get(id);
  if (!node) return;
  const isRoot = id === ROOT_ID;
  const isFolder = node.type === FOLDER;
  const hasData = !isFolder && !node.type.startsWith('Shortcut');

  const items = [];
  // Phase 20: Copy ID and Show-and-copy path apply to every node, root included.
  items.push({ label: `Copy ID (${id})`, action: () => copyId(id) });
  items.push({ label: 'Show and copy full path', action: () => showAndCopyPath(node), separatorAfter: !isRoot });

  if (!isRoot) {
    if (isFolder) {
      items.push({ label: 'Refresh subtree', action: () => refreshSubtree(id) });
      items.push({ label: 'Create child', action: () => openCreateModal(id) });
    }
    if (hasData) {
      items.push({ label: 'Edit data', action: () => openEditDataModal(id, null) });
    }
    items.push({ label: 'Rename', action: () => openRenameModal(id, node.name) });
    items.push({ label: 'Delete', action: () => openDeleteConfirm(id, node) });
    items.push({ label: 'Cut', action: () => { state.clipboard = { op: 'cut', sourceId: id, sourceName: node.name }; renderTree(); } });
    items.push({ label: 'Copy', action: () => { state.clipboard = { op: 'copy', sourceId: id, sourceName: node.name }; renderTree(); } });
    if (isFolder && state.clipboard) {
      items.push({ label: `Paste here (${state.clipboard.op} ${state.clipboard.sourceName})`,
                   action: () => pasteInto(id) });
    }
  }

  const ul = document.createElement('ul');
  ul.className = 'context-menu';
  ul.style.left = `${clientX}px`;
  ul.style.top = `${clientY}px`;
  for (const item of items) {
    const li = document.createElement('li');
    li.textContent = item.label;
    if (item.separatorAfter) li.classList.add('context-menu-separator');
    li.addEventListener('click', (e) => { e.stopPropagation(); closeMenu(); item.action(); });
    ul.appendChild(li);
  }
  document.body.appendChild(ul);
  openMenuEl = ul;
}
```

- [ ] **Step 2: Add the two helper functions**

In the same file, at module scope (e.g. just below `closeMenu`):

```javascript
async function copyId(id) {
  const text = String(id);
  try {
    await navigator.clipboard.writeText(text);
    toastSuccess(`Copied id ${text}`);
  } catch (e) {
    toastError(`Clipboard write failed: ${text}`);
  }
}

async function showAndCopyPath(node) {
  const path = node.path ?? '';
  if (!path) {
    alert('Path is not available for this node (no path was returned by the server).');
    return;
  }
  try {
    await navigator.clipboard.writeText(path);
  } catch (e) {
    toastError(`Clipboard write failed; path is: ${path}`);
    return;
  }
  alert('Path copied:\n' + path);
}
```

- [ ] **Step 3: Add the CSS separator rule**

Open `src/main/resources/static/styles.css`. Add the following rule (location doesn't matter — append at the end):

```css
.context-menu li.context-menu-separator {
  border-bottom: 1px solid #d0d0d0;
  padding-bottom: 6px;
  margin-bottom: 4px;
}
```

If the existing context-menu styling already specifies padding/margin in conflict, adjust to match the file's existing visual style.

- [ ] **Step 4: Manual verification (no automated UI tests)**

Run: `./gradlew bootRun`
In a browser at `http://localhost:8080/` (or whichever port `application-dev.yml` uses):

1. Right-click root → confirm only two items appear: `Copy ID (1)` and `Show and copy full path`.
2. Click `Copy ID (1)` → toast "Copied id 1" appears; paste in another window confirms `1`.
3. Click `Show and copy full path` → `alert("Path copied:\n/root")`; paste confirms `/root`.
4. Right-click a non-root node (e.g. id=10001) → confirm two new items at top, separator, then the existing menu (Refresh subtree / Create child / Edit data / Rename / Delete / Cut / Copy / [Paste]).
5. Click `Copy ID (10001)` → toast "Copied id 10001"; paste confirms `10001`.
6. Click `Show and copy full path` → alert showing `/root/.../<name>`; paste confirms same.
7. (Edge) If clipboard write fails (e.g. test in an insecure browser context), confirm fallback toast shows the value.

Document any deviation as a bug to fix before committing.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/static/js/menu.js src/main/resources/static/styles.css
git commit -m "$(cat <<'EOF'
feat(phase20): static UI adds Copy ID and Show-and-copy-path menu items

Right-click on any node — including root — now shows Copy ID (<id>)
and Show and copy full path. Path is read from node.path (populated by
/tree). Clipboard via navigator.clipboard.writeText with toast/alert
fallback. Existing menu items unchanged.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 8 — Design doc, implementation notes, memory

**Files:**
- Modify: `itemtree-service-design.md` (§3 + §9)
- Modify: `IMPLEMENTATION_NOTES.md`
- Create: `/home/dave/.claude/projects/-home-dave-Git-item-tree/memory/project-phase20-path-everywhere-done.md`
- Modify: `/home/dave/.claude/projects/-home-dave-Git-item-tree/memory/MEMORY.md`

- [ ] **Step 1: Update `itemtree-service-design.md` §3 schema summary**

In `itemtree-service-design.md`, locate line 152 (the `ItemNode` schema bullet). Replace:

```
- **`ItemNode`** — structural: `itemTreeId`, `parentId`, `name`, `type`, `path` (tree endpoints only), `lastUpdate`, `lastUpdateUser`.
```

with:

```
- **`ItemNode`** — structural: `itemTreeId`, `parentId`, `name`, `type`, `path` (root-anchored, leading-slash, e.g. `/root/Folder1/IceReport`; populated on all read endpoints; absent on mutation responses), `lastUpdate`, `lastUpdateUser`.
```

On the `ItemNodeWithData` bullet (next line), append `, `path` (same shape as on `ItemNode`)` to the field list. On the `SearchHit` bullet, replace with:

```
- **`SearchHit`** — `itemTreeId`, `name`, `type`, `path`.
```

Then locate the `/tree` response shape paragraph (line 160) and change the inline example `root/Folder1/IceReport` to `/root/Folder1/IceReport`.

- [ ] **Step 2: Update `itemtree-service-design.md` §9**

Locate §9 (line 621). Replace the existing endpoint table:

```
| Endpoint | Path field populated? |
|---|---|
| `/tree` | Yes |
| `/tree/{rootId}/subtree` | Yes |
| `/tree/{rootId}/subtree-full` | Yes |
| `/items/get` | No |
| `/search` | No |
| Create / update / move / rename / delete responses | No |
```

with:

```
| Endpoint | Path field populated? |
|---|---|
| `/tree` | Yes |
| `/tree/{rootId}/subtree` | Yes |
| `/tree/{rootId}/subtree-full` | Yes |
| `/items/get` (incl. expanded children) | Yes |
| `/search` | Yes |
| `/users/{userName}/home-folder` | Yes |
| Create / update / move / rename / copy responses | No |
| Delete (204 No Content) | n/a |
```

In the same section, change `Path format: `"root/Folder1/IceReport"` (line 646) to `Path format: leading-slash root-anchored, e.g. `"/root/Folder1/IceReport"`. Walks that do not reach the root (missing ancestor, cap reached) return their partial chain slash-less; an unknown id returns the empty string.`

- [ ] **Step 3: Update `IMPLEMENTATION_NOTES.md`**

Append a new section at the end of `IMPLEMENTATION_NOTES.md`:

```markdown
## Phase 20 — `path` on all read endpoints + leading-slash format + UI ID/path copy (2026-05-27)

`PathResolver` now plumbed into `/items/get`, `/search`, `/users/{u}/home-folder` (the tree endpoints had it since Phase 6). Path format standardised on a leading slash: `/root/Users/alice`. Stored-on-cache vs lazy-compute decision (§9) re-affirmed in favour of lazy compute. Mutation response bodies unchanged.

Test UI context menu adds `Copy ID (<id>)` and `Show and copy full path` to every node (root included); both write to `navigator.clipboard` and use `alert()` for the path display (with a toast fallback).

Spec: `docs/superpowers/specs/2026-05-27-phase20-path-on-all-read-endpoints-design.md`.
Plan: `docs/superpowers/plans/2026-05-27-phase20-path-on-all-read-endpoints.md`.

Phase 21 (or renumbered Phase 18 work-PC wiring) is the next user-managed step.
```

Adjust the test count if relevant (run `./gradlew test` and grab the count from the summary if you want to record it).

- [ ] **Step 4: Run full suite once more before tagging**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit doc updates**

```bash
git add itemtree-service-design.md IMPLEMENTATION_NOTES.md
git commit -m "$(cat <<'EOF'
docs(phase20): design §3 + §9 and implementation notes

ItemNode/ItemNodeWithData/SearchHit schema summary now reflects that
path is populated on all read endpoints in leading-slash form.
§9 table updated; Phase 20 entry added to implementation notes.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 6: Tag the phase**

```bash
git tag phase-20-path-on-all-read-endpoints
```

Do NOT push (user-managed).

- [ ] **Step 7: Write memory file**

Write `/home/dave/.claude/projects/-home-dave-Git-item-tree/memory/project-phase20-path-everywhere-done.md`:

```markdown
---
name: project-phase20-path-everywhere-done
description: Phase 20 done as of 2026-05-27; path on every read endpoint, leading-slash format, test UI ID/path copy; tagged phase-20-path-on-all-read-endpoints
metadata:
  type: project
---

Phase 20 of the ITEMTREE service is complete as of 2026-05-27.

Scope shipped:
- `/items/get`, `/search`, `/users/{u}/home-folder` now populate `path` on every item (and on the expanded children of `/items/get` folders).
- Path format standardised on a leading slash: `/root/Users/alice` (previously `root/Users/alice`). Implemented inside `DefaultPathResolver`; degraded paths (cycles, missing ancestors) and the empty-string return for unknown ids remain slash-less.
- Mutation response bodies (`create`, `update`, `move`, `rename`, `copy`) intentionally unchanged. `DELETE` continues to return 204.
- Test UI context menu adds `Copy ID (<id>)` and `Show and copy full path` to every node (root included); both write to `navigator.clipboard` and use `alert()` for path display.

**Why:** the design called out in §9 that lazy compute beats storing path on `CachedNode` (storing would force O(subtree) recomputation under the write lock on every rename/move). Phase 20 just extends the resolver to the remaining endpoints — no cache structural change.

**How to apply:** when adding a new read endpoint that returns items, plumb `PathResolver` into the service or controller, resolve via `pathsOf(...)` (memoised across the call) for bulk shapes or `pathOf(...)` for singletons, and stamp the value in the mapper that converts service-layer views into generated DTOs. Mutation responses stay path-less.

Spec: `docs/superpowers/specs/2026-05-27-phase20-path-on-all-read-endpoints-design.md`.
Plan: `docs/superpowers/plans/2026-05-27-phase20-path-on-all-read-endpoints.md`.
Tag: `phase-20-path-on-all-read-endpoints`.

Related: [[project-phase19-search-simplification-done]], [[project-phase15-test-ui-done]], [[feedback-h2-recursive-cte]].
```

- [ ] **Step 8: Append to memory index**

Append to `/home/dave/.claude/projects/-home-dave-Git-item-tree/memory/MEMORY.md`:

```
- [Phase 20 path on all read endpoints complete](project-phase20-path-everywhere-done.md) — Phase 20 done as of 2026-05-27; path on every read endpoint + leading-slash format + UI ID/path copy; tagged phase-20-path-on-all-read-endpoints
```

No commit needed for memory files (outside repo).

- [ ] **Step 9: Final confirmation**

Run: `git log --oneline phase-19-search-simplification..HEAD 2>/dev/null || git log --oneline -15`
Expected: a clean chain of Phase 20 commits — OpenAPI spec, path format, /search, /items/get, /users/{u}/home-folder, E2E, test UI, docs.

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

Phase 20 complete.

---

## Self-review summary

- **Spec coverage:** All eight sections of the spec map to numbered tasks: §1 Goal → Tasks 3–7; §2 Architecture → Task 2 + no cache change anywhere; §3 Format → Task 2; §4 OpenAPI → Task 1; §5 Service → Tasks 3, 4, 5; §6 Mappers → Tasks 3, 4; §7 Tests → integrated into each implementation task + Task 6; §8 UI → Task 7; §9 Rollout → matches Task 1–8 commit chain; §10 Out-of-scope → respected (no mutation-response touches, no `CachedNode` field, no path escaping); §11 Invariants → preserved.
- **Placeholders:** none — every step contains the actual code, command, or text edit.
- **Type consistency:** `SearchHitView(CachedNode, String)`, `ItemWithData` with 10 fields ending in `String path`, `TreeNodeView(CachedNode, String)` reused as-is. Mapper signatures match: `SearchHitMapper.toDto(SearchHitView)`, `ItemNodeMapper.toDto(TreeNodeView)` (existing), `ItemNodeWithDataMapper.toDto(ItemWithData)`.

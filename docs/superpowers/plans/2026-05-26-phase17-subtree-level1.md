# Phase 17 — Level-1 default for `getSubtree`; recursive becomes `getSubtreeFull` — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the single recursive `/subtree` endpoint with two endpoints — a fast level-1 default (`/subtree` = root + immediate children) and the preserved recursive one (`/subtree-full` = root + all descendants) — and route the test UI's at-login full pre-load to `/subtree-full` while every other UI fetch (chevron-expand, post-mutation refresh, search-jump) uses level-1.

**Architecture:** OpenAPI gains a new operation and renames the existing one; `TreeCache.getSubtreeFlat` is renamed to `getSubtreeFlatFull` (no behavioural change); `TreeService` keeps the recursive code as `getSubtreeFull` and adds a new `getSubtree` that composes `getById + getChildren`; the controller implements both generated interface methods; the static UI splits its API helper and bookkeeping. No new error codes, no new metrics, no new ownership rules. Spec: `docs/superpowers/specs/2026-05-26-subtree-level1-design.md`.

**Tech Stack:** Java 21, Spring Boot, JdbcClient, OpenAPI generator, JUnit 5, Mockito, AssertJ, MockMvc, plain ES modules for the static UI.

---

## File Structure

**Java — modified:**
- `src/main/resources/openapi/itemtree-api.yaml` — rename existing operation, add new operation.
- `src/main/java/com/myxcomp/ice/xtree/cache/TreeCache.java` — rename `getSubtreeFlat` → `getSubtreeFlatFull`.
- `src/main/java/com/myxcomp/ice/xtree/cache/DefaultTreeCache.java` — rename method.
- `src/main/java/com/myxcomp/ice/xtree/service/TreeService.java` — rename existing `getSubtree` to `getSubtreeFull`; add new `getSubtree`.
- `src/main/java/com/myxcomp/ice/xtree/api/controller/TreeController.java` — rename existing method to `getSubtreeFull`; add new `getSubtree`.
- `src/main/java/com/myxcomp/ice/xtree/service/TreeNodeView.java` — touch the Javadoc.

**Java tests — modified:**
- `src/test/java/com/myxcomp/ice/xtree/cache/DefaultTreeCacheTest.java` — rename 4 tests.
- `src/test/java/com/myxcomp/ice/xtree/service/TreeServiceTest.java` — rename 3 tests + add 4 new tests in a `GetSubtree` nested class.
- `src/test/java/com/myxcomp/ice/xtree/service/TreeServiceSubtreeNotFoundTest.java` — retarget 2 tests to `getSubtreeFull`.
- `src/test/java/com/myxcomp/ice/xtree/api/controller/TreeControllerTest.java` — rename 3 tests; add new `GetSubtree` nested class with 3 tests.
- `src/test/java/com/myxcomp/ice/xtree/e2e/ItemTreeApplicationE2EIT.java` — add 1 new test.

**UI — modified:**
- `src/main/resources/static/js/api.js` — rename `getSubtree` helper to `getSubtreeFull`; add new `getSubtree`.
- `src/main/resources/static/js/tree.js` — narrow `ingestSubtreeResult` (drop the folder-marking loop); add new `ingestSubtreeFullResult` (keeps the loop).
- `src/main/resources/static/js/app.js` — login path uses `getSubtreeFull` + `ingestSubtreeFullResult`.

**Docs — modified:**
- `itemtree-service-design.md` — §3 endpoint table, §3 response-shapes paragraph, §4 TreeCache interface code block, §9 lazy-path paragraph, §3 cache-served reads list.
- `IMPLEMENTATION_NOTES.md` — rename Phase 17 → Phase 18; insert new Phase 17 section.

**Memory — created (last task):**
- `~/.claude/projects/-home-dave-Git-item-tree/memory/project-phase17-subtree-level1-done.md`
- Append a one-line index entry to `~/.claude/projects/-home-dave-Git-item-tree/memory/MEMORY.md`.

---

## Task 1 — OpenAPI: rename existing operation, add new operation

**Files:**
- Modify: `src/main/resources/openapi/itemtree-api.yaml`

- [ ] **Step 1: Rename the existing `/subtree` operation and add the new `/subtree-full` block**

In `itemtree-api.yaml`, replace the entire `/api/v1/itemtree/tree/{rootId}/subtree:` block (lines ≈255–281) with:

```yaml
  /api/v1/itemtree/tree/{rootId}/subtree:
    get:
      tags: [tree]
      operationId: getSubtree
      summary: Get root + immediate children of rootId as a flat list with paths
      parameters:
        - name: rootId
          in: path
          required: true
          schema:
            type: integer
            format: int64
        - $ref: '#/components/parameters/XIceUser'
        - $ref: '#/components/parameters/XImpersonatedUser'
      responses:
        '200':
          description: Flat list of ItemNode (root first, then immediate children) each with path populated
          content:
            application/json:
              schema:
                type: array
                items:
                  $ref: '#/components/schemas/ItemNode'
        '404':
          $ref: '#/components/responses/NotFound'
        '503':
          $ref: '#/components/responses/ServiceUnavailable'

  /api/v1/itemtree/tree/{rootId}/subtree-full:
    get:
      tags: [tree]
      operationId: getSubtreeFull
      summary: Get the full recursive subtree rooted at rootId as a flat list with paths
      parameters:
        - name: rootId
          in: path
          required: true
          schema:
            type: integer
            format: int64
        - $ref: '#/components/parameters/XIceUser'
        - $ref: '#/components/parameters/XImpersonatedUser'
      responses:
        '200':
          description: Flat list of ItemNode (root + all descendants in BFS order) each with path populated
          content:
            application/json:
              schema:
                type: array
                items:
                  $ref: '#/components/schemas/ItemNode'
        '404':
          $ref: '#/components/responses/NotFound'
        '503':
          $ref: '#/components/responses/ServiceUnavailable'
```

- [ ] **Step 2: Confirm the OpenAPI generator picks up both operations**

Run: `./gradlew openApiGenerate`
Expected: BUILD SUCCESSFUL. Two methods now exist on the generated `TreeApi` interface — `getSubtree(...)` and `getSubtreeFull(...)`. Build will fail downstream until the controller catches up; that's expected.

- [ ] **Step 3: Do NOT commit yet**

This step's output (renamed + added operation) is committed together with the controller change in Task 5 — committing now would leave the build broken.

---

## Task 2 — Cache: rename `getSubtreeFlat` to `getSubtreeFlatFull`

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/cache/TreeCache.java`
- Modify: `src/main/java/com/myxcomp/ice/xtree/cache/DefaultTreeCache.java`
- Test: `src/test/java/com/myxcomp/ice/xtree/cache/DefaultTreeCacheTest.java`

- [ ] **Step 1: Update the existing four cache tests to the new method name (failing tests first)**

In `DefaultTreeCacheTest.java`, rename the following test methods and update the calls inside them. The tests currently appear inside a `@Nested class` around line 235 (search for `getSubtreeFlatIncludesRootAndAllDescendants`):

```java
@Test
void getSubtreeFlatFullIncludesRootAndAllDescendants() {
    List<CachedNode> subtree = cache.getSubtreeFlatFull(2L);
    assertThat(subtree).extracting(CachedNode::itemTreeId)
            .containsExactlyInAnyOrder(2L, 3L, 100L);
}

@Test
void getSubtreeFlatFullOnLeafReturnsOnlyThatNode() {
    List<CachedNode> subtree = cache.getSubtreeFlatFull(100L);
    assertThat(subtree).extracting(CachedNode::itemTreeId).containsExactly(100L);
}

@Test
void getSubtreeFlatFullOnMissingIdReturnsEmpty() {
    assertThat(cache.getSubtreeFlatFull(999L)).isEmpty();
}

@Test
void getSubtreeFlatFullResultIsUnmodifiable() {
    List<CachedNode> subtree = cache.getSubtreeFlatFull(1L);
    assertThatThrownBy(() -> subtree.add(folder(99L, 1L, "x")))
            .isInstanceOf(UnsupportedOperationException.class);
}
```

- [ ] **Step 2: Confirm tests fail to compile**

Run: `./gradlew compileTestJava`
Expected: FAIL with `cannot find symbol: method getSubtreeFlatFull` — proves the test rename is in place ahead of the implementation rename.

- [ ] **Step 3: Rename the cache interface method**

In `TreeCache.java`, change the line:

```java
    List<CachedNode>     getSubtreeFlat(long rootId);
```

to:

```java
    /**
     * Returns root + all descendants of the given node, in BFS order, as a defensive-copy flat list.
     * Returns an empty list if {@code rootId} is not in the cache.
     */
    List<CachedNode>     getSubtreeFlatFull(long rootId);
```

- [ ] **Step 4: Rename the implementation in DefaultTreeCache**

In `DefaultTreeCache.java`, change the method signature:

```java
    @Override
    public List<CachedNode> getSubtreeFlat(long rootId) {
```

to:

```java
    @Override
    public List<CachedNode> getSubtreeFlatFull(long rootId) {
```

Method body unchanged.

- [ ] **Step 5: Confirm all tests still compile but TreeService now fails**

Run: `./gradlew compileTestJava`
Expected: FAIL — `TreeService.java` and `TreeServiceTest.java` still reference `getSubtreeFlat`. Will be fixed in Task 3.

- [ ] **Step 6: Do NOT commit yet** — see Task 5 commit point.

---

## Task 3 — Service: rename `getSubtree` to `getSubtreeFull`; add new `getSubtree`

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/service/TreeService.java`
- Test: `src/test/java/com/myxcomp/ice/xtree/service/TreeServiceTest.java`
- Test: `src/test/java/com/myxcomp/ice/xtree/service/TreeServiceSubtreeNotFoundTest.java`

- [ ] **Step 1: Update existing TreeServiceTest tests to point at `getSubtreeFull` and `cache.getSubtreeFlatFull`**

In `TreeServiceTest.java`, replace the three existing subtree tests (currently at lines ≈88–126) with:

```java
@Test
void getSubtreeFullReturnsPairsForEveryNodeInSubtree() {
    CachedNode parent = folder(20L, 1L, "Group");
    CachedNode child  = folder(21L, 20L, "Sub");
    when(cache.getById(20L)).thenReturn(Optional.of(parent));
    when(cache.getSubtreeFlatFull(20L)).thenReturn(List.of(parent, child));
    when(pathResolver.pathsOf(List.of(20L, 21L))).thenReturn(Map.of(
            20L, "root/Group",
            21L, "root/Group/Sub"
    ));

    List<TreeNodeView> result = service.getSubtreeFull(20L);

    assertThat(result).containsExactly(
            new TreeNodeView(parent, "root/Group"),
            new TreeNodeView(child,  "root/Group/Sub")
    );
}

@Test
void getSubtreeFullThrowsNotFoundForUnknownRoot() {
    when(cache.getById(999L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getSubtreeFull(999L))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("999");
}

@Test
void getSubtreeFullFallsBackToEmptyStringPathWhenResolverOmitsId() {
    CachedNode node = folder(30L, 1L, "X");
    when(cache.getById(30L)).thenReturn(Optional.of(node));
    when(cache.getSubtreeFlatFull(30L)).thenReturn(List.of(node));
    when(pathResolver.pathsOf(anyCollection())).thenReturn(Map.of());

    List<TreeNodeView> result = service.getSubtreeFull(30L);

    assertThat(result).containsExactly(new TreeNodeView(node, ""));
}
```

- [ ] **Step 2: Add the new `GetSubtree` nested test class to TreeServiceTest**

Append (before the closing `}` of `class TreeServiceTest`):

```java
@org.junit.jupiter.api.Nested
class GetSubtree {

    @Test
    void returnsRootAndImmediateChildrenWithPaths() {
        CachedNode root = folder(50L, 1L, "Root");
        CachedNode c1   = folder(51L, 50L, "A");
        CachedNode c2   = folder(52L, 50L, "B");
        CachedNode c3   = folder(53L, 50L, "C");
        when(cache.getById(50L)).thenReturn(Optional.of(root));
        when(cache.getChildren(50L)).thenReturn(List.of(c1, c2, c3));
        when(pathResolver.pathsOf(List.of(50L, 51L, 52L, 53L))).thenReturn(Map.of(
                50L, "root/Root",
                51L, "root/Root/A",
                52L, "root/Root/B",
                53L, "root/Root/C"
        ));

        List<TreeNodeView> result = service.getSubtree(50L);

        assertThat(result).hasSize(4);
        assertThat(result.get(0)).isEqualTo(new TreeNodeView(root, "root/Root"));
        assertThat(result.subList(1, 4)).containsExactlyInAnyOrder(
                new TreeNodeView(c1, "root/Root/A"),
                new TreeNodeView(c2, "root/Root/B"),
                new TreeNodeView(c3, "root/Root/C")
        );
    }

    @Test
    void leafRootReturnsRootAlone() {
        CachedNode leaf = new CachedNode(60L, 1L, "rep", "Report", T, "sys");
        when(cache.getById(60L)).thenReturn(Optional.of(leaf));
        when(cache.getChildren(60L)).thenReturn(List.of());
        when(pathResolver.pathsOf(List.of(60L))).thenReturn(Map.of(60L, "root/rep"));

        List<TreeNodeView> result = service.getSubtree(60L);

        assertThat(result).containsExactly(new TreeNodeView(leaf, "root/rep"));
    }

    @Test
    void emptyFolderRootReturnsRootAlone() {
        CachedNode empty = folder(61L, 1L, "Empty");
        when(cache.getById(61L)).thenReturn(Optional.of(empty));
        when(cache.getChildren(61L)).thenReturn(List.of());
        when(pathResolver.pathsOf(List.of(61L))).thenReturn(Map.of(61L, "root/Empty"));

        List<TreeNodeView> result = service.getSubtree(61L);

        assertThat(result).containsExactly(new TreeNodeView(empty, "root/Empty"));
    }

    @Test
    void throwsNotFoundForUnknownRoot() {
        when(cache.getById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSubtree(999L))
                .isInstanceOf(NotFoundException.class)
                .satisfies(t -> assertThat(((NotFoundException) t).errorCode())
                        .isEqualTo(com.myxcomp.ice.xtree.service.exception.ErrorCode.ITEM_NOT_FOUND))
                .hasMessageContaining("999");
    }
}
```

- [ ] **Step 3: Update TreeServiceSubtreeNotFoundTest to call `getSubtreeFull` and `cache.getSubtreeFlatFull`**

In `TreeServiceSubtreeNotFoundTest.java`, change the two existing test method bodies:

```java
@Test
void throwsNotFoundWhenRootMissing() {
    when(cache.getById(99L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getSubtreeFull(99L))
            .isInstanceOf(NotFoundException.class)
            .satisfies(t -> assertThat(((NotFoundException) t).errorCode())
                    .isEqualTo(ErrorCode.ITEM_NOT_FOUND))
            .hasMessageContaining("99");
}

@Test
void returnsTheSubtreeWhenRootExists() {
    CachedNode root = new CachedNode(99L, 0L, "n", "Folder", Instant.EPOCH, "sys");
    when(cache.getById(99L)).thenReturn(Optional.of(root));
    when(cache.getSubtreeFlatFull(99L)).thenReturn(List.of(root));
    when(pathResolver.pathsOf(List.of(99L))).thenReturn(Map.of(99L, "root/n"));

    List<TreeNodeView> result = service.getSubtreeFull(99L);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).path()).isEqualTo("root/n");
}
```

- [ ] **Step 4: Confirm tests fail to compile**

Run: `./gradlew compileTestJava`
Expected: FAIL with `cannot find symbol: method getSubtreeFull` / `method getSubtree` (the new signature) on `TreeService`. This proves the test-driven step before implementation.

- [ ] **Step 5: Rename and add methods on TreeService**

In `TreeService.java`:

a) Rename the existing `getSubtree` method's signature and update the cache call:

```java
/**
 * Returns every node in the subtree rooted at {@code rootId} (root + all descendants
 * in BFS order), each paired with its path.
 *
 * @throws NotFoundException (ITEM_NOT_FOUND) when no item with {@code rootId} exists in the cache
 */
public List<TreeNodeView> getSubtreeFull(long rootId) {
    if (cache.getById(rootId).isEmpty()) {
        throw new NotFoundException(ErrorCode.ITEM_NOT_FOUND,
                "Item " + rootId + " not found");
    }
    List<CachedNode> nodes = cache.getSubtreeFlatFull(rootId);
    return pairWithPaths(nodes);
}
```

b) Add the new `getSubtree` method immediately above `pairWithPaths(...)`:

```java
/**
 * Returns the root node and its immediate children (level 1 only),
 * each paired with its path. Cheap by design — does not walk further.
 *
 * @throws NotFoundException (ITEM_NOT_FOUND) when no item with {@code rootId} exists in the cache
 */
public List<TreeNodeView> getSubtree(long rootId) {
    CachedNode root = cache.getById(rootId)
            .orElseThrow(() -> new NotFoundException(ErrorCode.ITEM_NOT_FOUND,
                    "Item " + rootId + " not found"));
    List<CachedNode> children = cache.getChildren(rootId);
    List<CachedNode> result = new ArrayList<>(children.size() + 1);
    result.add(root);
    result.addAll(children);
    return pairWithPaths(result);
}
```

- [ ] **Step 6: Confirm tests compile but controller still broken**

Run: `./gradlew compileTestJava`
Expected: FAIL — `TreeController.java` still implements the old generated `getSubtree(...)` signature that no longer matches the regenerated `TreeApi` interface (post-Task-1). Fixed in Task 4.

- [ ] **Step 7: Do NOT commit yet.**

---

## Task 4 — Controller: implement both generated methods

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/api/controller/TreeController.java`

- [ ] **Step 1: Replace the single `getSubtree(...)` method with both implementations**

Open `TreeController.java`. Replace the existing `getSubtree(...)` method (lines ≈32–36) with:

```java
@Override
public ResponseEntity<List<ItemNode>> getSubtree(Long rootId, String xIceUser, String xImpersonatedUser) {
    List<TreeNodeView> views = treeService.getSubtree(rootId);
    return ResponseEntity.ok(itemNodeMapper.toDtos(views));
}

@Override
public ResponseEntity<List<ItemNode>> getSubtreeFull(Long rootId, String xIceUser, String xImpersonatedUser) {
    List<TreeNodeView> views = treeService.getSubtreeFull(rootId);
    return ResponseEntity.ok(itemNodeMapper.toDtos(views));
}
```

- [ ] **Step 2: Update the TreeNodeView Javadoc**

In `src/main/java/com/myxcomp/ice/xtree/service/TreeNodeView.java`, update the class Javadoc:

```java
/**
 * Service-layer pairing of a node and its lazily-resolved path used by {@code /tree},
 * {@code /tree/{rootId}/subtree} (root + immediate children), and
 * {@code /tree/{rootId}/subtree-full} (root + all descendants). Phase 8 mappers
 * project this to the generated {@code ItemNode} DTO with the {@code path} field populated.
 */
```

- [ ] **Step 3: Compile and run all tests**

Run: `./gradlew compileJava compileTestJava`
Expected: BUILD SUCCESSFUL.

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL. All tests green. The pre-existing TreeControllerTest tests still pass: the URL `/api/v1/itemtree/tree/{rootId}/subtree` now routes to the new level-1 controller method, which calls `treeService.getSubtree(rootId)` — the same method name the old tests already mock — so the mocked stub continues to satisfy each test. The test names are stale (they say `getSubtree` but the URL semantics have flipped) — that is what Task 5 cleans up.

If any test fails, stop and diagnose. Do not paper over with `@Disabled`.

- [ ] **Step 4: Do NOT commit yet.**

---

## Task 5 — Controller tests: rename existing, add new `GetSubtree` nested class; commit Java-side work

**Files:**
- Test: `src/test/java/com/myxcomp/ice/xtree/api/controller/TreeControllerTest.java`

- [ ] **Step 1: Rename the three existing subtree tests to target `/subtree-full` and the new service method name**

In `TreeControllerTest.java`, replace the three existing methods (currently lines ≈98–127) with:

```java
@Test
void getSubtreeFullReturns200WithPaths() throws Exception {
    when(treeService.getSubtreeFull(7L)).thenReturn(List.of(
            view(7L, 0L, "root",  "Folder", "root"),
            view(8L, 7L, "child", "Folder", "root/child")));

    mvc.perform(get("/api/v1/itemtree/tree/7/subtree-full")
                    .header("X-Ice-User", "alice"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[1].path").value("root/child"));
}

@Test
void getSubtreeFullReturns404WhenRootMissing() throws Exception {
    when(treeService.getSubtreeFull(anyLong()))
            .thenThrow(new NotFoundException(ErrorCode.ITEM_NOT_FOUND, "Item 99 not found"));

    mvc.perform(get("/api/v1/itemtree/tree/99/subtree-full")
                    .header("X-Ice-User", "alice"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("ITEM_NOT_FOUND"));
}

@Test
void getSubtreeFullWithNonNumericRootReturns400() throws Exception {
    mvc.perform(get("/api/v1/itemtree/tree/abc/subtree-full")
                    .header("X-Ice-User", "alice"))
            .andExpect(status().isBadRequest());
}
```

- [ ] **Step 2: Add new nested class `GetSubtree` for the level-1 endpoint**

Append (before the closing `}` of `class TreeControllerTest`):

```java
@org.junit.jupiter.api.Nested
class GetSubtree {

    @Test
    void returns200WithRootAndChildren() throws Exception {
        when(treeService.getSubtree(5L)).thenReturn(List.of(
                view(5L, 1L, "Group",  "Folder", "root/Group"),
                view(6L, 5L, "ChildA", "Folder", "root/Group/ChildA"),
                view(7L, 5L, "ChildB", "Report", "root/Group/ChildB")));

        mvc.perform(get("/api/v1/itemtree/tree/5/subtree")
                        .header("X-Ice-User", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].itemTreeId").value(5))
                .andExpect(jsonPath("$[0].path").value("root/Group"))
                .andExpect(jsonPath("$[1].path").value("root/Group/ChildA"));
    }

    @Test
    void returns404WhenRootMissing() throws Exception {
        when(treeService.getSubtree(anyLong()))
                .thenThrow(new NotFoundException(ErrorCode.ITEM_NOT_FOUND, "Item 999 not found"));

        mvc.perform(get("/api/v1/itemtree/tree/999/subtree")
                        .header("X-Ice-User", "alice"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ITEM_NOT_FOUND"));
    }

    @Test
    void returns400OnNonNumericRoot() throws Exception {
        mvc.perform(get("/api/v1/itemtree/tree/abc/subtree")
                        .header("X-Ice-User", "alice"))
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 3: Run the full test suite**

Run: `./gradlew clean test`
Expected: BUILD SUCCESSFUL. Test count up by exactly 7 vs. the pre-Phase-17 baseline of 665 (4 new TreeServiceTest.GetSubtree + 3 new TreeControllerTest.GetSubtree).

If any test fails, stop and diagnose against the diff — do not paper over with `@Disabled`.

- [ ] **Step 4: Commit the Java-side work as one logical change**

Run:

```bash
git add src/main/resources/openapi/itemtree-api.yaml \
        src/main/java/com/myxcomp/ice/xtree/cache/TreeCache.java \
        src/main/java/com/myxcomp/ice/xtree/cache/DefaultTreeCache.java \
        src/main/java/com/myxcomp/ice/xtree/service/TreeService.java \
        src/main/java/com/myxcomp/ice/xtree/service/TreeNodeView.java \
        src/main/java/com/myxcomp/ice/xtree/api/controller/TreeController.java \
        src/test/java/com/myxcomp/ice/xtree/cache/DefaultTreeCacheTest.java \
        src/test/java/com/myxcomp/ice/xtree/service/TreeServiceTest.java \
        src/test/java/com/myxcomp/ice/xtree/service/TreeServiceSubtreeNotFoundTest.java \
        src/test/java/com/myxcomp/ice/xtree/api/controller/TreeControllerTest.java
```

```bash
git commit -m "$(cat <<'EOF'
feat(phase17): level-1 default for /subtree; recursive becomes /subtree-full

Splits the single recursive /tree/{rootId}/subtree endpoint into:
- /tree/{rootId}/subtree — root + immediate children (new fast default)
- /tree/{rootId}/subtree-full — root + all descendants (renamed, unchanged)

Internal rename: TreeCache.getSubtreeFlat -> getSubtreeFlatFull;
TreeService.getSubtree -> getSubtreeFull; new TreeService.getSubtree
composes getById + getChildren. No new error codes or metrics.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 6 — E2E test for the level-1 endpoint

**Files:**
- Test: `src/test/java/com/myxcomp/ice/xtree/e2e/ItemTreeApplicationE2EIT.java`

- [ ] **Step 1: Add the new E2E test**

Append the following test method to `ItemTreeApplicationE2EIT` (before the closing `}` of the class). Use the seed-data fixtures already in `data.sql`: `Users` (id=2) has children {testuser1=10, testuser2=11, deepuser=12}; `deepuser`'s subtree under id=12 includes `L2`=20, `L3`=21, and deeper descendants.

```java
@Test
void getSubtreeReturnsLevel1OnlyWhileSubtreeFullReturnsAllDescendants() {
    com.myxcomp.ice.xtree.service.TreeService treeService =
            pair.a().getBean(com.myxcomp.ice.xtree.service.TreeService.class);

    // Level-1 on Users (id=2) — should contain exactly Users + {testuser1, testuser2, deepuser}.
    List<com.myxcomp.ice.xtree.service.TreeNodeView> level1 = treeService.getSubtree(2L);
    assertThat(level1).extracting(v -> v.node().itemTreeId())
            .containsExactlyInAnyOrder(2L, 10L, 11L, 12L);

    // Level-1 on deepuser (id=12) — should contain only deepuser + L2 (id=20),
    // NOT L3 (21) or any further descendants in the depth-7 chain.
    List<com.myxcomp.ice.xtree.service.TreeNodeView> level1Deep = treeService.getSubtree(12L);
    assertThat(level1Deep).extracting(v -> v.node().itemTreeId())
            .containsExactlyInAnyOrder(12L, 20L);
    assertThat(level1Deep).extracting(v -> v.node().itemTreeId())
            .doesNotContain(21L);

    // Full recursive on deepuser — must still include the deeper chain (sanity for the rename).
    List<com.myxcomp.ice.xtree.service.TreeNodeView> full = treeService.getSubtreeFull(12L);
    assertThat(full).extracting(v -> v.node().itemTreeId())
            .contains(12L, 20L, 21L);
}
```

- [ ] **Step 2: Run the E2E suite**

Run: `./gradlew test --tests "com.myxcomp.ice.xtree.e2e.*"`
Expected: BUILD SUCCESSFUL with all E2E tests green, including the new one.

If it fails, diagnose — most likely cause is a seed-data drift in `data.sql` (e.g. someone added another child under Users between phases). Adjust the expected id set, not the assertion style.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/myxcomp/ice/xtree/e2e/ItemTreeApplicationE2EIT.java
git commit -m "$(cat <<'EOF'
test(phase17): E2E coverage for level-1 /subtree vs recursive /subtree-full

Asserts that getSubtree(Users) returns exactly Users + its three home folders,
that getSubtree(deepuser) excludes any node below L2 (proving the cut-off),
and that getSubtreeFull(deepuser) still reaches the depth-7 chain.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 7 — UI: split api.js helpers

**Files:**
- Modify: `src/main/resources/static/js/api.js`

- [ ] **Step 1: Replace the `getSubtree` line with two helpers**

In `api.js`, find the line:

```js
  getSubtree: (rootId) =>
    request('GET', `/api/v1/itemtree/tree/${rootId}/subtree`),
```

Replace with:

```js
  getSubtree: (rootId) =>
    request('GET', `/api/v1/itemtree/tree/${rootId}/subtree`),
  getSubtreeFull: (rootId) =>
    request('GET', `/api/v1/itemtree/tree/${rootId}/subtree-full`),
```

(`getSubtree` keeps the same URL; it now hits the new level-1 endpoint. `getSubtreeFull` is the new helper that targets the renamed recursive endpoint.)

- [ ] **Step 2: Do NOT commit yet — the UI is consistent only after Tasks 8 and 9.**

---

## Task 8 — UI: split `ingestSubtreeResult` into level-1 and full variants

**Files:**
- Modify: `src/main/resources/static/js/tree.js`

- [ ] **Step 1: Replace `ingestSubtreeResult` with two functions**

In `tree.js`, find the existing function (lines ≈104–110):

```js
export function ingestSubtreeResult(rootId, nodes) {
  ingestNodes(nodes);
  state.tree.loadedSubtreeOf.add(rootId);
  for (const n of nodes) {
    if (n.type === FOLDER) state.tree.loadedSubtreeOf.add(n.itemTreeId);
  }
}
```

Replace with:

```js
// Mark only the queried root as loaded — used after a level-1 fetch.
// Do NOT mark child folders as loaded: their children are not in the payload,
// so marking them would suppress later lazy-load fetches when expanded.
export function ingestSubtreeResult(rootId, nodes) {
  ingestNodes(nodes);
  state.tree.loadedSubtreeOf.add(rootId);
}

// Mark the queried root AND every folder in the payload as loaded — used after
// a recursive subtree-full fetch, where every folder's children are in the payload.
export function ingestSubtreeFullResult(rootId, nodes) {
  ingestNodes(nodes);
  state.tree.loadedSubtreeOf.add(rootId);
  for (const n of nodes) {
    if (n.type === FOLDER) state.tree.loadedSubtreeOf.add(n.itemTreeId);
  }
}
```

The existing `onChevronClick`, `refreshSubtree`, and `search.js:navigateTo` callers continue to invoke `ingestSubtreeResult` — they now work correctly with the level-1 payload because the function no longer marks child folders as pre-loaded.

- [ ] **Step 2: Do NOT commit yet.**

---

## Task 9 — UI: switch the login pre-load to use `subtree-full`

**Files:**
- Modify: `src/main/resources/static/js/app.js`

- [ ] **Step 1: Update the import**

At the top of `app.js`, replace the line:

```js
import { renderTree, ingestSubtreeResult } from './tree.js';
```

with:

```js
import { renderTree, ingestSubtreeFullResult } from './tree.js';
```

- [ ] **Step 2: Update the login body to use the full helper**

In the same file, lines ≈65–66 currently read:

```js
    const subtree = await api.getSubtree(home.itemTreeId);
    ingestSubtreeResult(home.itemTreeId, subtree);
```

Replace with:

```js
    const subtree = await api.getSubtreeFull(home.itemTreeId);
    ingestSubtreeFullResult(home.itemTreeId, subtree);
```

The downstream loop on lines ≈70–72 (`for (const n of subtree) { if (n.type === 'Folder') state.tree.expanded.add(n.itemTreeId); }`) is unchanged and still correct — it expands every folder we just loaded.

- [ ] **Step 3: Manual smoke test against the dev profile**

Run: `./gradlew bootRun` (in a separate terminal)

Then open `http://localhost:8080/` in a browser with DevTools → Network open.

Expected sequence at login (as `testuser1`):
1. `GET /api/v1/itemtree/users/testuser1/home-folder` — 200
2. `GET /api/v1/itemtree/tree` — 200
3. `GET /api/v1/itemtree/tree/10/subtree-full` — 200 (the home pre-load; payload contains everything under id=10)

Then collapse the home folder and expand a different folder (e.g. `Reports` at id=3):
- `GET /api/v1/itemtree/tree/3/subtree` — 200 (level-1 only; payload contains id=3 and its direct children only)

Then expand a grandchild folder of `Reports`:
- A second `GET /api/v1/itemtree/tree/{grandchildId}/subtree` — 200 (proves `loadedSubtreeOf` isn't suppressing the second fetch)

If any of the above does not happen as expected, stop and diagnose before committing.

- [ ] **Step 4: Stop bootRun and commit the UI work**

```bash
git add src/main/resources/static/js/api.js \
        src/main/resources/static/js/tree.js \
        src/main/resources/static/js/app.js
git commit -m "$(cat <<'EOF'
feat(phase17): static UI uses /subtree-full at login and level-1 /subtree elsewhere

api.js gains a getSubtreeFull helper alongside the existing getSubtree.
tree.js splits ingestSubtreeResult: the level-1 variant marks only the
queried root as loaded (so child folders still trigger a lazy fetch when
expanded); the new ingestSubtreeFullResult preserves the old "mark every
folder in payload" behaviour for the at-login full pre-load.
app.js login path swaps in the full helpers.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 10 — Update `itemtree-service-design.md`

**Files:**
- Modify: `itemtree-service-design.md`

- [ ] **Step 1: Update the §3 endpoint table**

In §3 (search for `| GET    | \`/tree/{rootId}/subtree\``), replace the single row:

```markdown
| GET    | `/tree/{rootId}/subtree`            | Subtree (flat)                           |
```

with:

```markdown
| GET    | `/tree/{rootId}/subtree`            | Root + immediate children (flat)         |
| GET    | `/tree/{rootId}/subtree-full`       | Full recursive subtree (flat)            |
```

- [ ] **Step 2: Update the §3 response-shapes paragraph**

Find the paragraph beginning:

```markdown
- **`/tree` and `/tree/{rootId}/subtree`** — flat list of `ItemNode`, each with `path` (root-anchored, slash-separated, e.g. `root/Folder1/IceReport`). `/tree` shape may be revisited later to switch to nested; flat is the contract for now.
```

Replace with:

```markdown
- **`/tree`, `/tree/{rootId}/subtree`, and `/tree/{rootId}/subtree-full`** — flat list of `ItemNode`, each with `path` (root-anchored, slash-separated, e.g. `root/Folder1/IceReport`). `/subtree` returns root + immediate children only; `/subtree-full` returns root + every descendant in BFS order. `/tree` shape may be revisited later to switch to nested; flat is the contract for all three for now.
```

- [ ] **Step 3: Update the §3 "Serve all read endpoints" sentence**

Find:

```markdown
Serve all read endpoints (`/tree`, `/tree/{rootId}/subtree`, `/search`, `/users/{userName}/home-folder`) entirely from memory.
```

Replace with:

```markdown
Serve all read endpoints (`/tree`, `/tree/{rootId}/subtree`, `/tree/{rootId}/subtree-full`, `/search`, `/users/{userName}/home-folder`) entirely from memory.
```

- [ ] **Step 4: Update the §4 TreeCache interface code block**

Find:

```java
    List<CachedNode>     getSubtreeFlat(long rootId);
```

Replace with:

```java
    List<CachedNode>     getSubtreeFlatFull(long rootId);
```

- [ ] **Step 5: Update the §9 lazy-compute paragraph**

Find the bullet that mentions `/tree` and `/tree/{rootId}/subtree`:

```markdown
**Lazy compute at response time** for `/tree` and `/tree/{rootId}/subtree`. Not stored on `CachedNode`.
```

Replace with:

```markdown
**Lazy compute at response time** for `/tree`, `/tree/{rootId}/subtree`, and `/tree/{rootId}/subtree-full`. Not stored on `CachedNode`.
```

- [ ] **Step 6: Update the §3 cached-endpoint per-row table (the "Yes/No" table)**

Find:

```markdown
| `/tree/{rootId}/subtree` | Yes |
```

Replace with:

```markdown
| `/tree/{rootId}/subtree` | Yes |
| `/tree/{rootId}/subtree-full` | Yes |
```

- [ ] **Step 7: Do NOT commit yet — combine with Task 11.**

---

## Task 11 — Update `IMPLEMENTATION_NOTES.md` (rename old Phase 17 → 18, add new Phase 17 section)

**Files:**
- Modify: `IMPLEMENTATION_NOTES.md`

- [ ] **Step 1: Rename the existing heading**

In `IMPLEMENTATION_NOTES.md`, find the line:

```markdown
## Phase 17 — Work PC wiring (Phase B, user-managed)
```

Replace with:

```markdown
## Phase 18 — Work PC wiring (Phase B, user-managed)
```

(Body unchanged.)

- [ ] **Step 2: Insert the new Phase 17 section immediately above the renamed heading**

Insert the following block immediately before the (newly renamed) `## Phase 18 — Work PC wiring (Phase B, user-managed)` line:

```markdown
## Phase 17 — Level-1 default for getSubtree; recursive becomes getSubtreeFull ✅ COMPLETE (2026-05-26)

**Goal:** Split the single recursive `/tree/{rootId}/subtree` endpoint into two — a fast level-1 default (`/subtree` = root + immediate children) and the preserved recursive `/subtree-full` (= root + all descendants). The test UI uses `/subtree-full` for the at-login pre-load of the user's home folder and `/subtree` for chevron-expand, post-mutation refresh, and search navigation.

Full design in `docs/superpowers/specs/2026-05-26-subtree-level1-design.md`; implementation plan in `docs/superpowers/plans/2026-05-26-phase17-subtree-level1.md`.

**Implementable end-to-end in Phase A.** No Phase B blockers.

### Surface

- **OpenAPI:** existing `operationId: getSubtree` renamed to `getSubtreeFull` (new path `/tree/{rootId}/subtree-full`); new `operationId: getSubtree` added at `/tree/{rootId}/subtree`. Same `ItemNode` response schema for both.
- **`TreeCache`:** `getSubtreeFlat(long)` renamed `getSubtreeFlatFull(long)`. No behavioural change.
- **`TreeService`:** existing `getSubtree(long)` renamed `getSubtreeFull(long)`; new `getSubtree(long)` composes `getById` + `getChildren`.
- **`TreeController`:** two methods, one per generated interface method.
- **Static UI:** `api.js` gains `getSubtreeFull`; `tree.js` splits `ingestSubtreeResult` into a level-1 variant (marks only the queried root as loaded) and a new `ingestSubtreeFullResult` (marks every folder in payload); `app.js` login flow uses `getSubtreeFull` + `ingestSubtreeFullResult`.

### Tests

- 7 new test executions: 4 in `TreeServiceTest.GetSubtree`, 3 in `TreeControllerTest.GetSubtree`.
- 1 new E2E test in `ItemTreeApplicationE2EIT`.
- 4 existing `DefaultTreeCacheTest` tests renamed to `getSubtreeFlatFull…`; 3 existing `TreeServiceTest` and 3 existing `TreeControllerTest` tests renamed/retargeted to `getSubtreeFull` and `/subtree-full`. 2 tests in `TreeServiceSubtreeNotFoundTest` retargeted.

### Done when

- 665 + 8 = 673 tests green; `./gradlew clean build` → BUILD SUCCESSFUL.
- Manual smoke against the dev profile: at login the network tab shows one `GET /tree` and one `GET /tree/{homeId}/subtree-full`; subsequent chevron expansions hit `/tree/{id}/subtree`.
- `itemtree-service-design.md` updated; old Phase 17 (Work PC wiring) renumbered to Phase 18.
- Memory note added: `project-phase17-subtree-level1-done.md`.

```

- [ ] **Step 3: Commit the docs update**

```bash
git add itemtree-service-design.md IMPLEMENTATION_NOTES.md
git commit -m "$(cat <<'EOF'
docs(phase17): update design doc; renumber prior Phase 17 to Phase 18

itemtree-service-design.md §3, §4, §9 reflect the new /subtree (level-1)
and renamed /subtree-full (recursive) endpoints.

IMPLEMENTATION_NOTES.md: previous "Phase 17 — Work PC wiring" becomes
"Phase 18 — Work PC wiring" (body unchanged); new Phase 17 section
records the subtree-split work.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 12 — Final verification + memory note

**Files:**
- Create: `~/.claude/projects/-home-dave-Git-item-tree/memory/project-phase17-subtree-level1-done.md`
- Modify: `~/.claude/projects/-home-dave-Git-item-tree/memory/MEMORY.md`

- [ ] **Step 1: Run the full build one more time from a clean state**

Run: `./gradlew clean build`
Expected: BUILD SUCCESSFUL. Print the test count line (`X tests completed, 0 failed`) — confirm `X == 673` (or 665 + however many you actually added if there is drift; verify against the plan's projection).

- [ ] **Step 2: Tag the phase**

```bash
git tag phase-17-subtree-level1
```

(Tagging only — do not push.)

- [ ] **Step 3: Write the memory note**

Create `~/.claude/projects/-home-dave-Git-item-tree/memory/project-phase17-subtree-level1-done.md`:

```markdown
---
name: project-phase17-subtree-level1-done
description: Phase 17 done as of 2026-05-26 — /subtree split into level-1 default and recursive /subtree-full; tagged phase-17-subtree-level1
metadata:
  type: project
---

Phase 17 — Level-1 default for `getSubtree`; recursive becomes `getSubtreeFull` — complete as of 2026-05-26.

**What shipped:**
- New `GET /api/v1/itemtree/tree/{rootId}/subtree` returns root + immediate children.
- Renamed `GET /api/v1/itemtree/tree/{rootId}/subtree-full` returns root + all descendants (was the old `/subtree`).
- `TreeCache.getSubtreeFlat` → `getSubtreeFlatFull`; `TreeService.getSubtree` → `getSubtreeFull`; new `TreeService.getSubtree`.
- Static test UI: `getSubtreeFull` used only for the at-login home-folder pre-load; everything else (chevron-expand, post-mutation refresh, search navigation) uses level-1 `getSubtree`.

**Why:** Full subtree fetch was the slow path noticed in the UI when many descendants were under the targeted folder. Level-1 default makes general browsing cheap; the full walk remains available for callers that genuinely need it (home pre-load, future search-jump pre-warm).

**How to apply:** New read endpoints? Default to the cheap-by-construction shape and add the heavy variant only when a caller asks for it. Two operations with clearly different latency/payload profiles → two URLs, not one URL with a default-valued depth param.

**Test count:** 673 (was 665). Tag: `phase-17-subtree-level1`.

**Phase numbering:** The previously-planned "Phase 17 — Work PC wiring" was renumbered to Phase 18 in `IMPLEMENTATION_NOTES.md`. See [[project-phase16-ownership-done]] for the prior phase.
```

- [ ] **Step 4: Append the index line to MEMORY.md**

In `~/.claude/projects/-home-dave-Git-item-tree/memory/MEMORY.md`, append after the existing `Phase 16` line:

```markdown
- [Phase 17 subtree level-1 complete](project-phase17-subtree-level1-done.md) — Phase 17 done as of 2026-05-26; /subtree split into level-1 default + /subtree-full; 673 tests; tagged phase-17-subtree-level1; old Phase 17 (Work PC wiring) renumbered to Phase 18
```

- [ ] **Step 5: No further commit is required for memory files** — they live outside the repo.

---

## Done state

- All tasks above ticked.
- Working tree clean; tag `phase-17-subtree-level1` points at the final commit.
- `./gradlew clean build` → BUILD SUCCESSFUL with ~673 tests green.
- Manual UI smoke walked through per Task 9 Step 3.
- Memory note + index updated.

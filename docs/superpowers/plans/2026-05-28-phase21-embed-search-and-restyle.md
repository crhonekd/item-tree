# Phase 21 — Embed search results in tree + test-UI restyle — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enrich `/search` to return each hit's `parentId` + root→parent `ancestors[]` so the test UI can embed any hit in the tree with no extra round-trips, add an "Embed results in tree" checkbox, make list-mode clicks reveal a single hit, and restyle the UI (warm-neutral & teal) without slowing tree rendering.

**Architecture:** Backend change is confined to the search slice (`PathResolver` gains `ancestorsOf`; `SearchHitView`, `SearchService`, `SearchHitMapper`, and the OpenAPI `SearchHit` schema carry ancestors). The frontend uses the ancestors already on each hit to materialize the tree path client-side. The restyle is CSS-only at the container level; the tree-render hot path stays untouched apart from one `search-match` class toggle.

**Tech Stack:** Java 21, Spring Boot 3.4, `JdbcClient`, openapi-generator 7.10 (spring, interfaceOnly), JUnit 5 + Mockito + AssertJ, H2 (Oracle mode). Static UI: vanilla ES modules, no build step.

**Spec:** `docs/superpowers/specs/2026-05-28-phase21-embed-search-and-restyle-design.md`

**Build/test commands:**
- One test class: `./gradlew test --tests 'com.myxcomp.ice.xtree.service.DefaultPathResolverTest'`
- Regenerate OpenAPI model (needed after editing the yaml): `./gradlew compileJava`
- Full verify: `./gradlew clean build`

---

## File Structure

**Backend (modified):**
- `src/main/resources/openapi/itemtree-api.yaml` — `SearchHit` gains `parentId` + `ancestors`.
- `src/main/java/com/myxcomp/ice/xtree/service/PathResolver.java` — add `ancestorsOf`.
- `src/main/java/com/myxcomp/ice/xtree/service/DefaultPathResolver.java` — implement via private `chainFor`.
- `src/main/java/com/myxcomp/ice/xtree/service/SearchHitView.java` — add `ancestors`.
- `src/main/java/com/myxcomp/ice/xtree/service/SearchService.java` — attach ancestors.
- `src/main/java/com/myxcomp/ice/xtree/api/mapper/SearchHitMapper.java` — inject `ItemNodeMapper`; map `parentId` + `ancestors`.

**Backend tests (modified):**
- `DefaultPathResolverTest`, `SearchServiceTest`, `SearchHitMapperTest`, `SearchControllerTest`, `ItemTreeApplicationE2EIT`.

**Frontend (modified):**
- `src/main/resources/static/index.html`, `styles.css`
- `src/main/resources/static/js/state.js`, `js/tree.js`, `js/search.js`, `js/app.js`
- `js/api.js` **unchanged**.

**Docs:** `itemtree-service-design.md` (§3, §9); `IMPLEMENTATION_NOTES.md` (Phase 21).

---

## Task 1: `PathResolver.ancestorsOf` + `DefaultPathResolver.chainFor`

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/service/PathResolver.java`
- Modify: `src/main/java/com/myxcomp/ice/xtree/service/DefaultPathResolver.java`
- Test: `src/test/java/com/myxcomp/ice/xtree/service/DefaultPathResolverTest.java`

- [ ] **Step 1: Write the failing tests** — append a new `@Nested` class inside `DefaultPathResolverTest` (it already has the `loadFixture`, `folder`, `leaf`, and `CountingTreeCache` helpers; reuse them):

```java
    @Nested
    class AncestorsOf {

        @Test
        void deepNodeReturnsRootToParentExclusiveInOrder() {
            loadFixture(cache);
            // 210 = DeepReport under root(1)/Users(2)/deepuser(12)/L2(20)/L3(21)/L4(22)
            List<CachedNode> ancestors = resolver.ancestorsOf(List.of(210L)).get(210L);

            assertThat(ancestors).extracting(CachedNode::itemTreeId)
                    .containsExactly(1L, 2L, 12L, 20L, 21L, 22L);
            assertThat(ancestors).extracting(CachedNode::name)
                    .containsExactly("root", "Users", "deepuser", "L2", "L3", "L4");
        }

        @Test
        void rootHasNoAncestors() {
            loadFixture(cache);
            assertThat(resolver.ancestorsOf(List.of(1L)).get(1L)).isEmpty();
        }

        @Test
        void directChildOfRootHasOnlyRoot() {
            loadFixture(cache);
            assertThat(resolver.ancestorsOf(List.of(2L)).get(2L))
                    .extracting(CachedNode::itemTreeId).containsExactly(1L);
        }

        @Test
        void unknownIdMapsToEmptyList() {
            loadFixture(cache);
            assertThat(resolver.ancestorsOf(List.of(999L)).get(999L)).isEmpty();
        }

        @Test
        void orphanParentMidChainYieldsEmptyAncestors() {
            // 50's parent (999) is missing — the walk cannot resolve any ancestor.
            cache.applyCreate(folder(50L, 999L, "OrphanA"));
            assertThat(resolver.ancestorsOf(List.of(50L)).get(50L)).isEmpty();
        }

        @Test
        void cycleTerminatesWithoutThrowing() {
            cache.applyCreate(folder(100L, 200L, "A"));
            cache.applyCreate(folder(200L, 100L, "B"));
            Map<Long, List<CachedNode>> result = resolver.ancestorsOf(List.of(100L, 200L));
            assertThat(result).containsOnlyKeys(100L, 200L);
        }

        @Test
        void emptyInputReturnsEmptyMap() {
            assertThat(resolver.ancestorsOf(List.of())).isEmpty();
        }

        @Test
        void nullInputReturnsEmptyMap() {
            assertThat(resolver.ancestorsOf(null)).isEmpty();
        }

        @Test
        void memoisationLimitsGetByIdCallsForSharedAncestorChain() {
            DefaultTreeCache realCache = new DefaultTreeCache();
            realCache.applyCreate(folder(1L, 0L, "root"));
            realCache.applyCreate(folder(2L, 1L, "A"));
            realCache.applyCreate(folder(3L, 2L, "B"));
            realCache.applyCreate(folder(4L, 3L, "C"));
            realCache.applyCreate(folder(5L, 4L, "D"));
            List<Long> leafIds = new java.util.ArrayList<>();
            for (long i = 100; i < 150; i++) {
                realCache.applyCreate(leaf(i, 5L, "leaf" + i));
                leafIds.add(i);
            }
            CountingTreeCache counting = new CountingTreeCache(realCache);
            DefaultPathResolver memoResolver = new DefaultPathResolver(counting);

            Map<Long, List<CachedNode>> result = memoResolver.ancestorsOf(leafIds);

            assertThat(result).hasSize(50);
            assertThat(result.get(100L)).extracting(CachedNode::name)
                    .containsExactly("root", "A", "B", "C", "D");
            assertThat(counting.getByIdCount())
                    .as("getById must be ~O(N + chain), not O(N * chain)")
                    .isLessThanOrEqualTo(60);
        }
    }
```

- [ ] **Step 2: Run the tests to verify they fail to compile** — Run: `./gradlew test --tests 'com.myxcomp.ice.xtree.service.DefaultPathResolverTest'`
Expected: compile failure — `ancestorsOf` is not defined on `PathResolver`.

- [ ] **Step 3: Add `ancestorsOf` to the `PathResolver` interface** — add these imports if missing (`java.util.List` is needed) and the method:

```java
import java.util.Collection;
import java.util.List;
import java.util.Map;
import com.myxcomp.ice.xtree.cache.CachedNode;

// ... inside the interface, after pathsOf:

    /**
     * Returns the ancestor chain root&rarr;parent (exclusive of the id itself) for each id,
     * ordered root-first. Memoised within a single call so a shared ancestor chain is walked
     * once. Unknown id &rarr; empty list. On a missing ancestor or a suspected cycle the walk
     * stops and the partial chain (without the true root prefix) is returned, mirroring
     * {@link #pathsOf}.
     */
    Map<Long, List<CachedNode>> ancestorsOf(Collection<Long> ids);
```

- [ ] **Step 4: Implement in `DefaultPathResolver`** — add the imports `java.util.List` (present), then add these two methods (leave `pathFor`/`pathsOf` untouched):

```java
    @Override
    public Map<Long, List<CachedNode>> ancestorsOf(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        Map<Long, List<CachedNode>> chainMemo = new HashMap<>(); // id -> root..id inclusive
        Map<Long, List<CachedNode>> result = new HashMap<>();
        for (Long id : ids) {
            if (id == null) continue;
            List<CachedNode> chain = chainFor(id, chainMemo);
            result.put(id, chain.size() <= 1
                    ? List.of()
                    : List.copyOf(chain.subList(0, chain.size() - 1)));
        }
        return result;
    }

    /**
     * Builds the root&rarr;node inclusive chain for {@code itemTreeId}, memoising every prefix in
     * {@code memo}. Mirrors {@link #pathFor}'s walk: same cap, same missing-ancestor / cycle WARN
     * logging, same partial-result behaviour. Unknown id &rarr; empty list.
     */
    private List<CachedNode> chainFor(long itemTreeId, Map<Long, List<CachedNode>> memo) {
        List<CachedNode> cached = memo.get(itemTreeId);
        if (cached != null) return cached;

        Optional<CachedNode> startOpt = cache.getById(itemTreeId);
        if (startOpt.isEmpty()) {
            memo.put(itemTreeId, List.of());
            return List.of();
        }

        List<CachedNode> leafFirst = new ArrayList<>();
        List<CachedNode> anchorChain = List.of();
        CachedNode cursor = startOpt.get();
        int steps = 0;
        while (cursor != null) {
            leafFirst.add(cursor);
            if (cursor.parentId() == TreeConstants.ROOT_PARENT_ID) {
                break;
            }
            List<CachedNode> memoParent = memo.get(cursor.parentId());
            if (memoParent != null) {
                anchorChain = memoParent;
                break;
            }
            if (++steps > MAX_TREE_DEPTH) {
                log.warn("PathResolver: ancestor walk cap reached at id={}, possible cycle", itemTreeId);
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

        List<CachedNode> rootFirst = new ArrayList<>(anchorChain);
        for (int i = leafFirst.size() - 1; i >= 0; i--) {
            rootFirst.add(leafFirst.get(i));
            memo.put(leafFirst.get(i).itemTreeId(), List.copyOf(rootFirst));
        }
        return memo.getOrDefault(itemTreeId, List.of());
    }
```

- [ ] **Step 5: Run the tests to verify they pass** — Run: `./gradlew test --tests 'com.myxcomp.ice.xtree.service.DefaultPathResolverTest'`
Expected: PASS (all `PathOf`, `PathsOf`, and new `AncestorsOf` tests green).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/service/PathResolver.java \
        src/main/java/com/myxcomp/ice/xtree/service/DefaultPathResolver.java \
        src/test/java/com/myxcomp/ice/xtree/service/DefaultPathResolverTest.java
git commit -m "feat(phase21): PathResolver.ancestorsOf returns root→parent chains"
```

---

## Task 2: `SearchHitView` carries ancestors; `SearchService` attaches them

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/service/SearchHitView.java`
- Modify: `src/main/java/com/myxcomp/ice/xtree/service/SearchService.java`
- Test: `src/test/java/com/myxcomp/ice/xtree/service/SearchServiceTest.java`
- Compile-fix (constructor arity): `src/test/java/com/myxcomp/ice/xtree/api/mapper/SearchHitMapperTest.java`, `src/test/java/com/myxcomp/ice/xtree/api/controller/SearchControllerTest.java`

> **Why the compile-fix files:** `SearchHitView` is changing from 2 to 3 components. Every `new SearchHitView(node, path)` in the codebase must become `new SearchHitView(node, path, List.of())` so the build still compiles. The mapper/controller *assertions* for ancestors come in Tasks 3–4; here we only keep them compiling.

- [ ] **Step 1: Change the record** — `SearchHitView.java`:

```java
package com.myxcomp.ice.xtree.service;

import com.myxcomp.ice.xtree.cache.CachedNode;

import java.util.List;

/**
 * Service-layer pairing of a search hit, its lazily-resolved path, and its root&rarr;parent
 * ancestor chain (exclusive of the hit). Lets {@link
 * com.myxcomp.ice.xtree.api.mapper.SearchHitMapper} project to the generated DTO so the UI can
 * embed the hit in the tree without extra round-trips.
 */
public record SearchHitView(CachedNode node, String path, List<CachedNode> ancestors) {}
```

- [ ] **Step 2: Write/adjust the failing tests** in `SearchServiceTest`:

First, update the two existing assertions that construct `SearchHitView` (they will otherwise not compile):
- line ~143: `new SearchHitView(bob, "/root/Users/bob/bobReport", List.of())`
- line ~154: `new SearchHitView(node, "/root/thing", List.of())`

Then add this test inside the `Search` nested class to prove ancestors are attached (note `node(id, name)` in this file sets `parentId = 1L`):

```java
        @Test
        void attachesAncestorsFromResolverToEachHit() {
            CachedNode hit = node(11L, "bobReport");
            CachedNode root = new CachedNode(1L, 0L, "root", "Folder", Instant.EPOCH, "sys");
            CachedNode users = new CachedNode(2L, 1L, "Users", "Folder", Instant.EPOCH, "sys");
            when(cache.searchByName("b", OptionalInt.empty())).thenReturn(List.of(hit));
            when(pathResolver.pathsOf(List.of(11L)))
                    .thenReturn(Map.of(11L, "/root/Users/bobReport"));
            when(pathResolver.ancestorsOf(List.of(11L)))
                    .thenReturn(Map.of(11L, List.of(root, users)));

            List<SearchHitView> hits = service.search("b", OptionalInt.empty());

            assertThat(hits).hasSize(1);
            assertThat(hits.get(0).ancestors()).containsExactly(root, users);
        }

        @Test
        void missingAncestorsEntryYieldsEmptyList() {
            CachedNode hit = node(5L, "thing");
            when(cache.searchById(5L)).thenReturn(Optional.of(hit));
            when(pathResolver.pathsOf(List.of(5L))).thenReturn(Map.of(5L, "/root/thing"));
            // ancestorsOf returns an empty map (Mockito default) — view must still get List.of()
            assertThat(service.search("5", OptionalInt.empty()).get(0).ancestors()).isEmpty();
        }
```

- [ ] **Step 3: Run to verify failure** — Run: `./gradlew test --tests 'com.myxcomp.ice.xtree.service.SearchServiceTest'`
Expected: compile failure or assertion failure — `SearchService` does not yet call `ancestorsOf`.

- [ ] **Step 4: Implement in `SearchService.search`** — after the existing `paths` line, add ancestors and pass them into the view. Replace the build loop:

```java
        if (hits.isEmpty()) return List.of();
        List<Long> ids = hits.stream().map(CachedNode::itemTreeId).toList();
        Map<Long, String> paths = pathResolver.pathsOf(ids);
        Map<Long, List<CachedNode>> ancestors = pathResolver.ancestorsOf(ids);
        List<SearchHitView> out = new ArrayList<>(hits.size());
        for (CachedNode n : hits) {
            out.add(new SearchHitView(
                    n,
                    paths.getOrDefault(n.itemTreeId(), ""),
                    ancestors.getOrDefault(n.itemTreeId(), List.of())));
        }
        return List.copyOf(out);
```

- [ ] **Step 5: Fix the other 2-arg `SearchHitView` constructions so the whole module compiles** — in `SearchHitMapperTest.java` change every `new SearchHitView(x, "...")` to `new SearchHitView(x, "...", List.of())` (5 occurrences across `mapsIdNameTypeAndPath`, `mapsListInOrder`, `toDtosMapsEachViewWithCorrectPath`); `java.util.List` is already imported. In `SearchControllerTest.java` change the 3 `new SearchHitView(node(...), "...")` to add `, List.of()` (`java.util.List` is already imported).

- [ ] **Step 6: Run to verify pass** — Run: `./gradlew test --tests 'com.myxcomp.ice.xtree.service.SearchServiceTest' --tests 'com.myxcomp.ice.xtree.api.mapper.SearchHitMapperTest' --tests 'com.myxcomp.ice.xtree.api.controller.SearchControllerTest'`
Expected: PASS (ancestors default to `List.of()` where unstubbed; new tests green).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/service/SearchHitView.java \
        src/main/java/com/myxcomp/ice/xtree/service/SearchService.java \
        src/test/java/com/myxcomp/ice/xtree/service/SearchServiceTest.java \
        src/test/java/com/myxcomp/ice/xtree/api/mapper/SearchHitMapperTest.java \
        src/test/java/com/myxcomp/ice/xtree/api/controller/SearchControllerTest.java
git commit -m "feat(phase21): SearchHitView + SearchService carry ancestor chains"
```

---

## Task 3: OpenAPI `SearchHit` schema + `SearchHitMapper` maps `parentId` + `ancestors`

**Files:**
- Modify: `src/main/resources/openapi/itemtree-api.yaml` (SearchHit schema, ~line 459)
- Modify: `src/main/java/com/myxcomp/ice/xtree/api/mapper/SearchHitMapper.java`
- Test: `src/test/java/com/myxcomp/ice/xtree/api/mapper/SearchHitMapperTest.java`
- Compile/context-fix: `src/test/java/com/myxcomp/ice/xtree/api/controller/SearchControllerTest.java` (`@Import`)

> **Generated-constructor note:** adding `parentId` + `ancestors` to `required` (in declaration order `itemTreeId, parentId, name, type, ancestors`) changes the generated all-required-args constructor to `new SearchHit(Long itemTreeId, Long parentId, String name, String type, List<ItemNode> ancestors)`. The mapper must switch to that constructor. `path` stays optional (`setPath`).

- [ ] **Step 1: Edit the OpenAPI `SearchHit` schema** — replace the existing block:

```yaml
    SearchHit:
      type: object
      required: [itemTreeId, parentId, name, type, ancestors]
      properties:
        itemTreeId:
          type: integer
          format: int64
        parentId:
          type: integer
          format: int64
          description: "Hit's parent id; 0 for the root's parent."
        name:
          type: string
        type:
          type: string
        path:
          type: string
          nullable: true
          description: "Root-anchored slash-separated path starting with a leading slash, e.g. /root/Folder1/IceReport."
        ancestors:
          type: array
          description: "Ancestor chain from root to this hit's parent (exclusive of the hit), ordered root-first; empty when the hit is the root. Each entry is a structural ItemNode (path left null). Lets the UI embed the hit in the tree without extra round-trips."
          items:
            $ref: '#/components/schemas/ItemNode'
```

- [ ] **Step 2: Regenerate + write the failing mapper test** — first regenerate so the new constructor exists: Run `./gradlew compileJava` (Expected: BUILD SUCCESSFUL; this will then break `SearchHitMapper` compilation in the next step's edit — that is expected). Then rewrite `SearchHitMapperTest.java`:

```java
package com.myxcomp.ice.xtree.api.mapper;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.common.TimeMapper;
import com.myxcomp.ice.xtree.generated.model.SearchHit;
import com.myxcomp.ice.xtree.service.SearchHitView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchHitMapperTest {

    private final SearchHitMapper mapper = new SearchHitMapper(new ItemNodeMapper(new TimeMapper()));

    private static CachedNode folder(long id, long parentId, String name) {
        return new CachedNode(id, parentId, name, "Folder", Instant.EPOCH, "sys");
    }

    @Test
    void mapsIdParentNameTypeAndPath() {
        CachedNode node = new CachedNode(42L, 7L, "Report-1", "Report", Instant.EPOCH, "alice");
        SearchHit hit = mapper.toDto(new SearchHitView(node, "/root/thing", List.of()));

        assertThat(hit.getItemTreeId()).isEqualTo(42L);
        assertThat(hit.getParentId()).isEqualTo(7L);
        assertThat(hit.getName()).isEqualTo("Report-1");
        assertThat(hit.getType()).isEqualTo("Report");
        assertThat(hit.getPath()).isEqualTo("/root/thing");
    }

    @Test
    void mapsAncestorsInRootFirstOrderWithNullPath() {
        CachedNode root = folder(1L, 0L, "root");
        CachedNode users = folder(2L, 1L, "Users");
        CachedNode hit = new CachedNode(42L, 2L, "Report-1", "Report", Instant.EPOCH, "alice");

        SearchHit dto = mapper.toDto(new SearchHitView(hit, "/root/Users/Report-1", List.of(root, users)));

        assertThat(dto.getAncestors()).extracting("itemTreeId").containsExactly(1L, 2L);
        assertThat(dto.getAncestors()).extracting("name").containsExactly("root", "Users");
        assertThat(dto.getAncestors().get(0).getPath()).isNull();
    }

    @Test
    void emptyAncestorsMapToEmptyList() {
        CachedNode root = folder(1L, 0L, "root");
        SearchHit dto = mapper.toDto(new SearchHitView(root, "/root", List.of()));
        assertThat(dto.getAncestors()).isEmpty();
    }

    @Test
    void mapsListInOrder() {
        CachedNode a = folder(1L, 0L, "root");
        CachedNode b = folder(2L, 1L, "Users");
        List<SearchHit> hits = mapper.toDtos(List.of(
                new SearchHitView(a, "/root", List.of()),
                new SearchHitView(b, "/root/Users", List.of(a))));
        assertThat(hits).extracting(SearchHit::getItemTreeId).containsExactly(1L, 2L);
    }
}
```

- [ ] **Step 3: Run to verify failure** — Run: `./gradlew test --tests 'com.myxcomp.ice.xtree.api.mapper.SearchHitMapperTest'`
Expected: compile failure — `SearchHitMapper` still has the no-arg constructor and old `new SearchHit(...)` call.

- [ ] **Step 4: Implement the mapper** — rewrite `SearchHitMapper.java`:

```java
package com.myxcomp.ice.xtree.api.mapper;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.generated.model.ItemNode;
import com.myxcomp.ice.xtree.generated.model.SearchHit;
import com.myxcomp.ice.xtree.service.SearchHitView;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class SearchHitMapper {

    private final ItemNodeMapper itemNodeMapper;

    public SearchHitMapper(ItemNodeMapper itemNodeMapper) {
        this.itemNodeMapper = itemNodeMapper;
    }

    public SearchHit toDto(SearchHitView view) {
        CachedNode node = view.node();
        List<ItemNode> ancestors = view.ancestors().stream()
                .map(itemNodeMapper::toDto)
                .toList();
        SearchHit dto = new SearchHit(
                node.itemTreeId(), node.parentId(), node.name(), node.type(), ancestors);
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

- [ ] **Step 5: Keep `SearchControllerTest` context loading** — `SearchHitMapper` now needs an `ItemNodeMapper` bean (which needs `TimeMapper`). In `SearchControllerTest.java`, change the `@Import` to include them:

```java
import com.myxcomp.ice.xtree.api.mapper.ItemNodeMapper;
import com.myxcomp.ice.xtree.common.TimeMapper;
// ...
@Import({GlobalExceptionHandler.class, ProblemFactory.class,
         SearchHitMapper.class, ItemNodeMapper.class, TimeMapper.class})
```

- [ ] **Step 6: Run to verify pass** — Run: `./gradlew test --tests 'com.myxcomp.ice.xtree.api.mapper.SearchHitMapperTest' --tests 'com.myxcomp.ice.xtree.api.controller.SearchControllerTest'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/openapi/itemtree-api.yaml \
        src/main/java/com/myxcomp/ice/xtree/api/mapper/SearchHitMapper.java \
        src/test/java/com/myxcomp/ice/xtree/api/mapper/SearchHitMapperTest.java \
        src/test/java/com/myxcomp/ice/xtree/api/controller/SearchControllerTest.java
git commit -m "feat(phase21): SearchHit DTO gains parentId + ancestors; mapper wired"
```

---

## Task 4: Controller-level assertions for `parentId` + `ancestors` in the JSON

**Files:**
- Test: `src/test/java/com/myxcomp/ice/xtree/api/controller/SearchControllerTest.java`

- [ ] **Step 1: Write the failing test** — add to `SearchControllerTest` (uses the existing `node(id, name, type)` helper which sets `parentId = 0L`; build a hit with a real parent + ancestors):

```java
    @Test
    void responseCarriesParentIdAndAncestors() throws Exception {
        CachedNode root = new CachedNode(1L, 0L, "root", "Folder", Instant.EPOCH, "sys");
        CachedNode users = new CachedNode(2L, 1L, "Users", "Folder", Instant.EPOCH, "sys");
        CachedNode hit = new CachedNode(42L, 2L, "Report-1", "Report", Instant.EPOCH, "alice");
        when(searchService.search(eq("Report-1"), any(OptionalInt.class)))
                .thenReturn(List.of(new SearchHitView(hit, "/root/Users/Report-1",
                        List.of(root, users))));

        mvc.perform(get("/api/v1/itemtree/search?q=Report-1")
                        .header("X-Ice-User", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].itemTreeId").value(42))
                .andExpect(jsonPath("$[0].parentId").value(2))
                .andExpect(jsonPath("$[0].ancestors.length()").value(2))
                .andExpect(jsonPath("$[0].ancestors[0].itemTreeId").value(1))
                .andExpect(jsonPath("$[0].ancestors[0].name").value("root"))
                .andExpect(jsonPath("$[0].ancestors[1].itemTreeId").value(2));
    }
```

- [ ] **Step 2: Run to verify pass** — Run: `./gradlew test --tests 'com.myxcomp.ice.xtree.api.controller.SearchControllerTest'`
Expected: PASS (mapper from Task 3 already produces these fields).

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/myxcomp/ice/xtree/api/controller/SearchControllerTest.java
git commit -m "test(phase21): controller asserts parentId + ancestors in /search JSON"
```

---

## Task 5: E2E roundtrip — `/search` carries the ancestor chain for a deep hit

**Files:**
- Test: `src/test/java/com/myxcomp/ice/xtree/e2e/ItemTreeApplicationE2EIT.java`

- [ ] **Step 1: Write the failing test** — add this method to `ItemTreeApplicationE2EIT` (it uses the dev `data.sql` tree: `leafItem` id 25, parent 24, chain root(1)/Users(2)/deepuser(12)/L2(20)/L3(21)/L4(22)/L5(23)/L6(24)). Add the imports `com.myxcomp.ice.xtree.service.SearchService`, `com.myxcomp.ice.xtree.service.SearchHitView`, `java.util.OptionalInt`:

```java
    @Test
    void searchCarriesRootToParentAncestorsForDeepHit() {
        SearchService searchService = pair.a().getBean(SearchService.class);

        List<SearchHitView> hits = searchService.search("leafItem", OptionalInt.empty());

        assertThat(hits).hasSize(1);
        SearchHitView hit = hits.get(0);
        assertThat(hit.node().itemTreeId()).isEqualTo(25L);
        assertThat(hit.node().parentId()).isEqualTo(24L);
        assertThat(hit.ancestors()).extracting(CachedNode::itemTreeId)
                .containsExactly(1L, 2L, 12L, 20L, 21L, 22L, 23L, 24L);
        assertThat(hit.ancestors()).extracting(CachedNode::name)
                .containsExactly("root", "Users", "deepuser", "L2", "L3", "L4", "L5", "L6");
    }
```

- [ ] **Step 2: Run to verify pass** — Run: `./gradlew test --tests 'com.myxcomp.ice.xtree.e2e.ItemTreeApplicationE2EIT'`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/myxcomp/ice/xtree/e2e/ItemTreeApplicationE2EIT.java
git commit -m "test(phase21): e2e confirms /search ancestor chain for deep hit"
```

---

## Task 6: Full backend verify + design-doc updates

**Files:**
- Modify: `itemtree-service-design.md` (§3 search response, §9 path/ancestor note)

- [ ] **Step 1: Run the full suite** — Run: `./gradlew clean build`
Expected: BUILD SUCCESSFUL, all tests green. If any pre-existing test constructed a 2-arg `SearchHitView` outside the files touched above, fix it to add `, List.of()` and re-run.

- [ ] **Step 2: Update `itemtree-service-design.md` §3** — in the search-endpoint / `SearchHit` schema description, document that each hit now also returns `parentId` and `ancestors` (root→parent, exclusive of the hit, each a structural node with `path` null), enabling the UI to embed a hit in the tree without extra calls.

- [ ] **Step 3: Update `itemtree-service-design.md` §9** — add one line noting `PathResolver.ancestorsOf(ids)` shares the §9 bounded-walk / cycle / missing-ancestor semantics with `pathsOf`, returning the ancestor node chain instead of the path string.

- [ ] **Step 4: Commit**

```bash
git add itemtree-service-design.md
git commit -m "docs(phase21): design §3 + §9 document search ancestors"
```

---

## Task 7: Frontend state — persist `embedInTree`, add `search.matchIds`

**Files:**
- Modify: `src/main/resources/static/js/state.js`

> No automated tests for the static UI (Phase 15 precedent — manual harness). Verification is the manual smoke in Task 13.

- [ ] **Step 1: Edit `state.js`** — (a) read `embedInTree` from persisted; (b) add `embedInTree` + `search` to `state`; (c) persist `embedInTree`; (d) clear `matchIds` in `resetTreeState`:

In the `state` object add:
```js
  embedInTree: persisted.embedInTree ?? false,
  search: { matchIds: new Set() },
```
In `savePersisted`'s object literal add:
```js
    embedInTree: state.embedInTree,
```
In `resetTreeState` add (with the other tree clears):
```js
  state.search.matchIds.clear();
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/static/js/state.js
git commit -m "feat(phase21): UI state persists embedInTree + tracks search matches"
```

---

## Task 8: Frontend tree — export `selectAndLoad`/`scrollToNode`, add `search-match` class

**Files:**
- Modify: `src/main/resources/static/js/tree.js`

- [ ] **Step 1: Add the `search-match` class in `renderNode`** — change the `row.className` line (currently `'tree-row' + (isSelected ? ' selected' : '') + (isCut ? ' cut' : '')`). First add near the other flags:
```js
  const isMatch = state.search.matchIds.has(id);
```
then:
```js
  row.className = 'tree-row'
    + (isSelected ? ' selected' : '')
    + (isMatch ? ' search-match' : '')
    + (isCut ? ' cut' : '');
```

- [ ] **Step 2: Rename `onNameClick` to an exported `selectAndLoad`** — change the declaration `async function onNameClick(id) {` to `export async function selectAndLoad(id) {` (body unchanged), and update the label binding inside `renderNode` from `label.addEventListener('click', () => onNameClick(id));` to `label.addEventListener('click', () => selectAndLoad(id));`.

- [ ] **Step 3: Add an exported `scrollToNode` helper** — append:
```js
export function scrollToNode(id) {
  const row = document.querySelector(`.tree-node[data-id="${id}"] .tree-row`);
  row?.scrollIntoView({ block: 'center', behavior: 'smooth' });
}
```

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/static/js/tree.js
git commit -m "feat(phase21): tree exports selectAndLoad/scrollToNode; renders search-match"
```

---

## Task 9: Frontend search — embed branch, single-hit reveal, clear

**Files:**
- Modify (full rewrite): `src/main/resources/static/js/search.js`

- [ ] **Step 1: Rewrite `search.js`**:

```js
import { state, ingestNodes } from './state.js';
import { api, ProblemError } from './api.js';
import { toastError } from './toast.js';
import { renderTree, selectAndLoad, scrollToNode } from './tree.js';

const $ = (id) => document.getElementById(id);

function hitNode(hit) {
  return { itemTreeId: hit.itemTreeId, parentId: hit.parentId, name: hit.name, type: hit.type };
}

// Materialize a hit's location in the tree from the ancestors carried on the hit
// itself (no extra backend call): ingest root→hit and expand the ancestor chain.
function ingestHit(hit) {
  const ancestors = hit.ancestors || [];
  ingestNodes([...ancestors, hitNode(hit)]);
  for (const a of ancestors) state.tree.expanded.add(a.itemTreeId);
}

function setClearVisible(visible) {
  const btn = $('search-clear-btn');
  if (btn) btn.hidden = !visible;
}

export async function runSearch() {
  const q = $('search-input').value.trim();
  const limit = $('search-limit').value.trim() || undefined;
  const results = $('search-results');
  const status = $('search-status');
  const embed = $('embed-in-tree').checked;

  results.innerHTML = '';
  state.search.matchIds.clear();
  status.textContent = '';
  setClearVisible(false);
  if (!q) { renderTree(); return; }

  let hits;
  try {
    hits = await api.search({ q, limit });
  } catch (e) {
    if (e instanceof ProblemError) toastError(e.problem); else toastError(String(e));
    return;
  }

  if (!hits || hits.length === 0) {
    if (embed) { status.textContent = '(no results)'; renderTree(); }
    else { results.innerHTML = '<li>(no results)</li>'; }
    return;
  }

  if (embed) {
    for (const hit of hits) {
      ingestHit(hit);
      state.search.matchIds.add(hit.itemTreeId);
    }
    status.textContent = `${hits.length} match${hits.length === 1 ? '' : 'es'}`;
    setClearVisible(true);
    renderTree();
    scrollToNode(hits[0].itemTreeId);
  } else {
    for (const hit of hits) {
      const li = document.createElement('li');
      li.textContent = `${hit.itemTreeId}  ${hit.type}  ${hit.name}`;
      li.addEventListener('click', () => revealHit(hit));
      results.appendChild(li);
    }
  }
}

// List-mode click: reveal exactly this hit in the tree, select it, load its
// detail from the backend, and scroll to it.
async function revealHit(hit) {
  ingestHit(hit);
  await selectAndLoad(hit.itemTreeId);
  scrollToNode(hit.itemTreeId);
}

export function clearSearchHighlight() {
  state.search.matchIds.clear();
  $('search-status').textContent = '';
  setClearVisible(false);
  renderTree();
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/static/js/search.js
git commit -m "feat(phase21): embed-in-tree search branch + single-hit reveal + clear"
```

---

## Task 10: Frontend markup — embed checkbox, clear button, status

**Files:**
- Modify: `src/main/resources/static/index.html`

- [ ] **Step 1: Edit the `.search-bar` section** — replace it with:

```html
  <section class="search-bar">
    <label>Search:
      <input id="search-input" type="text" placeholder="id or name…">
    </label>
    <label>limit:
      <input id="search-limit" type="number" min="1" value="50" style="width:5em">
    </label>
    <button id="search-btn" type="button">Search</button>
    <label class="embed-toggle">
      <input id="embed-in-tree" type="checkbox"> Embed results in tree
    </label>
    <button id="search-clear-btn" type="button" hidden>Clear</button>
    <span id="search-status" class="search-status"></span>
    <ul id="search-results" class="search-results"></ul>
  </section>
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/static/index.html
git commit -m "feat(phase21): search bar adds embed toggle, clear, status"
```

---

## Task 11: Frontend wiring — bind embed checkbox + clear button

**Files:**
- Modify: `src/main/resources/static/js/app.js`

- [ ] **Step 1: Update the import** — change the search import to also pull in the clear handler:
```js
import { runSearch, clearSearchHighlight } from './search.js';
```

- [ ] **Step 2: Extend `bindSearch`** — replace the function body with:
```js
function bindSearch() {
  $('search-btn').addEventListener('click', runSearch);
  $('search-input').addEventListener('keydown', (e) => { if (e.key === 'Enter') runSearch(); });

  const embed = $('embed-in-tree');
  embed.checked = state.embedInTree;
  embed.addEventListener('change', (e) => { state.embedInTree = e.target.checked; savePersisted(); });

  $('search-clear-btn').addEventListener('click', clearSearchHighlight);
}
```
(`$`, `state`, and `savePersisted` are already imported in `app.js`.)

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/js/app.js
git commit -m "feat(phase21): bind embed checkbox (persisted) + clear button"
```

---

## Task 12: Frontend restyle — warm neutral & teal

**Files:**
- Modify (full rewrite): `src/main/resources/static/styles.css`

- [ ] **Step 1: Rewrite `styles.css`** — palette via CSS variables; cards/shadows only on header + panes; tree rows use `background-color` only (no shadow/transition/filter):

```css
:root {
  --surface: #fffefb;
  --page-bg: #f5f3ee;
  --text: #3a3631;
  --muted: #7a736a;
  --border: #e7e2d8;
  --accent: #0d9488;
  --accent-hover: #0f766e;
  --selected: #ccfbf1;
  --match-bg: #fef3c7;
  --match-bar: #f59e0b;
  --radius: 6px;
  --shadow: 0 1px 3px rgba(58, 54, 49, 0.10);
}

* { box-sizing: border-box; }
html, body {
  margin: 0; padding: 0;
  font: 13px/1.5 -apple-system, "Segoe UI", system-ui, sans-serif;
  color: var(--text); background: var(--page-bg);
}

button {
  font: inherit; cursor: pointer;
  background: var(--surface); color: var(--text);
  border: 1px solid var(--border); border-radius: var(--radius);
  padding: 4px 12px;
}
button:hover { border-color: var(--accent); color: var(--accent); }
#search-btn, #login-btn {
  background: var(--accent); color: #fff; border-color: var(--accent);
}
#search-btn:hover, #login-btn:hover { background: var(--accent-hover); border-color: var(--accent-hover); color: #fff; }
input[type=text], input[type=number] {
  font: inherit; padding: 3px 6px;
  border: 1px solid var(--border); border-radius: var(--radius);
  background: var(--surface); color: var(--text);
}
input[type=text]:focus, input[type=number]:focus {
  outline: none; border-color: var(--accent);
  box-shadow: 0 0 0 2px var(--selected);
}

.app-header {
  position: sticky; top: 0; z-index: 50;
  display: flex; flex-direction: column; gap: 6px;
  padding: 8px 14px; background: var(--surface);
  border-bottom: 1px solid var(--border); box-shadow: var(--shadow);
}
.app-header .brand { font-weight: 700; font-size: 15px; color: var(--accent); letter-spacing: .2px; }
.header-controls { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.header-controls label { color: var(--muted); }
.probe-result { margin-left: 8px; font-family: monospace; }
.probe-result.ok { color: var(--accent); }
.probe-result.err { color: #b00020; }

.search-bar {
  display: flex; gap: 10px; align-items: center; flex-wrap: wrap;
  padding: 8px 14px; background: var(--surface); border-bottom: 1px solid var(--border);
}
.search-bar label { color: var(--muted); }
.embed-toggle { display: inline-flex; align-items: center; gap: 4px; color: var(--text); }
.search-status { font-size: 12px; color: var(--muted); font-family: monospace; }
.search-results { list-style: none; margin: 4px 0 0; padding: 0; max-height: 140px; overflow: auto; width: 100%; }
.search-results li { padding: 3px 8px; cursor: pointer; font-family: monospace; border-radius: 4px; }
.search-results li:hover { background: var(--selected); }

.main-grid {
  display: grid; grid-template-columns: 340px 1fr; gap: 12px;
  padding: 12px; height: calc(100vh - 132px); overflow: hidden;
}
.tree-pane, .detail-pane {
  padding: 12px 14px; overflow: auto;
  background: var(--surface); border: 1px solid var(--border);
  border-radius: var(--radius); box-shadow: var(--shadow);
}
.tree-pane { display: flex; flex-direction: column; }
.tree-pane h2, .detail-pane h2 {
  margin: 0 0 8px 0; font-size: 11px; font-weight: 700;
  text-transform: uppercase; letter-spacing: .6px; color: var(--muted);
}

.tree-root, .tree-children { list-style: none; margin: 0; padding: 0; }
.tree-node { user-select: none; }
.tree-children { padding-left: 0; }
/* PERF: tree rows use background-color only — no shadow/transition/filter. */
.tree-row { display: flex; align-items: center; gap: 5px; padding: 2px 4px; cursor: default; border-radius: 4px; }
.tree-row:hover { background: var(--page-bg); }
.tree-row.selected { background: var(--selected); }
.tree-row.search-match { background: var(--match-bg); box-shadow: inset 3px 0 0 var(--match-bar); }
.tree-row.cut { opacity: 0.5; }
.tree-chevron { display: inline-block; width: 12px; cursor: pointer; color: var(--accent); }
.tree-icon { display: inline-block; width: 12px; color: var(--muted); }
.tree-label { cursor: pointer; }

.refresh-strip { margin-top: auto; padding-top: 10px; border-top: 1px solid var(--border); }
.refresh-strip button { margin-right: 6px; }
.refresh-status { font-size: 11px; color: var(--muted); margin-top: 6px; font-family: monospace; }

.detail-pane pre { background: var(--page-bg); border: 1px solid var(--border); border-radius: var(--radius); padding: 10px; overflow: auto; max-height: 60vh; }
.detail-pane table { border-collapse: collapse; }
.detail-pane th, .detail-pane td { border: 1px solid var(--border); padding: 3px 8px; text-align: left; font-family: monospace; }
.metadata { display: grid; grid-template-columns: max-content 1fr; gap: 3px 14px; font-family: monospace; margin-bottom: 10px; }
.metadata > .k { color: var(--muted); }
.detail-section-head { display: flex; align-items: center; gap: 8px; margin: 10px 0 6px; }
.detail-section-head button { font-size: 11px; }

.context-menu {
  position: fixed; background: var(--surface); border: 1px solid var(--border);
  border-radius: var(--radius); box-shadow: 0 4px 12px rgba(58,54,49,0.18);
  list-style: none; margin: 0; padding: 4px 0; min-width: 180px; z-index: 100;
}
.context-menu li { padding: 5px 14px; cursor: pointer; }
.context-menu li:hover:not(.disabled) { background: var(--selected); }
.context-menu li.disabled { color: var(--muted); cursor: default; opacity: .6; }
.context-menu li.context-menu-separator { border-bottom: 1px solid var(--border); padding-bottom: 6px; margin-bottom: 4px; }
.context-menu li.context-menu-separator-before { border-top: 1px solid var(--border); padding-top: 6px; margin-top: 4px; }

.modal-backdrop {
  position: fixed; inset: 0; background: rgba(58,54,49,0.35); z-index: 200;
  display: flex; align-items: center; justify-content: center;
}
.modal {
  background: var(--surface); padding: 18px; border-radius: var(--radius);
  min-width: 380px; max-width: 90vw; box-shadow: 0 8px 28px rgba(58,54,49,0.30);
}
.modal h3 { margin-top: 0; color: var(--accent); }
.modal label { display: block; margin: 10px 0 3px; font-weight: 600; }
.modal input[type=text], .modal select { width: 100%; padding: 5px; }
.modal textarea { width: 100%; min-height: 200px; font-family: monospace; padding: 6px; border: 1px solid var(--border); border-radius: var(--radius); }
.modal-actions { margin-top: 14px; display: flex; justify-content: flex-end; gap: 8px; }
.modal-error { color: #b00020; margin-top: 4px; min-height: 1em; }

.toast-container {
  position: fixed; bottom: 14px; right: 14px; z-index: 300;
  display: flex; flex-direction: column; gap: 8px; max-width: 480px;
}
.toast {
  background: var(--text); color: #fff; padding: 9px 13px; border-radius: var(--radius);
  box-shadow: 0 3px 10px rgba(58,54,49,0.30); font-family: monospace; font-size: 12px;
}
.toast.success { background: var(--accent); }
.toast.error { background: #8a1422; }
.toast .toast-close { float: right; cursor: pointer; margin-left: 8px; }
.toast details { margin-top: 4px; }
.toast pre { background: rgba(0,0,0,0.2); color: inherit; border: none; padding: 4px; max-height: 200px; overflow: auto; font-size: 11px; }
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/static/styles.css
git commit -m "feat(phase21): warm-neutral & teal restyle (perf-safe tree rows)"
```

---

## Task 13: Manual smoke + IMPLEMENTATION_NOTES + final verify

**Files:**
- Modify: `IMPLEMENTATION_NOTES.md`

- [ ] **Step 1: Full build** — Run: `./gradlew clean build`
Expected: BUILD SUCCESSFUL, all tests green.

- [ ] **Step 2: Run the app on the dev profile and smoke-test** — Run: `./gradlew bootRun` (default profile is `dev`), open `http://localhost:8080/`, then verify:
  1. **List mode (checkbox off):** search `leaf` → hit list appears on top. Click `leafItem` (id 25, deep under `deepuser`, outside `testuser1`'s home) → the tree expands to reveal it, it becomes selected, its detail renders in the right pane, and the view scrolls to it.
  2. **Embed mode (checkbox on):** search `L` (or another term matching several nodes) → list is hidden, status shows `"N matches"`, every match is revealed in place with the amber highlight + left bar, view scrolls to the first match. Click **Clear** → highlight removed, status cleared.
  3. **Checkbox persists:** reload the page → the checkbox keeps its last state.
  4. **Restyle:** warm-neutral & teal applied across header/search/tree/detail/toasts/modals/context-menu; match highlight (amber) is clearly distinct from selection (teal).
  5. **Perf:** expanding/collapsing folders and selecting rows stays visibly instant.
  Stop the server (Ctrl-C) when done.

- [ ] **Step 3: Add the Phase 21 section to `IMPLEMENTATION_NOTES.md`** — after the Phase 20 section, document: goal; the `/search` enrichment (`parentId` + root→parent `ancestors[]` of `ItemNode`); `PathResolver.ancestorsOf`; the embed checkbox + single-hit reveal; the warm-neutral & teal restyle with tree-render perf guardrails; test deltas (new `DefaultPathResolverTest.AncestorsOf`, `SearchServiceTest`, `SearchHitMapperTest`, `SearchControllerTest`, one E2E); no automated UI tests; spec + plan paths.

- [ ] **Step 4: Commit**

```bash
git add IMPLEMENTATION_NOTES.md
git commit -m "docs(phase21): implementation notes for embed-search + restyle"
```

- [ ] **Step 5: Add the completion memory note** — write `project-phase21-embed-search-restyle-done.md` to the memory dir and index it in `MEMORY.md` (one line), summarizing Phase 21 as done (search ancestors + embed checkbox + single-hit reveal + warm-neutral/teal restyle), with the final test count from Step 1.

---

## Self-Review

**Spec coverage:**
- §2.1 response shape → Task 3 (schema) + Task 4 (JSON assertions). ✓
- §2.2 OpenAPI → Task 3. ✓
- §2.3 `ancestorsOf` → Task 1. ✓
- §2.4 `SearchHitView`/`SearchService`/`SearchHitMapper` → Tasks 2, 3. ✓
- §2.5 design doc → Task 6. ✓
- §3 embed mode (state/search/highlight/clear) → Tasks 7, 8, 9, 10, 11. ✓
- §3.4 single-hit reveal → Task 9 (`revealHit` + `selectAndLoad` from Task 8). ✓
- §4 restyle + perf guardrails → Task 12. ✓
- §6 tests → Tasks 1–5; §7 done-when manual smoke → Task 13. ✓

**Placeholder scan:** none — every code/test step shows full content and exact commands.

**Type consistency:** `SearchHitView(node, path, ancestors)` 3-arg used consistently (Tasks 2–5); `ancestorsOf(Collection<Long>) → Map<Long,List<CachedNode>>` consistent (Tasks 1–2); generated `SearchHit(itemTreeId, parentId, name, type, ancestors)` matches the mapper (Task 3); UI helpers `selectAndLoad`/`scrollToNode`/`clearSearchHighlight` defined in Tasks 8–9 and consumed in Tasks 9, 11; `state.search.matchIds` defined in Task 7, read in Task 8, written in Task 9. ✓

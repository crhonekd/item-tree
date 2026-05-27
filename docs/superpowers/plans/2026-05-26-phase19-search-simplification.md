# Phase 19 — Search simplification (single `q` param) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the two-parameter `GET /search?id=…|name=…` API with a single `q` query parameter. The server tries `Long.parseLong(q)`; on success and a cache hit by id, returns the single matching node — otherwise (parse fails, or id not present) falls back to substring-match search by name. Test UI drops the name/id radio toggle.

**Architecture:**
- One orchestration method on `SearchService`: `search(String q, OptionalInt limit) → List<CachedNode>`. Cache primitives (`searchById`, `searchByName`) are unchanged. Controller becomes a thin pass-through that only validates `limit > 0`.
- OpenAPI contract changes: drop `id` and `name` query params on `/search`; add required `q`. Generator regenerates `SearchApi`; controller signature changes accordingly.
- Static UI: remove radio buttons in `index.html`, simplify `search.js` to send `q`, update `api.js#search` signature to `({ q, limit })`.

**Tech Stack:** Java 21 / Spring Boot, JUnit 5 + Mockito + AssertJ, openapi-generator, vanilla JS for the static UI. H2 (Oracle compat) and in-memory event bus for tests (Phase A stubs unchanged).

**Phase numbering:** This is **Phase 19**. Phase 18 (Work PC wiring, user-managed) is unaffected. CLAUDE.md and the design doc invariants are unchanged.

**Inputs / decisions already made (from clarifying questions, 2026-05-26):**
- Single `q` param; **drop `id` and `name`** entirely (no backward-compat shim).
- When `q` parses as `Long` but no item has that id → **fall back to name search** using the same string.
- Empty / whitespace-only `q` → **return empty list, 200 OK** (no `INVALID_SEARCH_PARAMS`).
- Keep the optional `limit` query param; it applies only to name-search results. `INVALID_SEARCH_PARAMS` remains in use for `limit <= 0`.

---

## File Structure

**Modified backend:**
- `src/main/resources/openapi/itemtree-api.yaml` — `/api/v1/itemtree/search` parameters: remove `id`, remove `name`, add required `q`.
- `src/main/java/com/myxcomp/ice/xtree/service/SearchService.java` — replace `searchById(long)` and `searchByName(String, OptionalInt)` with a single `search(String, OptionalInt)`.
- `src/main/java/com/myxcomp/ice/xtree/api/controller/SearchController.java` — match new generated signature; remove the two-param validation, keep the `limit > 0` check.

**Unchanged backend:**
- `cache/TreeCache.java`, `cache/DefaultTreeCache.java` — both primitives stay (no contract change).
- `api/mapper/SearchHitMapper.java` — no DTO changes.
- `service/exception/ErrorCode.java` — `INVALID_SEARCH_PARAMS` retained (used by limit validation).

**Modified static UI:**
- `src/main/resources/static/index.html` — drop the two `<input type="radio" name="search-mode" …>` labels; update the input `placeholder`.
- `src/main/resources/static/js/search.js` — remove `mode` lookup; always send `{ q, limit }`.
- `src/main/resources/static/js/api.js` — `search` signature becomes `({ q, limit })`.

**Modified tests:**
- `src/test/java/com/myxcomp/ice/xtree/service/SearchServiceTest.java` — replace existing 5 tests with a `@Nested` `Search` group covering parse / fallback / blank / limit propagation.
- `src/test/java/com/myxcomp/ice/xtree/api/controller/SearchControllerTest.java` — replace existing 7 tests with a smaller set covering the new contract.
- `src/test/java/com/myxcomp/ice/xtree/ApiContractTest.java` — no count change (still 11 ops, `search` still present); confirm by running.

**Modified docs:**
- `itemtree-service-design.md` — §3 (REST table row + `/search` response shape paragraph), §4 read-endpoint list paragraph unchanged.
- `IMPLEMENTATION_NOTES.md` — add Phase 19 section after the current Phase 17 section, before Phase 18.

---

## Task 1 — Update OpenAPI spec: replace `id`/`name` with required `q`

**Files:**
- Modify: `src/main/resources/openapi/itemtree-api.yaml` (search operation, lines 311–348)
- Verify: `src/test/java/com/myxcomp/ice/xtree/ApiContractTest.java`

- [ ] **Step 1: Edit the OpenAPI search operation**

Replace the existing `/api/v1/itemtree/search` block (lines ≈311–348). Find:

```yaml
  /api/v1/itemtree/search:
    get:
      tags: [search]
      operationId: search
      summary: Search by id or name; supply exactly one of id or name
      parameters:
        - $ref: '#/components/parameters/XIceUser'
        - $ref: '#/components/parameters/XImpersonatedUser'
        - name: id
          in: query
          required: false
          schema:
            type: integer
            format: int64
        - name: name
          in: query
          required: false
          schema:
            type: string
        - name: limit
          in: query
          required: false
          schema:
            type: integer
            format: int32
```

Replace with:

```yaml
  /api/v1/itemtree/search:
    get:
      tags: [search]
      operationId: search
      summary: Search items by query string (numeric → tries id then name; otherwise name)
      parameters:
        - $ref: '#/components/parameters/XIceUser'
        - $ref: '#/components/parameters/XImpersonatedUser'
        - name: q
          in: query
          required: true
          schema:
            type: string
        - name: limit
          in: query
          required: false
          schema:
            type: integer
            format: int32
```

Leave the `responses:` block beneath unchanged.

- [ ] **Step 2: Regenerate sources and confirm the generated signature**

Run:

```bash
./gradlew clean openApiGenerate
```

Expected: BUILD SUCCESSFUL. The generated file `build/generated/openapi/src/main/java/com/myxcomp/ice/xtree/generated/api/SearchApi.java` should now have a `search(...)` method with parameters `String xIceUser, String xImpersonatedUser, String q, Integer limit` (in that order — no `Long id`, no `String name`). Confirm with:

```bash
grep -n "search(" build/generated/openapi/src/main/java/com/myxcomp/ice/xtree/generated/api/SearchApi.java
```

Expected output: a single signature taking `String q` and `Integer limit` (header params come first per existing convention).

- [ ] **Step 3: Run the contract test (will fail to compile downstream, that's OK at this point)**

Run:

```bash
./gradlew test --tests com.myxcomp.ice.xtree.ApiContractTest
```

Expected at this stage: compilation failure in `SearchController.java` (still implements the old signature). The contract test itself does not need changing — it only asserts that `"search"` appears in the spec and that 11 operations are present. Move on to Task 2; the build returns to green there.

- [ ] **Step 4: Commit the spec change**

```bash
git add src/main/resources/openapi/itemtree-api.yaml
git commit -m "feat(phase19): OpenAPI \`/search\` accepts single \`q\` param"
```

---

## Task 2 — Rewrite `SearchService` around a single `search(q, limit)` method (TDD)

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/service/SearchService.java`
- Modify: `src/test/java/com/myxcomp/ice/xtree/service/SearchServiceTest.java`

- [ ] **Step 1: Replace the test file with the new spec**

Overwrite `src/test/java/com/myxcomp/ice/xtree/service/SearchServiceTest.java` with:

```java
package com.myxcomp.ice.xtree.service;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.TreeCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchServiceTest {

    @Mock TreeCache cache;
    SearchService service;

    @BeforeEach
    void setUp() {
        service = new SearchService(cache);
    }

    private CachedNode node(long id, String name) {
        return new CachedNode(id, 1L, name, "Report", Instant.EPOCH, "sys");
    }

    @Nested
    class Search {

        @ParameterizedTest
        @ValueSource(strings = {"", "   ", "\t"})
        void blankQueryReturnsEmptyListAndDoesNotTouchCache(String q) {
            assertThat(service.search(q, OptionalInt.empty())).isEmpty();
            verify(cache, never()).searchById(anyLong());
            verify(cache, never()).searchByName(any(), any());
        }

        @Test
        void nullQueryReturnsEmptyList() {
            assertThat(service.search(null, OptionalInt.empty())).isEmpty();
            verify(cache, never()).searchById(anyLong());
            verify(cache, never()).searchByName(any(), any());
        }

        @Test
        void numericQueryHittingByIdReturnsSingleNode() {
            CachedNode hit = node(42L, "Report-42");
            when(cache.searchById(42L)).thenReturn(Optional.of(hit));

            assertThat(service.search("42", OptionalInt.empty())).containsExactly(hit);
            verify(cache, never()).searchByName(any(), any());
        }

        @Test
        void numericQueryWithSurroundingWhitespaceIsTrimmedBeforeParsing() {
            CachedNode hit = node(7L, "Lucky");
            when(cache.searchById(7L)).thenReturn(Optional.of(hit));

            assertThat(service.search("  7  ", OptionalInt.empty())).containsExactly(hit);
        }

        @Test
        void numericQueryMissingByIdFallsBackToNameSearch() {
            when(cache.searchById(999L)).thenReturn(Optional.empty());
            CachedNode nameHit = node(101L, "999-Report");
            when(cache.searchByName("999", OptionalInt.empty())).thenReturn(List.of(nameHit));

            assertThat(service.search("999", OptionalInt.empty())).containsExactly(nameHit);
        }

        @Test
        void nonNumericQueryGoesStraightToNameSearch() {
            CachedNode hit = node(8L, "MyReport");
            when(cache.searchByName("repo", OptionalInt.empty())).thenReturn(List.of(hit));

            assertThat(service.search("repo", OptionalInt.empty())).containsExactly(hit);
            verify(cache, never()).searchById(anyLong());
        }

        @Test
        void numericTooLargeForLongFallsBackToNameSearch() {
            String overflow = "99999999999999999999"; // > Long.MAX_VALUE
            when(cache.searchByName(overflow, OptionalInt.empty())).thenReturn(List.of());

            assertThat(service.search(overflow, OptionalInt.empty())).isEmpty();
            verify(cache, never()).searchById(anyLong());
            verify(cache).searchByName(overflow, OptionalInt.empty());
        }

        @Test
        void limitIsPropagatedToNameSearch() {
            when(cache.searchByName("rep", OptionalInt.of(5))).thenReturn(List.of());

            service.search("rep", OptionalInt.of(5));

            verify(cache).searchByName("rep", OptionalInt.of(5));
        }

        @Test
        void limitIsNotConsultedWhenIdHits() {
            CachedNode hit = node(3L, "Three");
            when(cache.searchById(3L)).thenReturn(Optional.of(hit));

            assertThat(service.search("3", OptionalInt.of(1))).containsExactly(hit);
            verify(cache, never()).searchByName(any(), any());
        }
    }
}
```

- [ ] **Step 2: Run the test — expect a compile failure**

Run:

```bash
./gradlew test --tests com.myxcomp.ice.xtree.service.SearchServiceTest
```

Expected: COMPILE FAILURE — `search(String, OptionalInt)` is not yet defined on `SearchService`.

- [ ] **Step 3: Replace `SearchService.java`**

Overwrite `src/main/java/com/myxcomp/ice/xtree/service/SearchService.java` with:

```java
package com.myxcomp.ice.xtree.service;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.TreeCache;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

@Service
public class SearchService {

    private final TreeCache cache;

    public SearchService(TreeCache cache) {
        this.cache = cache;
    }

    /**
     * Numeric-or-name search. If {@code q} parses as a Long and an item with
     * that id is cached, returns that single item. Otherwise (parse fails or
     * id not present) returns a case-insensitive substring match on name.
     * Blank / null {@code q} returns an empty list without touching the cache.
     */
    public List<CachedNode> search(String q, OptionalInt limit) {
        Objects.requireNonNull(limit, "limit");
        if (q == null) {
            return List.of();
        }
        String trimmed = q.trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        Long parsed = tryParseLong(trimmed);
        if (parsed != null) {
            Optional<CachedNode> byId = cache.searchById(parsed);
            if (byId.isPresent()) {
                return List.of(byId.get());
            }
        }
        return cache.searchByName(trimmed, limit);
    }

    private static Long tryParseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
```

- [ ] **Step 4: Run the service tests — expect green**

Run:

```bash
./gradlew test --tests com.myxcomp.ice.xtree.service.SearchServiceTest
```

Expected: 10 tests pass (1 `@ParameterizedTest` with 3 sources + 8 `@Test` methods).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/service/SearchService.java \
        src/test/java/com/myxcomp/ice/xtree/service/SearchServiceTest.java
git commit -m "feat(phase19): SearchService.search(q, limit) with parse-then-fallback"
```

---

## Task 3 — Rewrite `SearchController` to match the new generated signature (TDD)

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/api/controller/SearchController.java`
- Modify: `src/test/java/com/myxcomp/ice/xtree/api/controller/SearchControllerTest.java`

- [ ] **Step 1: Replace the controller test file**

Overwrite `src/test/java/com/myxcomp/ice/xtree/api/controller/SearchControllerTest.java` with:

```java
package com.myxcomp.ice.xtree.api.controller;

import com.myxcomp.ice.xtree.api.advice.GlobalExceptionHandler;
import com.myxcomp.ice.xtree.api.advice.ProblemFactory;
import com.myxcomp.ice.xtree.api.mapper.SearchHitMapper;
import com.myxcomp.ice.xtree.cache.CacheReadinessGate;
import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.config.SecurityProperties;
import com.myxcomp.ice.xtree.service.SearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SearchController.class)
@Import({GlobalExceptionHandler.class, ProblemFactory.class, SearchHitMapper.class})
class SearchControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean SearchService searchService;
    @MockitoBean CacheReadinessGate cacheReadinessGate;
    @MockitoBean SecurityProperties securityProperties;

    @BeforeEach
    void gateReady() {
        when(cacheReadinessGate.isReady()).thenReturn(true);
    }

    private CachedNode node(long id, String name, String type) {
        return new CachedNode(id, 0L, name, type, Instant.EPOCH, "alice");
    }

    @Test
    void numericQueryReturnsServiceResult() throws Exception {
        when(searchService.search(eq("42"), any(OptionalInt.class)))
                .thenReturn(List.of(node(42L, "Report-1", "Report")));

        mvc.perform(get("/api/v1/itemtree/search?q=42")
                        .header("X-Ice-User", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].itemTreeId").value(42));
    }

    @Test
    void nameQueryReturnsServiceResult() throws Exception {
        when(searchService.search(eq("Repo"), any(OptionalInt.class)))
                .thenReturn(List.of(node(42L, "Report-1", "Report"),
                                    node(43L, "Report-2", "Report")));

        mvc.perform(get("/api/v1/itemtree/search?q=Repo")
                        .header("X-Ice-User", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void emptyQueryReturnsEmptyList() throws Exception {
        when(searchService.search(eq(""), any(OptionalInt.class))).thenReturn(List.of());

        mvc.perform(get("/api/v1/itemtree/search?q=")
                        .header("X-Ice-User", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void limitIsPropagatedToService() throws Exception {
        when(searchService.search(eq("Repo"), eq(OptionalInt.of(5)))).thenReturn(List.of());

        mvc.perform(get("/api/v1/itemtree/search?q=Repo&limit=5")
                        .header("X-Ice-User", "alice"))
                .andExpect(status().isOk());

        ArgumentCaptor<OptionalInt> captor = ArgumentCaptor.forClass(OptionalInt.class);
        verify(searchService).search(eq("Repo"), captor.capture());
        assertThat(captor.getValue()).isEqualTo(OptionalInt.of(5));
    }

    @Test
    void missingQueryParamReturns400FromGenerator() throws Exception {
        mvc.perform(get("/api/v1/itemtree/search")
                        .header("X-Ice-User", "alice"))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void nonPositiveLimitReturns400(int limit) throws Exception {
        mvc.perform(get("/api/v1/itemtree/search?q=Repo&limit=" + limit)
                        .header("X-Ice-User", "alice"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_SEARCH_PARAMS"))
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("positive integer")));
    }
}
```

Notes:
- The removed tests (`searchWithBothIdAndNameReturns400`, `searchWithNeitherIdNorNameReturns400`, the separate `searchByIdReturnsSingleHit` + `searchByIdMissingReturnsEmptyList`) no longer match the contract.
- `missingQueryParamReturns400FromGenerator` covers the OpenAPI-generated mandatory-param behaviour. If the generator decides to surface this as a Spring `MissingServletRequestParameterException`, the global exception handler already maps it to a 400 problem document — we only assert the status code so the test is not coupled to the exact body shape produced for a missing required query param.

- [ ] **Step 2: Replace the controller**

Overwrite `src/main/java/com/myxcomp/ice/xtree/api/controller/SearchController.java` with:

```java
package com.myxcomp.ice.xtree.api.controller;

import com.myxcomp.ice.xtree.api.mapper.SearchHitMapper;
import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.generated.api.SearchApi;
import com.myxcomp.ice.xtree.generated.model.SearchHit;
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
    public ResponseEntity<List<SearchHit>> search(String xIceUser, String xImpersonatedUser,
                                                  String q, Integer limit) {
        if (limit != null && limit <= 0) {
            throw new ValidationException(ErrorCode.INVALID_SEARCH_PARAMS,
                    "limit must be a positive integer");
        }
        OptionalInt limitOpt = limit != null ? OptionalInt.of(limit) : OptionalInt.empty();
        List<CachedNode> hits = searchService.search(q, limitOpt);
        return ResponseEntity.ok(searchHitMapper.toDtos(hits));
    }
}
```

Notes:
- Parameter order matches the generator's convention (headers first, then query params in spec order); the regenerated `SearchApi` interface from Task 1 dictates the final order. If the generator emits a different order, mirror exactly what the generated interface declares — do not deviate.

- [ ] **Step 3: Run the full controller test class**

Run:

```bash
./gradlew test --tests com.myxcomp.ice.xtree.api.controller.SearchControllerTest
```

Expected: 7 tests pass (5 `@Test` + 1 `@ParameterizedTest` with 2 sources = 7 executions).

- [ ] **Step 4: Run the OpenAPI contract test as a sanity check**

Run:

```bash
./gradlew test --tests com.myxcomp.ice.xtree.ApiContractTest
```

Expected: PASS (no change required — search is still in the spec; the op count is still 11).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/api/controller/SearchController.java \
        src/test/java/com/myxcomp/ice/xtree/api/controller/SearchControllerTest.java
git commit -m "feat(phase19): SearchController accepts single \`q\` param"
```

---

## Task 4 — Static UI: drop radios, simplify `search.js`, update `api.js`

**Files:**
- Modify: `src/main/resources/static/index.html` (lines 29–40, the `<section class="search-bar">` block)
- Modify: `src/main/resources/static/js/search.js`
- Modify: `src/main/resources/static/js/api.js` (the `search:` entry around lines 76–82)

- [ ] **Step 1: Edit `index.html`**

Replace the `<section class="search-bar">` block. Find:

```html
  <section class="search-bar">
    <label>Search:
      <input id="search-input" type="text" placeholder="name or id…">
    </label>
    <label><input type="radio" name="search-mode" value="name" checked> name</label>
    <label><input type="radio" name="search-mode" value="id"> id</label>
    <label>limit:
      <input id="search-limit" type="number" min="1" value="50" style="width:5em">
    </label>
    <button id="search-btn" type="button">Search</button>
    <ul id="search-results" class="search-results"></ul>
  </section>
```

Replace with:

```html
  <section class="search-bar">
    <label>Search:
      <input id="search-input" type="text" placeholder="id or name…">
    </label>
    <label>limit:
      <input id="search-limit" type="number" min="1" value="50" style="width:5em">
    </label>
    <button id="search-btn" type="button">Search</button>
    <ul id="search-results" class="search-results"></ul>
  </section>
```

- [ ] **Step 2: Edit `search.js`**

Overwrite `src/main/resources/static/js/search.js` with:

```javascript
import { state, ingestNodes } from './state.js';
import { api, ProblemError } from './api.js';
import { toastError } from './toast.js';
import { renderTree, ingestSubtreeResult } from './tree.js';

export async function runSearch() {
  const q = document.getElementById('search-input').value.trim();
  const limit = document.getElementById('search-limit').value.trim() || undefined;
  const results = document.getElementById('search-results');
  results.innerHTML = '';
  if (!q) return;
  try {
    const hits = await api.search({ q, limit });
    if (!hits || hits.length === 0) {
      results.innerHTML = '<li>(no results)</li>';
      return;
    }
    for (const hit of hits) {
      const li = document.createElement('li');
      li.textContent = `${hit.itemTreeId}  ${hit.type}  ${hit.name}`;
      li.addEventListener('click', () => navigateTo(hit.itemTreeId));
      results.appendChild(li);
    }
  } catch (e) {
    if (e instanceof ProblemError) toastError(e.problem); else toastError(String(e));
  }
}

async function navigateTo(id) {
  if (!state.tree.nodesById.has(id)) {
    try {
      const subtree = await api.getSubtree(id);
      ingestSubtreeResult(id, subtree);
    } catch (e) {
      if (e instanceof ProblemError) toastError(e.problem); else toastError(String(e));
      return;
    }
  }
  let cur = state.tree.nodesById.get(id);
  while (cur && cur.parentId && cur.parentId !== 0) {
    state.tree.expanded.add(cur.parentId);
    cur = state.tree.nodesById.get(cur.parentId);
  }
  state.tree.selectedId = id;
  renderTree();
  const row = document.querySelector(`.tree-node[data-id="${id}"] .tree-row`);
  row?.scrollIntoView({ block: 'center', behavior: 'smooth' });
}
```

(Functional change vs. current file: removed the `mode` lookup and the `id`/`name` branching; everything else is preserved.)

- [ ] **Step 3: Edit `api.js`**

In `src/main/resources/static/js/api.js`, find:

```javascript
  search: ({ id, name, limit }) => {
    const params = new URLSearchParams();
    if (id !== undefined && id !== '') params.set('id', String(id));
    if (name !== undefined && name !== '') params.set('name', name);
    if (limit !== undefined && limit !== '') params.set('limit', String(limit));
    return request('GET', `/api/v1/itemtree/search?${params.toString()}`);
  },
```

Replace with:

```javascript
  search: ({ q, limit }) => {
    const params = new URLSearchParams();
    params.set('q', q ?? '');
    if (limit !== undefined && limit !== '') params.set('limit', String(limit));
    return request('GET', `/api/v1/itemtree/search?${params.toString()}`);
  },
```

- [ ] **Step 4: Smoke-test the static UI manually**

```bash
./gradlew bootRun
```

Expected: app boots, `dev` profile active, cache populated from H2 dummy data. In a browser at `http://localhost:8080/`:

1. Log in as `alice`. The search bar is visible with only the text input, limit field, and Search button — no radio buttons.
2. Type `1` (the root id), click Search → single row `1  Folder  root` appears. Click it → root is selected in the tree.
3. Type `999999` (no such id), click Search → falls back to name search; `(no results)` shows.
4. Type a substring of any item name (e.g. `Report`), click Search → multiple hits appear.
5. Stop the server: Ctrl-C.

If any of those fail, fix before committing.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/static/index.html \
        src/main/resources/static/js/search.js \
        src/main/resources/static/js/api.js
git commit -m "feat(phase19): static UI uses single search input (no id/name radios)"
```

---

## Task 5 — Update design doc and `IMPLEMENTATION_NOTES.md`

**Files:**
- Modify: `itemtree-service-design.md` (§1 overview line 49, §3 endpoints table line 147, §3 schema summary near line 154–161)
- Modify: `IMPLEMENTATION_NOTES.md` (insert new Phase 19 section after Phase 17, before Phase 18)

- [ ] **Step 1: Update `itemtree-service-design.md` overview line**

Find line 49:

```
- Search (by id or name)
```

Replace with:

```
- Search (single query string — numeric resolves to id, otherwise name)
```

- [ ] **Step 2: Update the endpoints table row**

Find line ~147:

```
| GET    | `/search`                           | Search by id or name                     |
```

Replace with:

```
| GET    | `/search`                           | Search by `q` (numeric → id, else name)  |
```

- [ ] **Step 3: Update the response-shape paragraph for `/search`**

Find line ~161:

```
- **`/search`** — flat list of `SearchHit`. Optional `limit` query parameter; no default cap.
```

Replace with:

```
- **`/search`** — flat list of `SearchHit`. Required `q` query parameter (string). If `q` parses as `Long` and an item with that id exists, the response contains that single item; otherwise (parse fails or id missing) the server returns a case-insensitive substring match on `name`. Optional `limit` applies to the name-search branch only; no default cap. Blank `q` returns `[]` with status 200.
```

- [ ] **Step 4: Add Phase 19 section to `IMPLEMENTATION_NOTES.md`**

Insert the following block immediately before the `## Phase 18 — Work PC wiring (Phase B, user-managed)` header:

```markdown
## Phase 19 — Search simplification (single `q` param) ✅ COMPLETE (2026-05-26)

**Goal:** Collapse the two-parameter `/search?id=…|name=…` API into a single `?q=…` parameter. Server tries `Long.parseLong(q)`; on success and a cache hit by id returns the single matching node, otherwise falls back to name search.

Full plan in `docs/superpowers/plans/2026-05-26-phase19-search-simplification.md`.

**Implementable end-to-end in Phase A.** No Phase B blockers.

### Surface

- **OpenAPI:** `/api/v1/itemtree/search` parameters changed — `id` and `name` removed, required `q` added; `limit` unchanged.
- **`SearchService`:** previous `searchById(long)` / `searchByName(String, OptionalInt)` removed; replaced with a single `search(String q, OptionalInt limit) -> List<CachedNode>` that handles parse-then-fallback. Trims input; blank/null returns empty list.
- **`SearchController`:** matches the regenerated `SearchApi` signature (`String q, Integer limit`); only validation kept is `limit > 0` (errors with `INVALID_SEARCH_PARAMS`).
- **`TreeCache`:** unchanged — both `searchById` and `searchByName` remain as primitives, both called by the new service method.
- **Static UI:** radio buttons removed from `index.html`; `search.js` sends a single `q`; `api.js#search` signature is `({ q, limit })`.

### Tests

- `SearchServiceTest` rewritten as a `@Nested Search` group covering: blank/whitespace/null → empty list; numeric → id hit; trimmed input; id miss → name fallback; non-numeric → name only; > Long.MAX_VALUE → name fallback; limit propagation; limit ignored on id hit.
- `SearchControllerTest` rewritten for new contract; removes the now-obsolete "exactly one of id or name" tests; keeps the `limit <= 0` parameterised case.
- `ApiContractTest` — no change required; 11 operations preserved.

### Deviations from plan

- (record here on completion)

### Done when

- All tests green (expected count: 673 baseline minus 5 removed `SearchServiceTest` tests + 10 new = 678; minus 7 old `SearchControllerTest` tests + 7 new = 678).
- `./gradlew clean build` → BUILD SUCCESSFUL.
- Manual smoke against the dev profile per Task 4 Step 4.
- `itemtree-service-design.md` updated (§1, §3).
- Memory note added: `project-phase19-search-simplification-done.md`.

### Actual done state

(record here on completion)
```

- [ ] **Step 5: Commit**

```bash
git add itemtree-service-design.md IMPLEMENTATION_NOTES.md
git commit -m "docs(phase19): design + implementation notes for single \`q\` search"
```

---

## Task 6 — Full build + final commit

**Files:** none modified; gate task only.

- [ ] **Step 1: Full clean build**

Run:

```bash
./gradlew clean build
```

Expected: BUILD SUCCESSFUL. Test count should land at **678** (673 baseline − 5 old `SearchServiceTest` tests + 10 new `SearchServiceTest` test executions, then − 7 old `SearchControllerTest` tests + 7 new `SearchControllerTest` test executions). If the count differs by more than ±1, recount: parameterised tests count by source values, not by method.

- [ ] **Step 2: Targeted re-run of the touched test classes**

Run:

```bash
./gradlew test \
  --tests com.myxcomp.ice.xtree.service.SearchServiceTest \
  --tests com.myxcomp.ice.xtree.api.controller.SearchControllerTest \
  --tests com.myxcomp.ice.xtree.api.mapper.SearchHitMapperTest \
  --tests com.myxcomp.ice.xtree.ApiContractTest
```

Expected: all pass.

- [ ] **Step 3: Save memory note**

Write `/home/dave/.claude/projects/-home-dave-Git-item-tree/memory/project-phase19-search-simplification-done.md` with frontmatter:

```markdown
---
name: project-phase19-search-simplification-done
description: Phase 19 done as of 2026-05-26; /search takes single `q` param; numeric → id then name, else name; 678 tests
metadata:
  type: project
---

Phase 19 complete on 2026-05-26.

**What changed**
- `/search` now takes a single required `q` query parameter (string). `id` and `name` params removed entirely.
- Server tries `Long.parseLong(q.trim())`; on a cache id hit returns the single node, otherwise falls back to `searchByName`.
- Blank/null `q` returns 200 with empty list. `INVALID_SEARCH_PARAMS` retained for `limit <= 0`.
- Test UI lost the name/id radio buttons; single search input.
- `TreeCache`, `SearchHitMapper`, `ErrorCode` unchanged.

**Why:** API surface simplification — clients should not have to know whether their query is an id or a name. Phase A only; Phase 18 (Work PC wiring) unaffected.

**How to apply:** When extending search later (e.g. payload search per design §1248), build on top of `SearchService.search(String, OptionalInt)` rather than reintroducing the id/name split.

678 tests; tagged `phase-19-search-simplification` (if user tags).
```

Then add this line to `MEMORY.md`:

```
- [Phase 19 search simplification complete](project-phase19-search-simplification-done.md) — Phase 19 done as of 2026-05-26; single `q` param replaces id/name; 678 tests
```

- [ ] **Step 4: Final summary**

Confirm to the user:
- All tests green; build successful.
- Manual UI smoke confirmed per Task 4 Step 4.
- Plan + design doc + implementation notes + memory all updated.
- No git tag created (the user tags phases manually after review).

No commit needed in this task — Tasks 1–5 already committed their respective changes. If `IMPLEMENTATION_NOTES.md` needs a follow-up edit for "Actual done state" / "Deviations", commit that separately:

```bash
git add IMPLEMENTATION_NOTES.md
git commit -m "docs(phase19): record actual done state"
```

---

## Self-review checklist (executed before plan was finalised)

- [x] **Spec coverage** — every clarifying-question answer is implemented somewhere:
  - Single `q` param, drop `id`/`name` → Task 1 (OpenAPI), Task 3 (controller).
  - Numeric miss falls back to name → Task 2 (service), tests cover it.
  - Blank `q` → 200 empty list → Task 2 + Task 3 tests.
  - Keep `limit`, applies only to name → Task 2 (`searchByName(trimmed, limit)`) + Task 3 (`limit <= 0` validation).
- [x] **No placeholders** — every code block is the actual final text. No "TODO" / "similar to" / "add validation" prose.
- [x] **Type consistency** — `SearchService.search(String, OptionalInt) -> List<CachedNode>` used consistently in service file, service tests, controller, controller tests. `Long.parseLong` (not `parseUnsignedLong`) consistent throughout.
- [x] **No imports from `generated.*` outside `api/mapper/`** — `SearchController` is in `api/controller/`, which is exempt per CLAUDE.md (controllers implement generated `*Api` interfaces).
- [x] **Time invariant** — no new clock calls; `Instant.EPOCH` in tests only, as elsewhere.
- [x] **CLAUDE.md test conventions honoured** — `@Nested`, `@ParameterizedTest`, AssertJ `isEmpty()` / `containsExactly`, no Mockito `eq(null)`, named-parameter constants not needed (no SQL touched).
- [x] **Phase numbering** — Phase 19, slots after Phase 17, before placeholder Phase 18 (Work PC wiring).

package com.myxcomp.ice.xtree.api.controller;

import com.myxcomp.ice.xtree.api.advice.GlobalExceptionHandler;
import com.myxcomp.ice.xtree.api.advice.ProblemFactory;
import com.myxcomp.ice.xtree.api.mapper.SearchHitMapper;
import com.myxcomp.ice.xtree.cache.CacheReadinessGate;
import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.service.SearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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

    @BeforeEach
    void gateReady() {
        when(cacheReadinessGate.isReady()).thenReturn(true);
    }

    private CachedNode node(long id, String name, String type) {
        return new CachedNode(id, 0L, name, type, Instant.EPOCH, "alice");
    }

    @Test
    void searchByIdReturnsSingleHit() throws Exception {
        when(searchService.searchById(42L)).thenReturn(Optional.of(node(42L, "Report-1", "Report")));

        mvc.perform(get("/api/v1/itemtree/search?id=42")
                        .header("X-Ice-User", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].itemTreeId").value(42));
    }

    @Test
    void searchByIdMissingReturnsEmptyList() throws Exception {
        when(searchService.searchById(anyLong())).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/itemtree/search?id=999")
                        .header("X-Ice-User", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void searchByNameReturnsList() throws Exception {
        when(searchService.searchByName("Repo", OptionalInt.empty()))
                .thenReturn(List.of(node(42L, "Report-1", "Report"), node(43L, "Report-2", "Report")));

        mvc.perform(get("/api/v1/itemtree/search?name=Repo")
                        .header("X-Ice-User", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void searchByNamePropagatesLimit() throws Exception {
        when(searchService.searchByName("Repo", OptionalInt.of(5))).thenReturn(List.of());

        mvc.perform(get("/api/v1/itemtree/search?name=Repo&limit=5")
                        .header("X-Ice-User", "alice"))
                .andExpect(status().isOk());

        ArgumentCaptor<OptionalInt> captor = ArgumentCaptor.forClass(OptionalInt.class);
        verify(searchService).searchByName(any(), captor.capture());
        assertThat(captor.getValue()).isEqualTo(OptionalInt.of(5));
    }

    @Test
    void searchWithBothIdAndNameReturns400() throws Exception {
        mvc.perform(get("/api/v1/itemtree/search?id=1&name=foo")
                        .header("X-Ice-User", "alice"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("exactly one")))
                .andExpect(jsonPath("$.errorCode").value("INVALID_SEARCH_PARAMS"));
    }

    @Test
    void searchWithNeitherIdNorNameReturns400() throws Exception {
        mvc.perform(get("/api/v1/itemtree/search")
                        .header("X-Ice-User", "alice"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("exactly one")))
                .andExpect(jsonPath("$.errorCode").value("INVALID_SEARCH_PARAMS"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void searchWithNonPositiveLimitReturns400(int limit) throws Exception {
        mvc.perform(get("/api/v1/itemtree/search?name=Repo&limit=" + limit)
                        .header("X-Ice-User", "alice"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_SEARCH_PARAMS"))
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("positive integer")));
    }
}

package com.myxcomp.ice.xtree.api.controller;

import com.myxcomp.ice.xtree.api.advice.GlobalExceptionHandler;
import com.myxcomp.ice.xtree.api.advice.ProblemFactory;
import com.myxcomp.ice.xtree.api.mapper.SearchHitMapper;
import com.myxcomp.ice.xtree.cache.CacheReadinessGate;
import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.config.SecurityProperties;
import com.myxcomp.ice.xtree.service.SearchHitView;
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
                .thenReturn(List.of(new SearchHitView(node(42L, "Report-1", "Report"), "/root/Users/alice")));

        mvc.perform(get("/api/v1/itemtree/search?q=42")
                        .header("X-Ice-User", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].itemTreeId").value(42))
                .andExpect(jsonPath("$[0].name").value("Report-1"))
                .andExpect(jsonPath("$[0].path").value("/root/Users/alice"));
    }

    @Test
    void nameQueryReturnsServiceResult() throws Exception {
        when(searchService.search(eq("Repo"), any(OptionalInt.class)))
                .thenReturn(List.of(
                        new SearchHitView(node(42L, "Report-1", "Report"), "/root/a"),
                        new SearchHitView(node(43L, "Report-2", "Report"), "/root/b")));

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

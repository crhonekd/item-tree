package com.myxcomp.ice.xtree.api.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxcomp.ice.xtree.api.advice.ProblemFactory;
import com.myxcomp.ice.xtree.cache.CacheReadinessGate;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CacheReadinessFilterTest {

    CacheReadinessGate gate;
    CacheReadinessFilter filter;
    ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        gate = mock(CacheReadinessGate.class);
        filter = new CacheReadinessFilter(gate, new ProblemFactory(), objectMapper);
    }

    @Test
    void passesThroughWhenReady() throws Exception {
        when(gate.isReady()).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/itemtree/tree");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void writes503ProblemWhenNotReady() throws Exception {
        when(gate.isReady()).thenReturn(false);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/itemtree/tree");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");

        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.path("status").asInt()).isEqualTo(503);
        assertThat(body.path("title").asText()).isEqualTo("Service Unavailable");
        assertThat(body.path("detail").asText()).isEqualTo("Cache not ready");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/actuator/health", "/v3/api-docs", "/swagger-ui/index.html",
        "/", "/index.html", "/js/app.js", "/styles.css", "/favicon.ico"
    })
    void bypassesNonApiPaths(String path) throws Exception {
        when(gate.isReady()).thenReturn(false);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void stillGatesApiPathsWhenNotReady() throws Exception {
        when(gate.isReady()).thenReturn(false);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/itemtree/search");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(503);
    }
}

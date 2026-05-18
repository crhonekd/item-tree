package com.myxcomp.ice.xtree.api.filter;

import com.myxcomp.ice.xtree.config.SecurityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class RefreshEndpointAccessFilterTest {

    private final SecurityProperties defaults =
            new SecurityProperties(List.of("127.0.0.1/32", "::1/128"));

    @Test
    void nonRefreshUriPassesThroughUntouched() throws ServletException, IOException {
        RefreshEndpointAccessFilter filter = new RefreshEndpointAccessFilter(defaults);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/actuator/health");
        req.setRemoteAddr("203.0.113.10");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
    }

    @Test
    void refreshFromLoopbackPassesThrough() throws ServletException, IOException {
        RefreshEndpointAccessFilter filter = new RefreshEndpointAccessFilter(defaults);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/actuator/itemtree-refresh/delta");
        req.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
    }

    @Test
    void refreshFromIpv4MappedIpv6LoopbackPassesThrough() throws ServletException, IOException {
        RefreshEndpointAccessFilter filter = new RefreshEndpointAccessFilter(defaults);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/actuator/itemtree-refresh/delta");
        req.setRemoteAddr("::ffff:127.0.0.1");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
    }

    @Test
    void refreshFromUntrustedIpReturns403() throws ServletException, IOException {
        RefreshEndpointAccessFilter filter = new RefreshEndpointAccessFilter(defaults);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/actuator/itemtree-refresh/full");
        req.setRemoteAddr("203.0.113.10");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, never()).doFilter(req, res);
        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentType()).isEqualTo("application/problem+json");
    }

    @Test
    void refreshWithEmptyTrustedListReturns403ForAllSources() throws ServletException, IOException {
        RefreshEndpointAccessFilter filter = new RefreshEndpointAccessFilter(
                new SecurityProperties(List.of()));
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/actuator/itemtree-refresh/delta");
        req.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, never()).doFilter(req, res);
        assertThat(res.getStatus()).isEqualTo(403);
    }
}

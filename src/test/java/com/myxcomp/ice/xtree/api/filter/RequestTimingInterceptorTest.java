package com.myxcomp.ice.xtree.api.filter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class RequestTimingInterceptorTest {

    private static final String START_NS = "com.myxcomp.ice.xtree.api.filter.RequestTimingInterceptor.START_NS";

    private final RequestTimingInterceptor interceptor = new RequestTimingInterceptor();

    @Test
    void preHandleStashesStartTimeAndProceeds() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/itemtree/tree");
        HttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = interceptor.preHandle(request, response, new Object());

        assertThat(proceed).isTrue();
        assertThat(request.getAttribute(START_NS)).isInstanceOf(Long.class);
    }

    @Test
    void afterCompletionWithStartAttributeLogsWithoutError() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/itemtree/tree");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);
        interceptor.preHandle(request, response, new Object());

        assertThatCode(() ->
                interceptor.afterCompletion(request, response, new Object(), null))
                .doesNotThrowAnyException();
    }

    @Test
    void afterCompletionWithoutStartAttributeDoesNotThrow() {
        // Defensive: preHandle may not have run (e.g. an earlier interceptor returned false).
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/itemtree/tree");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatCode(() ->
                interceptor.afterCompletion(request, response, new Object(), null))
                .doesNotThrowAnyException();
    }
}

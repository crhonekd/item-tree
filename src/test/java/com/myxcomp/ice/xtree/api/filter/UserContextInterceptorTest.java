package com.myxcomp.ice.xtree.api.filter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class UserContextInterceptorTest {

    private final UserContextInterceptor interceptor = new UserContextInterceptor();

    @AfterEach
    void cleanMdc() {
        MDC.clear();
    }

    @Test
    void preHandlePutsIceUserOnly() {
        HttpServletRequest request = new MockHttpServletRequest() {{
            addHeader("X-Ice-User", "alice");
        }};
        HttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();

        assertThat(MDC.get("iceUser")).isEqualTo("alice");
        assertThat(MDC.get("impersonatedUser")).isNull();
    }

    @Test
    void preHandlePutsBothUsersWhenImpersonating() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Ice-User", "alice");
        request.addHeader("X-Impersonated-User", "bob");

        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertThat(MDC.get("iceUser")).isEqualTo("alice");
        assertThat(MDC.get("impersonatedUser")).isEqualTo("bob");
    }

    @Test
    void afterCompletionClearsMdcKeysItOwns() {
        MDC.put("iceUser", "alice");
        MDC.put("impersonatedUser", "bob");
        MDC.put("unrelated", "stay");

        interceptor.afterCompletion(
                new MockHttpServletRequest(), new MockHttpServletResponse(), new Object(), null);

        assertThat(MDC.get("iceUser")).isNull();
        assertThat(MDC.get("impersonatedUser")).isNull();
        assertThat(MDC.get("unrelated")).isEqualTo("stay");
    }

    @Test
    void preHandleSurvivesMissingIceUserHeader() {
        // Missing X-Ice-User would normally be a 400 via Spring validation; the
        // interceptor must still be safe in case it runs before validation.
        assertThat(interceptor.preHandle(
                new MockHttpServletRequest(), new MockHttpServletResponse(), new Object())).isTrue();
        assertThat(MDC.get("iceUser")).isNull();
    }
}

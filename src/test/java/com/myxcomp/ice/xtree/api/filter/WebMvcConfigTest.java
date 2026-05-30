package com.myxcomp.ice.xtree.api.filter;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.handler.MappedInterceptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the interceptor registration order in {@link WebMvcConfig}.
 * A regression that swaps RequestTimingInterceptor and UserContextInterceptor
 * (so timing is no longer outermost) must fail this test.
 */
class WebMvcConfigTest {

    @Test
    void timingInterceptorIsRegisteredBeforeUserContextInterceptor() {
        WebMvcConfig config = new WebMvcConfig();
        InterceptorRegistry registry = new InterceptorRegistry();

        config.addInterceptors(registry);

        List<Object> interceptors = ReflectionTestUtils.invokeMethod(registry, "getInterceptors");
        assertThat(interceptors).hasSize(2);

        Object first = interceptors.get(0);
        Object second = interceptors.get(1);

        assertThat(first).isInstanceOf(MappedInterceptor.class);
        assertThat(second).isInstanceOf(MappedInterceptor.class);

        assertThat(((MappedInterceptor) first).getInterceptor())
                .isInstanceOf(RequestTimingInterceptor.class);
        assertThat(((MappedInterceptor) second).getInterceptor())
                .isInstanceOf(UserContextInterceptor.class);
    }
}

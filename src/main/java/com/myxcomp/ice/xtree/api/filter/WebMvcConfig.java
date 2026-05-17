package com.myxcomp.ice.xtree.api.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxcomp.ice.xtree.api.advice.ProblemFactory;
import com.myxcomp.ice.xtree.cache.CacheReadinessGate;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new UserContextInterceptor())
                .addPathPatterns("/api/v1/itemtree/**");
    }

    @Bean
    public FilterRegistrationBean<CacheReadinessFilter> cacheReadinessFilterRegistration(
            CacheReadinessGate gate, ProblemFactory problemFactory, ObjectMapper objectMapper) {
        FilterRegistrationBean<CacheReadinessFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new CacheReadinessFilter(gate, problemFactory, objectMapper));
        registration.addUrlPatterns("/api/v1/itemtree/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}

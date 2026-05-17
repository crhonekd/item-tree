package com.myxcomp.ice.xtree.api.filter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.servlet.HandlerInterceptor;

public class UserContextInterceptor implements HandlerInterceptor {

    private static final String HEADER_ICE_USER = "X-Ice-User";
    private static final String HEADER_IMPERSONATED_USER = "X-Impersonated-User";
    private static final String MDC_ICE_USER = "iceUser";
    private static final String MDC_IMPERSONATED_USER = "impersonatedUser";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String iceUser = request.getHeader(HEADER_ICE_USER);
        if (iceUser != null) MDC.put(MDC_ICE_USER, iceUser);
        String impersonated = request.getHeader(HEADER_IMPERSONATED_USER);
        if (impersonated != null) MDC.put(MDC_IMPERSONATED_USER, impersonated);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        MDC.remove(MDC_ICE_USER);
        MDC.remove(MDC_IMPERSONATED_USER);
    }
}

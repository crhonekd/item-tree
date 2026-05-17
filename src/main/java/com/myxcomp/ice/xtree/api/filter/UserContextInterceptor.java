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
        request.setAttribute(MDC_ICE_USER, MDC.get(MDC_ICE_USER));
        request.setAttribute(MDC_IMPERSONATED_USER, MDC.get(MDC_IMPERSONATED_USER));
        String iceUser = request.getHeader(HEADER_ICE_USER);
        if (iceUser != null) MDC.put(MDC_ICE_USER, iceUser);
        String impersonated = request.getHeader(HEADER_IMPERSONATED_USER);
        if (impersonated != null) MDC.put(MDC_IMPERSONATED_USER, impersonated);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        restoreMdc(request, MDC_ICE_USER);
        restoreMdc(request, MDC_IMPERSONATED_USER);
    }

    private void restoreMdc(HttpServletRequest request, String key) {
        String prior = (String) request.getAttribute(key);
        if (prior == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, prior);
        }
    }
}

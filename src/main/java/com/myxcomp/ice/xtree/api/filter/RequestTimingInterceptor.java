package com.myxcomp.ice.xtree.api.filter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.concurrent.TimeUnit;

/**
 * Logs one INFO line per request with the HTTP method, URI (including query string when
 * present), response status, and end-to-end duration in milliseconds. When the handler
 * throws, the exception's simple class name is appended as {@code [ex=ClassName]}.
 * Registered outermost in {@link WebMvcConfig} so the measured span wraps the whole
 * handler chain. Uses {@link System#nanoTime()} (a monotonic duration source, not a
 * wall-clock API) so the TimeMapper UTC-clock rule does not apply.
 */
public class RequestTimingInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RequestTimingInterceptor.class);
    static final String START_NS = RequestTimingInterceptor.class.getName() + ".START_NS";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(START_NS, System.nanoTime());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        Object start = request.getAttribute(START_NS);
        if (!(start instanceof Long startNs)) {
            return;
        }
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
        String qs = request.getQueryString();
        String uri = (qs != null) ? request.getRequestURI() + "?" + qs : request.getRequestURI();
        String exSuffix = (ex != null) ? " [ex=" + ex.getClass().getSimpleName() + "]" : "";
        log.info("{} {} -> {} ({} ms){}",
                request.getMethod(), uri, response.getStatus(), ms, exSuffix);
    }
}

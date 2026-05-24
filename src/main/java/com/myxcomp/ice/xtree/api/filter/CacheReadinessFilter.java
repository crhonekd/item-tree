package com.myxcomp.ice.xtree.api.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxcomp.ice.xtree.api.advice.ProblemFactory;
import com.myxcomp.ice.xtree.cache.CacheReadinessGate;
import com.myxcomp.ice.xtree.generated.model.Problem;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class CacheReadinessFilter extends OncePerRequestFilter {

    private static final String API_PREFIX = "/api/";

    private final CacheReadinessGate gate;
    private final ProblemFactory problemFactory;
    private final ObjectMapper objectMapper;

    public CacheReadinessFilter(CacheReadinessGate gate, ProblemFactory problemFactory,
                                ObjectMapper objectMapper) {
        this.gate = gate;
        this.problemFactory = problemFactory;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(API_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (gate.isReady()) {
            chain.doFilter(request, response);
            return;
        }
        ResponseEntity<Problem> entity = problemFactory.build(
                HttpStatus.SERVICE_UNAVAILABLE, null, "Cache not ready");
        response.setStatus(entity.getStatusCode().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), entity.getBody());
    }
}

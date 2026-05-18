package com.myxcomp.ice.xtree.api.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxcomp.ice.xtree.api.advice.ProblemFactory;
import com.myxcomp.ice.xtree.common.IpCidrMatcher;
import com.myxcomp.ice.xtree.config.SecurityProperties;
import com.myxcomp.ice.xtree.generated.model.Problem;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Objects;

/**
 * Gates {@code POST /actuator/itemtree-refresh/**} to a configurable CIDR allowlist
 * (design §7 "Manual trigger"). Phase A implementation without Spring Security.
 *
 * <p>Registered explicitly via {@link WebMvcConfig} (not a {@code @Component}) so that
 * {@code @WebMvcTest} slices don't try to instantiate it without its properties bean.
 */
public class RefreshEndpointAccessFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RefreshEndpointAccessFilter.class);
    private static final String REFRESH_PATH_PREFIX = "/actuator/itemtree-refresh";
    private static final String FORBIDDEN_DETAIL =
            "Source IP is not in the configured trusted CIDR list";

    private final List<IpCidrMatcher.CidrRule> rules;
    private final ProblemFactory problemFactory;
    private final ObjectMapper objectMapper;

    public RefreshEndpointAccessFilter(SecurityProperties props,
                                       ProblemFactory problemFactory,
                                       ObjectMapper objectMapper) {
        Objects.requireNonNull(props, "props");
        Objects.requireNonNull(problemFactory, "problemFactory");
        Objects.requireNonNull(objectMapper, "objectMapper");
        this.rules = IpCidrMatcher.parse(props.trustedCidrs());
        this.problemFactory = problemFactory;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !uri.startsWith(REFRESH_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String remote = request.getRemoteAddr();
        InetAddress addr;
        try {
            addr = InetAddress.getByName(remote);
        } catch (UnknownHostException e) {
            denied(response, remote);
            return;
        }
        if (!IpCidrMatcher.matches(addr, rules)) {
            denied(response, remote);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void denied(HttpServletResponse response, String remote) throws IOException {
        log.warn("Denied /actuator/itemtree-refresh request from untrusted source: {}", remote);
        ResponseEntity<Problem> entity =
                problemFactory.build(HttpStatus.FORBIDDEN, null, FORBIDDEN_DETAIL);
        response.setStatus(entity.getStatusCode().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), entity.getBody());
    }
}

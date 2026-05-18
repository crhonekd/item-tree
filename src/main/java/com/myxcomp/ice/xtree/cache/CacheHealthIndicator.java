package com.myxcomp.ice.xtree.cache;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Spring Boot {@link HealthIndicator} for the in-memory tree cache (design §18 "Readiness").
 * Exposed at {@code /actuator/health/cache}.
 *
 * <p>UP when the {@link CacheReadinessGate} has flipped; DOWN until {@code TreeCacheBootstrap}
 * has succeeded.
 */
@Component
public class CacheHealthIndicator implements HealthIndicator {

    private final CacheReadinessGate gate;
    private final TreeCache cache;

    public CacheHealthIndicator(CacheReadinessGate gate, TreeCache cache) {
        this.gate = Objects.requireNonNull(gate, "gate");
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    @Override
    public Health health() {
        boolean ready = gate.isReady();
        Health.Builder builder = ready ? Health.up() : Health.down();
        return builder
                .withDetail("ready", ready)
                .withDetail("size", cache.size())
                .build();
    }
}

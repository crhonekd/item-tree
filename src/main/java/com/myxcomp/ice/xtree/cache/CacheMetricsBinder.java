package com.myxcomp.ice.xtree.cache;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Registers the {@code itemtree.cache.size} gauge (design §18).
 * Holds no state; the gauge reads {@link TreeCache#size()} on each scrape.
 */
@Component
public class CacheMetricsBinder {

    public CacheMetricsBinder(TreeCache cache, MeterRegistry meterRegistry) {
        Objects.requireNonNull(cache, "cache");
        Objects.requireNonNull(meterRegistry, "meterRegistry");
        Gauge.builder("itemtree.cache.size", cache, TreeCache::size)
                .description("Total number of nodes currently held by the in-memory tree cache")
                .register(meterRegistry);
    }
}

package com.myxcomp.ice.xtree.cache;

import com.myxcomp.ice.xtree.common.Types;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class CacheMetricsBinderTest {

    @Test
    void registersCacheSizeGauge() {
        DefaultTreeCache cache = new DefaultTreeCache();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new CacheMetricsBinder(cache, registry);

        Gauge gauge = registry.find("itemtree.cache.size").gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isZero();
    }

    @Test
    void gaugeTracksLiveCacheSize() {
        DefaultTreeCache cache = new DefaultTreeCache();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new CacheMetricsBinder(cache, registry);

        cache.applyCreate(new CachedNode(1L, 0L, "root", Types.FOLDER, Instant.EPOCH, "u"));
        cache.applyCreate(new CachedNode(2L, 1L, "child", Types.FOLDER, Instant.EPOCH, "u"));

        assertThat(registry.find("itemtree.cache.size").gauge().value()).isEqualTo(2.0);
    }
}

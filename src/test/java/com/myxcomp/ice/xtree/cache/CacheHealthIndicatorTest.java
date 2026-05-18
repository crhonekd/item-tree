package com.myxcomp.ice.xtree.cache;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CacheHealthIndicatorTest {

    @Test
    void notReadyReportsDown() {
        CacheReadinessGate gate = mock(CacheReadinessGate.class);
        when(gate.isReady()).thenReturn(false);
        TreeCache cache = mock(TreeCache.class);
        when(cache.size()).thenReturn(0);

        Health h = new CacheHealthIndicator(gate, cache).health();

        assertThat(h.getStatus()).isEqualTo(Status.DOWN);
        assertThat(h.getDetails()).containsEntry("ready", false);
        assertThat(h.getDetails()).containsEntry("size", 0);
    }

    @Test
    void readyReportsUpWithSize() {
        CacheReadinessGate gate = mock(CacheReadinessGate.class);
        when(gate.isReady()).thenReturn(true);
        TreeCache cache = mock(TreeCache.class);
        when(cache.size()).thenReturn(42);

        Health h = new CacheHealthIndicator(gate, cache).health();

        assertThat(h.getStatus()).isEqualTo(Status.UP);
        assertThat(h.getDetails()).containsEntry("ready", true);
        assertThat(h.getDetails()).containsEntry("size", 42);
    }
}

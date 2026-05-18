package com.myxcomp.ice.xtree.refresh;

import com.myxcomp.ice.xtree.cache.TreeCache;
import com.myxcomp.ice.xtree.common.TimeMapper;
import com.myxcomp.ice.xtree.config.RefreshProperties;
import com.myxcomp.ice.xtree.persistence.ItemTreeRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RefreshOrchestratorMetricsTest {

    private RefreshOrchestrator buildOrchestrator(TimeMapper timeMapper, SimpleMeterRegistry registry) {
        return new RefreshOrchestrator(
                mock(ItemTreeRepository.class),
                mock(TreeCache.class),
                mock(DeltaReconciler.class),
                timeMapper,
                registry,
                new RefreshProperties("0 */30 * * * *", 60, "0 0 2 * * MON-FRI", 3,
                        List.of(Duration.ofSeconds(1)))
        );
    }

    @Test
    void lastRefreshAgeGaugeReturnsMinusOneBeforeAnyRefresh() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TimeMapper timeMapper = mock(TimeMapper.class);
        when(timeMapper.now()).thenReturn(Instant.parse("2026-05-18T10:00:00Z"));

        // Keep a strong reference so the WeakReference inside Gauge doesn't get collected.
        RefreshOrchestrator orchestrator = buildOrchestrator(timeMapper, registry);

        Gauge gauge = registry.find("itemtree.cache.last_refresh_age_seconds").gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(-1.0);

        // Prevent orchestrator from being collected before assertion (suppress unused-variable warning).
        assertThat(orchestrator).isNotNull();
    }

    @Test
    void lastRefreshAgeGaugeReportsSecondsSinceRefresh() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TimeMapper timeMapper = mock(TimeMapper.class);
        Instant base = Instant.parse("2026-05-18T10:00:00Z");
        // runDelta stamps lastRefresh with first now() call; gauge read uses the second.
        when(timeMapper.now()).thenReturn(base, base.plus(Duration.ofMinutes(5)));

        ItemTreeRepository repository = mock(ItemTreeRepository.class);
        when(repository.findStructuralChangedSince(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());

        RefreshOrchestrator orchestrator = new RefreshOrchestrator(
                repository,
                mock(TreeCache.class),
                mock(DeltaReconciler.class),
                timeMapper,
                registry,
                new RefreshProperties("0 */30 * * * *", 60, "0 0 2 * * MON-FRI", 3,
                        List.of(Duration.ofSeconds(1)))
        );

        orchestrator.runDelta();  // stamps lastRefresh = base

        // gauge reads timeMapper.now() -> base + 5min
        double age = registry.find("itemtree.cache.last_refresh_age_seconds").gauge().value();
        assertThat(age).isEqualTo(Duration.ofMinutes(5).toSeconds());

        // Prevent orchestrator from being collected before assertion.
        assertThat(orchestrator).isNotNull();
    }
}

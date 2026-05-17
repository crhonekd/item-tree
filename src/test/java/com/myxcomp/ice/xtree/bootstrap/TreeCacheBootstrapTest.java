package com.myxcomp.ice.xtree.bootstrap;

import com.myxcomp.ice.xtree.cache.CacheReadinessGate;
import com.myxcomp.ice.xtree.cache.TreeCache;
import com.myxcomp.ice.xtree.cache.TreeSnapshot;
import com.myxcomp.ice.xtree.config.RefreshProperties;
import com.myxcomp.ice.xtree.persistence.ItemTreeRepository;
import com.myxcomp.ice.xtree.persistence.StructuralRow;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TreeCacheBootstrapTest {

    private ItemTreeRepository repo;
    private TreeCache cache;
    private CacheReadinessGate gate;
    private Sleeper sleeper;
    private SimpleMeterRegistry meterRegistry;
    private RefreshProperties props;
    private TreeCacheBootstrap bootstrap;

    @BeforeEach
    void setUp() {
        repo = mock(ItemTreeRepository.class);
        cache = mock(TreeCache.class);
        gate = mock(CacheReadinessGate.class);
        sleeper = mock(Sleeper.class);
        meterRegistry = new SimpleMeterRegistry();
        props = new RefreshProperties(
                "0 */30 * * * *", 60, "0 0 2 * * MON-FRI",
                3, List.of(Duration.ofMillis(1), Duration.ofMillis(1), Duration.ofMillis(1)));
        bootstrap = new TreeCacheBootstrap(repo, cache, gate, sleeper, meterRegistry, props);
        when(repo.lastUpdateIndexExists()).thenReturn(true);
    }

    @Test
    void firstAttemptSuccessLoadsCacheAndMarksReady() throws Exception {
        doAnswer(inv -> {
            Consumer<StructuralRow> handler = inv.getArgument(0);
            handler.accept(new StructuralRow(1L, 0L, "root", "Folder", Instant.EPOCH, "sys"));
            return null;
        }).when(repo).streamAllStructural(any());

        bootstrap.run(null);

        verify(cache).replaceAll(any(TreeSnapshot.class));
        verify(gate).markReady();
        verify(sleeper, never()).sleep(any());
        assertThat(meterRegistry.counter("itemtree.cache.bootstrap.attempts").count()).isEqualTo(1.0);
    }

    @Test
    void retriesAfterTransientFailures() throws Exception {
        doThrow(new DataAccessResourceFailureException("first"))
                .doThrow(new DataAccessResourceFailureException("second"))
                .doAnswer(inv -> {
                    Consumer<StructuralRow> handler = inv.getArgument(0);
                    handler.accept(new StructuralRow(1L, 0L, "root", "Folder", Instant.EPOCH, "sys"));
                    return null;
                })
                .when(repo).streamAllStructural(any());

        bootstrap.run(null);

        verify(repo, times(3)).streamAllStructural(any());
        verify(sleeper, times(2)).sleep(any());
        verify(gate).markReady();
        assertThat(meterRegistry.counter("itemtree.cache.bootstrap.attempts").count()).isEqualTo(3.0);
    }

    @Test
    void allAttemptsFailingRethrowsAndGateStaysClosed() throws Exception {
        doThrow(new DataAccessResourceFailureException("boom"))
                .when(repo).streamAllStructural(any());

        assertThatThrownBy(() -> bootstrap.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cache bootstrap failed");

        verify(repo, times(3)).streamAllStructural(any());
        verify(gate, never()).markReady();
        verify(cache, never()).replaceAll(any());
    }

    @Test
    void missingLastUpdateIndexLogsWarningButStillSucceeds() throws Exception {
        when(repo.lastUpdateIndexExists()).thenReturn(false);
        doNothing().when(repo).streamAllStructural(any());

        bootstrap.run(null);

        verify(gate).markReady();
        verify(cache).replaceAll(any());
    }
}

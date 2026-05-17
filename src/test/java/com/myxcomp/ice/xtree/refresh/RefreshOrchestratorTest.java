package com.myxcomp.ice.xtree.refresh;

import com.myxcomp.ice.xtree.cache.TreeCache;
import com.myxcomp.ice.xtree.cache.TreeSnapshot;
import com.myxcomp.ice.xtree.common.TimeMapper;
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
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RefreshOrchestratorTest {

    private static final Instant T0 = Instant.parse("2026-05-17T10:00:00Z");
    private static final Instant T_LATER = Instant.parse("2026-05-17T10:30:00Z");

    private ItemTreeRepository repo;
    private TreeCache cache;
    private DeltaReconciler reconciler;
    private TimeMapper timeMapper;
    private SimpleMeterRegistry meterRegistry;
    private RefreshProperties props;
    private RefreshOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        repo = mock(ItemTreeRepository.class);
        cache = mock(TreeCache.class);
        reconciler = mock(DeltaReconciler.class);
        timeMapper = mock(TimeMapper.class);
        meterRegistry = new SimpleMeterRegistry();
        props = new RefreshProperties(
                "0 */30 * * * *", 60, "0 0 2 * * MON-FRI",
                3, List.of(Duration.ZERO));
        orchestrator = new RefreshOrchestrator(
                repo, cache, reconciler, timeMapper, meterRegistry, props);
        when(timeMapper.now()).thenReturn(T0);
    }

    @Test
    void deltaQueriesSinceMinusOverlapAndAdvancesMarkerOnSuccess() {
        StructuralRow r1 = new StructuralRow(10L, 0L, "x", "Folder", T0, "sys");
        when(repo.findStructuralChangedSince(any())).thenReturn(List.of(r1));

        // First run: from epoch − 60s offset; advance to T0.
        RefreshResult first = orchestrator.runDelta();
        assertThat(first.success()).isTrue();
        assertThat(first.type()).isEqualTo(RefreshResult.Type.DELTA);
        verify(repo).findStructuralChangedSince(Instant.EPOCH.minusSeconds(60));
        verify(reconciler).reconcileRow(eq(r1), any(DeltaCounters.class));

        // Second run: now = T_LATER, since-marker should be T0 − 60s.
        when(timeMapper.now()).thenReturn(T_LATER);
        when(repo.findStructuralChangedSince(any())).thenReturn(List.of());
        orchestrator.runDelta();
        verify(repo).findStructuralChangedSince(T0.minusSeconds(60));
    }

    @Test
    void deltaFailureLeavesMarkerUnchanged() {
        when(repo.findStructuralChangedSince(any()))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        RefreshResult result = orchestrator.runDelta();

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("db down");
        assertThat(meterRegistry.counter("itemtree.cache.refresh.delta.failure").count()).isOne();

        // Re-run: since-marker still epoch (not advanced).
        reset(repo);
        when(repo.findStructuralChangedSince(any())).thenReturn(List.of());
        orchestrator.runDelta();
        verify(repo).findStructuralChangedSince(Instant.EPOCH.minusSeconds(60));
    }

    @Test
    void fullReloadBuildsSnapshotAndComputesDrift() {
        when(cache.snapshot()).thenReturn(new TreeSnapshot(Map.of(), Map.of(), Map.of()));
        doAnswer(inv -> {
            Consumer<StructuralRow> handler = inv.getArgument(0);
            handler.accept(new StructuralRow(1L, 0L, "root", "Folder", T0, "sys"));
            return null;
        }).when(repo).streamAllStructural(any());

        RefreshResult result = orchestrator.runFullReload();

        assertThat(result.success()).isTrue();
        assertThat(result.type()).isEqualTo(RefreshResult.Type.FULL);
        assertThat(result.driftCounters().created()).isOne();
        verify(cache).replaceAll(any(TreeSnapshot.class));
        assertThat(meterRegistry.counter("itemtree.cache.refresh.full.drift", "type", "created").count())
                .isOne();
    }

    @Test
    void fullReloadFailureLeavesCacheUntouched() {
        doThrow(new DataAccessResourceFailureException("db down"))
                .when(repo).streamAllStructural(any());

        RefreshResult result = orchestrator.runFullReload();

        assertThat(result.success()).isFalse();
        verify(cache, never()).replaceAll(any());
        assertThat(meterRegistry.counter("itemtree.cache.refresh.full.failure").count()).isOne();
    }

    @Test
    void deltaCountersEmittedAsMicrometerTags() {
        StructuralRow r1 = new StructuralRow(10L, 0L, "x", "Folder", T0, "sys");
        when(repo.findStructuralChangedSince(any())).thenReturn(List.of(r1));
        doAnswer(inv -> {
            DeltaCounters c = inv.getArgument(1);
            c.incrementCreated();
            return null;
        }).when(reconciler).reconcileRow(eq(r1), any(DeltaCounters.class));

        orchestrator.runDelta();

        assertThat(meterRegistry.counter("itemtree.cache.refresh.delta.rows", "change", "created").count())
                .isOne();
    }
}

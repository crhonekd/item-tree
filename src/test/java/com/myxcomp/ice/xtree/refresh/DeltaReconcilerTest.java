package com.myxcomp.ice.xtree.refresh;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.TreeCache;
import com.myxcomp.ice.xtree.persistence.StructuralRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DeltaReconcilerTest {

    private static final Instant T1 = Instant.parse("2026-05-01T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-05-02T10:00:00Z");

    private TreeCache cache;
    private DeltaReconciler reconciler;

    @BeforeEach
    void setUp() {
        cache = mock(TreeCache.class);
        reconciler = new DeltaReconciler(cache);
    }

    @Test
    void newRowDispatchesToApplyCreate() {
        StructuralRow row = new StructuralRow(42L, 1L, "fresh", "Folder", T1, "sys");
        when(cache.getById(42L)).thenReturn(Optional.empty());

        DeltaCounters counters = new DeltaCounters();
        reconciler.reconcileRow(row, counters);

        verify(cache).applyCreate(new CachedNode(42L, 1L, "fresh", "Folder", T1, "sys"));
        assertThat(counters.created()).isOne();
        assertThat(counters.total()).isOne();
    }

    @Test
    void parentChangedDispatchesToApplyMove() {
        when(cache.getById(42L)).thenReturn(Optional.of(
                new CachedNode(42L, 1L, "name", "Folder", T1, "sys")));

        DeltaCounters counters = new DeltaCounters();
        reconciler.reconcileRow(
                new StructuralRow(42L, 2L, "name", "Folder", T2, "sys"),
                counters);

        verify(cache).applyMove(42L, 2L, T2, "sys");
        verify(cache, never()).applyCreate(any());
        verify(cache, never()).applyMetadataUpdate(anyLong(), any(), any());
        assertThat(counters.moved()).isOne();
    }

    @Test
    void nameChangedDispatchesToApplyRename() {
        when(cache.getById(42L)).thenReturn(Optional.of(
                new CachedNode(42L, 1L, "old", "Folder", T1, "sys")));

        DeltaCounters counters = new DeltaCounters();
        reconciler.reconcileRow(
                new StructuralRow(42L, 1L, "new", "Folder", T2, "sys"),
                counters);

        verify(cache).applyRename(42L, "new", T2, "sys");
        assertThat(counters.renamed()).isOne();
    }

    @Test
    void metaOnlyChangeDispatchesToApplyMetadataUpdate() {
        when(cache.getById(42L)).thenReturn(Optional.of(
                new CachedNode(42L, 1L, "name", "Folder", T1, "sys")));

        DeltaCounters counters = new DeltaCounters();
        reconciler.reconcileRow(
                new StructuralRow(42L, 1L, "name", "Folder", T2, "sys2"),
                counters);

        verify(cache).applyMetadataUpdate(42L, T2, "sys2");
        assertThat(counters.meta()).isOne();
    }

    @Test
    void identicalRowDispatchesNothing() {
        when(cache.getById(42L)).thenReturn(Optional.of(
                new CachedNode(42L, 1L, "name", "Folder", T1, "sys")));

        DeltaCounters counters = new DeltaCounters();
        reconciler.reconcileRow(
                new StructuralRow(42L, 1L, "name", "Folder", T1, "sys"),
                counters);

        verify(cache).getById(42L);
        verifyNoMoreInteractions(cache);
        assertThat(counters.total()).isZero();
    }

    @Test
    void combinedParentAndNameChangeDispatchesBothMoveAndRename() {
        when(cache.getById(42L)).thenReturn(Optional.of(
                new CachedNode(42L, 1L, "old", "Folder", T1, "sys")));

        DeltaCounters counters = new DeltaCounters();
        reconciler.reconcileRow(
                new StructuralRow(42L, 2L, "new", "Folder", T2, "sys"),
                counters);

        verify(cache).applyMove(42L, 2L, T2, "sys");
        verify(cache).applyRename(42L, "new", T2, "sys");
        verify(cache, never()).applyMetadataUpdate(anyLong(), any(), any());
        assertThat(counters.moved()).isOne();
        assertThat(counters.renamed()).isOne();
    }
}

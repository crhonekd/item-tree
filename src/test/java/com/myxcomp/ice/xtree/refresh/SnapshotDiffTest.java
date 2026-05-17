package com.myxcomp.ice.xtree.refresh;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.TreeSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class SnapshotDiffTest {

    private static final Instant T1 = Instant.parse("2026-05-01T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-05-02T10:00:00Z");

    private static TreeSnapshot snapshot(Map<Long, CachedNode> byId) {
        return new TreeSnapshot(byId, Map.of(), Map.of());
    }

    private static CachedNode node(long id, long parent, String name, Instant lastUpdate) {
        return new CachedNode(id, parent, name, "Folder", lastUpdate, "sys");
    }

    @Test
    void emptyVsEmptyHasNoDrift() {
        DriftCounters d = SnapshotDiff.diff(snapshot(Map.of()), snapshot(Map.of()));
        assertThat(d.created()).isZero();
        assertThat(d.deleted()).isZero();
        assertThat(d.mutated()).isZero();
        assertThat(d.total()).isZero();
    }

    @Test
    void emptyOldOnlyNewYieldsCreations() {
        DriftCounters d = SnapshotDiff.diff(
                snapshot(Map.of()),
                snapshot(Map.of(1L, node(1L, 0L, "a", T1), 2L, node(2L, 0L, "b", T1))));
        assertThat(d.created()).isEqualTo(2);
        assertThat(d.deleted()).isZero();
        assertThat(d.mutated()).isZero();
    }

    @Test
    void emptyNewOnlyOldYieldsDeletions() {
        DriftCounters d = SnapshotDiff.diff(
                snapshot(Map.of(1L, node(1L, 0L, "a", T1))),
                snapshot(Map.of()));
        assertThat(d.created()).isZero();
        assertThat(d.deleted()).isOne();
        assertThat(d.mutated()).isZero();
    }

    @Test
    void identicalSnapshotsHaveNoDrift() {
        Map<Long, CachedNode> byId = Map.of(1L, node(1L, 0L, "a", T1), 2L, node(2L, 0L, "b", T1));
        DriftCounters d = SnapshotDiff.diff(snapshot(byId), snapshot(byId));
        assertThat(d.total()).isZero();
    }

    @Test
    void nullOldSnapThrows() {
        assertThatNullPointerException()
                .isThrownBy(() -> SnapshotDiff.diff(null, snapshot(Map.of())))
                .withMessage("oldSnap");
    }

    @Test
    void nullNewSnapThrows() {
        assertThatNullPointerException()
                .isThrownBy(() -> SnapshotDiff.diff(snapshot(Map.of()), null))
                .withMessage("newSnap");
    }

    @Test
    void mixedChangesAreClassified() {
        Map<Long, CachedNode> oldById = Map.of(
                1L, node(1L, 0L, "a", T1),
                2L, node(2L, 0L, "b", T1),
                3L, node(3L, 0L, "c", T1));
        Map<Long, CachedNode> newById = Map.of(
                1L, node(1L, 0L, "a", T1),         // identical
                2L, node(2L, 0L, "b-renamed", T2), // mutated
                4L, node(4L, 0L, "d", T1));        // created

        DriftCounters d = SnapshotDiff.diff(snapshot(oldById), snapshot(newById));
        assertThat(d.created()).isOne();
        assertThat(d.deleted()).isOne();
        assertThat(d.mutated()).isOne();
    }
}

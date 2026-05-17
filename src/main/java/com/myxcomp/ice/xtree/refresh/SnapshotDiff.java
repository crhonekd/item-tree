package com.myxcomp.ice.xtree.refresh;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.TreeSnapshot;

import java.util.Map;
import java.util.Objects;

public final class SnapshotDiff {

    private SnapshotDiff() {}

    public static DriftCounters diff(TreeSnapshot oldSnap, TreeSnapshot newSnap) {
        Objects.requireNonNull(oldSnap, "oldSnap");
        Objects.requireNonNull(newSnap, "newSnap");
        DriftCounters d = new DriftCounters();
        Map<Long, CachedNode> oldById = oldSnap.byId();
        Map<Long, CachedNode> newById = newSnap.byId();

        for (Map.Entry<Long, CachedNode> e : newById.entrySet()) {
            CachedNode prior = oldById.get(e.getKey());
            if (prior == null) {
                d.incrementCreated();
            } else if (!Objects.equals(prior, e.getValue())) {
                d.incrementMutated();
            }
        }
        for (Long id : oldById.keySet()) {
            if (!newById.containsKey(id)) {
                d.incrementDeleted();
            }
        }
        return d;
    }
}

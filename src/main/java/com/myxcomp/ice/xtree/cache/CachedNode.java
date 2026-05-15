package com.myxcomp.ice.xtree.cache;

import java.time.Instant;
import java.util.Objects;

public record CachedNode(
        long itemTreeId,
        Long parentId,
        String name,
        String type,
        Instant lastUpdate,
        String lastUpdateUser
) {
    public CachedNode {
        Objects.requireNonNull(parentId, "parentId");
    }
}

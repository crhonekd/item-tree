package com.myxcomp.ice.xtree.cache;

import java.time.Instant;

public record CachedNode(
        long itemTreeId,
        Long parentId,
        String name,
        String type,
        Instant lastUpdate,
        String lastUpdateUser
) {}

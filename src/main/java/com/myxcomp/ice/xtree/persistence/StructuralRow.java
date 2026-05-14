package com.myxcomp.ice.xtree.persistence;

import java.time.Instant;

public record StructuralRow(
        long itemTreeId,
        Long parentId,
        String name,
        String type,
        Instant lastUpdate,
        String lastUpdateUser
) {}

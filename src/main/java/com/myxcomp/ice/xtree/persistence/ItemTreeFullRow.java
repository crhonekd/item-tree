package com.myxcomp.ice.xtree.persistence;

import java.time.Instant;
import java.util.Objects;

/**
 * Combined structural + payload row used by the copy operation.
 *
 * <p>{@code parentId} is required (never null; {@code 0} for the root); {@code json} and
 * {@code xml} are nullable. Stored timestamps are UTC {@code Instant}s (design §14).
 */
public record ItemTreeFullRow(
        long itemTreeId,
        Long parentId,
        String name,
        String type,
        String json,
        String xml,
        Instant lastUpdate,
        String lastUpdateUser
) {
    public ItemTreeFullRow {
        Objects.requireNonNull(parentId, "parentId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(lastUpdate, "lastUpdate");
        Objects.requireNonNull(lastUpdateUser, "lastUpdateUser");
    }
}

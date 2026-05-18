package com.myxcomp.ice.xtree.messaging.event.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/**
 * Payload for a MOVE operation.
 *
 * @param oldParentId carried for log/debug context only; not passed to
 *                    {@link com.myxcomp.ice.xtree.cache.TreeCache#applyMove}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MovePayload(
        long itemTreeId,
        long oldParentId,
        long newParentId,
        Instant lastUpdate,
        String lastUpdateUser
) implements EventPayload {}

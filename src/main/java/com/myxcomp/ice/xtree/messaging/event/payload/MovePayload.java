package com.myxcomp.ice.xtree.messaging.event.payload;

import java.time.Instant;

public record MovePayload(
        long itemTreeId,
        long oldParentId,
        long newParentId,
        Instant lastUpdate,
        String lastUpdateUser
) implements EventPayload {}

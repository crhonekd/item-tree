package com.myxcomp.ice.xtree.messaging.event.payload;

import java.time.Instant;

public record CreatePayload(
        long itemTreeId,
        long parentId,
        String name,
        String type,
        Instant lastUpdate,
        String lastUpdateUser
) implements EventPayload {}

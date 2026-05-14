package com.myxcomp.ice.xtree.messaging.event.payload;

import java.time.Instant;

public record UpdatePayload(
        long itemTreeId,
        Instant lastUpdate,
        String lastUpdateUser
) implements EventPayload {}

package com.myxcomp.ice.xtree.messaging.event.payload;

import java.time.Instant;

public record RenamePayload(
        long itemTreeId,
        String newName,
        Instant lastUpdate,
        String lastUpdateUser
) implements EventPayload {}

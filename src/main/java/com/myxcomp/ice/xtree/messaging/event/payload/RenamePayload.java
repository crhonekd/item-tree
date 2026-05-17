package com.myxcomp.ice.xtree.messaging.event.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RenamePayload(
        long itemTreeId,
        String newName,
        Instant lastUpdate,
        String lastUpdateUser
) implements EventPayload {}

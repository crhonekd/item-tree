package com.myxcomp.ice.xtree.messaging.event.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UpdatePayload(
        long itemTreeId,
        Instant lastUpdate,
        String lastUpdateUser
) implements EventPayload {}

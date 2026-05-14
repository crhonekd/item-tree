package com.myxcomp.ice.xtree.messaging.event;

import com.myxcomp.ice.xtree.messaging.event.payload.EventPayload;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.time.Instant;

@Value
@Builder
@Jacksonized
public class TreeMutationEvent {

    String eventId;
    String instanceId;
    long sequence;
    Instant occurredAt;
    String iceUser;
    String impersonatedUser;
    OperationType operationType;
    EventPayload payload;
}

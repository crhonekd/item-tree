package com.myxcomp.ice.xtree.messaging.event;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.myxcomp.ice.xtree.messaging.event.payload.EventPayload;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * Envelope for a single tree-mutation event broadcast over the Solace JMS bus.
 *
 * <p>Serialization uses Jackson's standard property serializer (fields written as plain
 * JSON properties; {@code operationType} appears at the envelope level only).
 * Deserialization is handled by {@link TreeMutationEventDeserializer}, which reads the
 * sibling {@code operationType} field to determine the concrete {@link EventPayload}
 * subtype — keeping {@code operationType} exclusively at the envelope level in the wire
 * format.
 */
@Value
@Builder
@JsonDeserialize(using = TreeMutationEventDeserializer.class)
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

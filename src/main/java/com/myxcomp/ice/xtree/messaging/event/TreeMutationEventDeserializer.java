package com.myxcomp.ice.xtree.messaging.event;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.myxcomp.ice.xtree.messaging.event.payload.CreatePayload;
import com.myxcomp.ice.xtree.messaging.event.payload.DeletePayload;
import com.myxcomp.ice.xtree.messaging.event.payload.EventPayload;
import com.myxcomp.ice.xtree.messaging.event.payload.MovePayload;
import com.myxcomp.ice.xtree.messaging.event.payload.RenamePayload;
import com.myxcomp.ice.xtree.messaging.event.payload.UpdatePayload;

import java.io.IOException;
import java.time.Instant;

/**
 * Custom deserializer for {@link TreeMutationEvent}.
 *
 * <p>Reads the full envelope JSON object as a tree, determines the concrete payload type
 * from the sibling {@code operationType} field, and constructs the event via its builder.
 *
 * <p>This replaces Jackson's {@code EXTERNAL_PROPERTY} polymorphic mechanism, which
 * — despite being the conceptually correct annotation — writes the type discriminator
 * <em>inside</em> the serialised payload object during serialization.  Handling the
 * dispatch here keeps the wire format clean: {@code operationType} lives exclusively at
 * the envelope level.
 */
class TreeMutationEventDeserializer extends StdDeserializer<TreeMutationEvent> {

    TreeMutationEventDeserializer() {
        super(TreeMutationEvent.class);
    }

    @Override
    public TreeMutationEvent deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode root = p.getCodec().readTree(p);

        // operationType is required; EXTERNAL_PROPERTY would embed it in the payload on serialization
        JsonNode opNode = root.get("operationType");
        if (opNode == null || opNode.isNull()) {
            throw new JsonMappingException(p, "Missing required field 'operationType'");
        }
        String operationTypeText = opNode.asText();

        // Reject unknown enum values with a clear mapping exception instead of a raw IllegalArgumentException
        OperationType operationType;
        try {
            operationType = OperationType.valueOf(operationTypeText);
        } catch (IllegalArgumentException e) {
            throw new JsonMappingException(p, "Unknown operationType: '" + operationTypeText + "'");
        }

        // occurredAt is required; sequence defaults to 0 if absent (used only for gap detection)
        JsonNode occurredAtNode = root.get("occurredAt");
        if (occurredAtNode == null || occurredAtNode.isNull()) {
            throw new JsonMappingException(p, "Missing required field 'occurredAt'");
        }
        Instant occurredAt;
        try {
            occurredAt = Instant.parse(occurredAtNode.asText());
        } catch (java.time.format.DateTimeParseException e) {
            throw new JsonMappingException(p, "Invalid occurredAt value: '" + occurredAtNode.asText() + "'");
        }

        JsonNode payloadNode = root.get("payload");
        if (payloadNode == null || payloadNode.isNull()) {
            throw new JsonMappingException(p, "Missing required field 'payload'");
        }
        EventPayload payload = deserializePayload(p, payloadNode, operationType);

        return TreeMutationEvent.builder()
                .eventId(textOrNull(root, "eventId"))
                .instanceId(textOrNull(root, "instanceId"))
                .sequence(root.path("sequence").asLong())
                .occurredAt(occurredAt)
                .iceUser(textOrNull(root, "iceUser"))
                .impersonatedUser(textOrNull(root, "impersonatedUser"))
                .operationType(operationType)
                .payload(payload)
                .build();
    }

    private EventPayload deserializePayload(
            JsonParser p, JsonNode payloadNode, OperationType operationType) throws IOException {
        return switch (operationType) {
            case CREATE  -> p.getCodec().treeToValue(payloadNode, CreatePayload.class);
            case UPDATE  -> p.getCodec().treeToValue(payloadNode, UpdatePayload.class);
            case MOVE    -> p.getCodec().treeToValue(payloadNode, MovePayload.class);
            case RENAME  -> p.getCodec().treeToValue(payloadNode, RenamePayload.class);
            case DELETE  -> p.getCodec().treeToValue(payloadNode, DeletePayload.class);
        };
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return (child == null || child.isNull()) ? null : child.asText();
    }
}

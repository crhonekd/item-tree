package com.myxcomp.ice.xtree.messaging.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.myxcomp.ice.xtree.messaging.event.payload.CreatePayload;
import com.myxcomp.ice.xtree.messaging.event.payload.DeletePayload;
import com.myxcomp.ice.xtree.messaging.event.payload.EventPayload;
import com.myxcomp.ice.xtree.messaging.event.payload.MovePayload;
import com.myxcomp.ice.xtree.messaging.event.payload.RenamePayload;
import com.myxcomp.ice.xtree.messaging.event.payload.UpdatePayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TreeMutationEventTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private TreeMutationEvent buildEvent(OperationType opType, EventPayload payload) {
        return TreeMutationEvent.builder()
                .eventId("evt-uuid-1")
                .instanceId("inst-uuid-1")
                .sequence(42L)
                .occurredAt(Instant.parse("2026-05-13T14:30:00Z"))
                .iceUser("alice")
                .impersonatedUser(null)
                .operationType(opType)
                .payload(payload)
                .build();
    }

    private TreeMutationEvent roundTrip(TreeMutationEvent event) throws Exception {
        String json = mapper.writeValueAsString(event);
        return mapper.readValue(json, TreeMutationEvent.class);
    }

    @Nested
    class CreateRoundTrip {
        @Test
        void payload_is_deserialized_as_CreatePayload() throws Exception {
            var payload = new CreatePayload(100L, 1L, "NewReport", "Report",
                    Instant.parse("2026-05-13T14:30:00Z"), "alice");
            var event = buildEvent(OperationType.CREATE, payload);

            TreeMutationEvent restored = roundTrip(event);

            assertThat(restored.getOperationType()).isEqualTo(OperationType.CREATE);
            assertThat(restored.getPayload()).isInstanceOf(CreatePayload.class);
            CreatePayload rp = (CreatePayload) restored.getPayload();
            assertThat(rp.itemTreeId()).isEqualTo(100L);
            assertThat(rp.parentId()).isEqualTo(1L);
            assertThat(rp.name()).isEqualTo("NewReport");
            assertThat(rp.type()).isEqualTo("Report");
            assertThat(rp.lastUpdate()).isEqualTo(Instant.parse("2026-05-13T14:30:00Z"));
            assertThat(rp.lastUpdateUser()).isEqualTo("alice");
        }

        @Test
        void envelope_fields_survive_round_trip() throws Exception {
            var payload = new CreatePayload(100L, 1L, "X", "Folder",
                    Instant.parse("2026-05-13T14:30:00Z"), "alice");
            var event = buildEvent(OperationType.CREATE, payload);

            TreeMutationEvent restored = roundTrip(event);

            assertThat(restored.getEventId()).isEqualTo("evt-uuid-1");
            assertThat(restored.getInstanceId()).isEqualTo("inst-uuid-1");
            assertThat(restored.getSequence()).isEqualTo(42L);
            assertThat(restored.getOccurredAt()).isEqualTo(Instant.parse("2026-05-13T14:30:00Z"));
            assertThat(restored.getIceUser()).isEqualTo("alice");
            assertThat(restored.getImpersonatedUser()).isNull();
        }
    }

    @Nested
    class UpdateRoundTrip {
        @Test
        void payload_is_deserialized_as_UpdatePayload() throws Exception {
            var payload = new UpdatePayload(100L, Instant.parse("2026-05-13T15:00:00Z"), "bob");
            TreeMutationEvent restored = roundTrip(buildEvent(OperationType.UPDATE, payload));

            assertThat(restored.getOperationType()).isEqualTo(OperationType.UPDATE);
            assertThat(restored.getPayload()).isInstanceOf(UpdatePayload.class);
            UpdatePayload rp = (UpdatePayload) restored.getPayload();
            assertThat(rp.itemTreeId()).isEqualTo(100L);
            assertThat(rp.lastUpdate()).isEqualTo(Instant.parse("2026-05-13T15:00:00Z"));
            assertThat(rp.lastUpdateUser()).isEqualTo("bob");
        }
    }

    @Nested
    class MoveRoundTrip {
        @Test
        void payload_is_deserialized_as_MovePayload() throws Exception {
            var payload = new MovePayload(100L, 1L, 5L,
                    Instant.parse("2026-05-13T15:00:00Z"), "carol");
            TreeMutationEvent restored = roundTrip(buildEvent(OperationType.MOVE, payload));

            assertThat(restored.getOperationType()).isEqualTo(OperationType.MOVE);
            assertThat(restored.getPayload()).isInstanceOf(MovePayload.class);
            MovePayload rp = (MovePayload) restored.getPayload();
            assertThat(rp.itemTreeId()).isEqualTo(100L);
            assertThat(rp.oldParentId()).isEqualTo(1L);
            assertThat(rp.newParentId()).isEqualTo(5L);
            assertThat(rp.lastUpdateUser()).isEqualTo("carol");
        }
    }

    @Nested
    class RenameRoundTrip {
        @Test
        void payload_is_deserialized_as_RenamePayload() throws Exception {
            var payload = new RenamePayload(100L, "RenamedItem",
                    Instant.parse("2026-05-13T15:00:00Z"), "dave");
            TreeMutationEvent restored = roundTrip(buildEvent(OperationType.RENAME, payload));

            assertThat(restored.getOperationType()).isEqualTo(OperationType.RENAME);
            assertThat(restored.getPayload()).isInstanceOf(RenamePayload.class);
            RenamePayload rp = (RenamePayload) restored.getPayload();
            assertThat(rp.itemTreeId()).isEqualTo(100L);
            assertThat(rp.newName()).isEqualTo("RenamedItem");
            assertThat(rp.lastUpdateUser()).isEqualTo("dave");
        }
    }

    @Nested
    class DeleteRoundTrip {
        @Test
        void payload_is_deserialized_as_DeletePayload() throws Exception {
            var payload = new DeletePayload(List.of(100L, 101L, 102L));
            TreeMutationEvent restored = roundTrip(buildEvent(OperationType.DELETE, payload));

            assertThat(restored.getOperationType()).isEqualTo(OperationType.DELETE);
            assertThat(restored.getPayload()).isInstanceOf(DeletePayload.class);
            DeletePayload rp = (DeletePayload) restored.getPayload();
            assertThat(rp.deletedIds()).containsExactly(100L, 101L, 102L);
        }

        @Test
        void delete_payload_with_single_id() throws Exception {
            var payload = new DeletePayload(List.of(999L));
            TreeMutationEvent restored = roundTrip(buildEvent(OperationType.DELETE, payload));

            assertThat(((DeletePayload) restored.getPayload()).deletedIds()).containsExactly(999L);
        }
    }

    @Nested
    class WireFormat {

        @Test
        void operationType_is_sibling_of_payload_in_json() throws Exception {
            var payload = new CreatePayload(100L, 1L, "NewReport", "Report",
                    Instant.parse("2026-05-13T14:30:00Z"), "alice");
            String json = mapper.writeValueAsString(buildEvent(OperationType.CREATE, payload));

            assertThat(json).contains("\"operationType\":\"CREATE\"");
            assertThat(json).contains("\"payload\":");

            JsonNode root = mapper.readTree(json);
            assertThat(root.has("operationType"))
                    .as("operationType must be a top-level envelope field")
                    .isTrue();
            assertThat(root.has("payload"))
                    .as("payload must be a top-level envelope field")
                    .isTrue();
        }

        @Test
        void payload_does_not_contain_operationType_discriminator() throws Exception {
            var payload = new UpdatePayload(100L, Instant.parse("2026-05-13T15:00:00Z"), "bob");
            String json = mapper.writeValueAsString(buildEvent(OperationType.UPDATE, payload));

            JsonNode root = mapper.readTree(json);
            JsonNode payloadNode = root.get("payload");

            assertThat(payloadNode).isNotNull();
            assertThat(payloadNode.has("operationType"))
                    .as("operationType must NOT appear inside the payload object — it belongs at the envelope level")
                    .isFalse();
        }

        @Test
        void all_envelope_fields_are_present_in_json() throws Exception {
            var payload = new CreatePayload(100L, 1L, "NewReport", "Report",
                    Instant.parse("2026-05-13T14:30:00Z"), "alice");
            String json = mapper.writeValueAsString(buildEvent(OperationType.CREATE, payload));

            JsonNode root = mapper.readTree(json);
            assertThat(root.has("eventId")).as("eventId").isTrue();
            assertThat(root.has("instanceId")).as("instanceId").isTrue();
            assertThat(root.has("sequence")).as("sequence").isTrue();
            assertThat(root.has("occurredAt")).as("occurredAt").isTrue();
            assertThat(root.has("iceUser")).as("iceUser").isTrue();
            assertThat(root.has("operationType")).as("operationType").isTrue();
            assertThat(root.has("payload")).as("payload").isTrue();
        }
    }

    @Test
    void instant_is_serialized_as_iso8601_with_z_suffix() throws Exception {
        var payload = new UpdatePayload(1L, Instant.parse("2026-05-13T14:30:00Z"), "alice");
        String json = mapper.writeValueAsString(buildEvent(OperationType.UPDATE, payload));

        assertThat(json).contains("2026-05-13T14:30:00Z");
    }

    @Test
    void impersonatedUser_null_round_trips() throws Exception {
        var payload = new UpdatePayload(1L, Instant.parse("2026-05-13T14:30:00Z"), "alice");
        TreeMutationEvent event = TreeMutationEvent.builder()
                .eventId("e")
                .instanceId("i")
                .sequence(1L)
                .occurredAt(Instant.parse("2026-05-13T14:30:00Z"))
                .iceUser("alice")
                .impersonatedUser(null)
                .operationType(OperationType.UPDATE)
                .payload(payload)
                .build();

        TreeMutationEvent restored = roundTrip(event);
        assertThat(restored.getImpersonatedUser()).isNull();
    }
}

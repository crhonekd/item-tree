package com.myxcomp.ice.xtree.messaging.event.payload;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CopyPayloadTest {

    private final ObjectMapper om = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void roundTrip() throws Exception {
        Instant t = Instant.parse("2026-05-24T10:00:00Z");
        CopyPayload original = new CopyPayload(List.of(
                new CopyPayload.CopiedNode(100L, 10L, "root-copy", "Folder", t, "alice"),
                new CopyPayload.CopiedNode(101L, 100L, "child", "Report", t, "alice")
        ));
        String json = om.writeValueAsString(original);
        CopyPayload parsed = om.readValue(json, CopyPayload.class);
        assertThat(parsed).isEqualTo(original);
    }

    @Test
    void unknownFieldsIgnoredOnEnvelope() throws Exception {
        String json = """
                { "newNodes": [
                    { "itemTreeId": 100, "parentId": 10, "name": "x", "type": "Folder",
                      "lastUpdate": "2026-05-24T10:00:00Z", "lastUpdateUser": "alice",
                      "unknownField": "ignored" }
                  ],
                  "extraEnvelopeField": "ignored" }
                """;
        CopyPayload parsed = om.readValue(json, CopyPayload.class);
        assertThat(parsed.newNodes()).hasSize(1);
        assertThat(parsed.newNodes().get(0).itemTreeId()).isEqualTo(100L);
    }
}

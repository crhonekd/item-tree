package com.myxcomp.ice.xtree.messaging.dev;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.myxcomp.ice.xtree.config.SolaceProperties;
import com.myxcomp.ice.xtree.messaging.event.OperationType;
import com.myxcomp.ice.xtree.messaging.event.TreeMutationEvent;
import com.myxcomp.ice.xtree.messaging.event.payload.UpdatePayload;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class LocalLoopbackEventPublisherTest {

    private static final String TOPIC = "BC/ICE/ITEMTREE";
    private static final Instant T = Instant.parse("2026-05-17T10:00:00Z");

    private ObjectMapper mapper;
    private InMemoryEventBus bus;
    private MeterRegistry registry;
    private LocalLoopbackEventPublisher publisher;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        bus = mock(InMemoryEventBus.class);
        registry = new SimpleMeterRegistry();
        publisher = new LocalLoopbackEventPublisher(mapper, bus, new SolaceProperties(TOPIC,
                        new SolaceProperties.Reconnect(Duration.ofMinutes(1), Duration.ofHours(1)),
                        new SolaceProperties.Health(Duration.ofHours(4))), registry);
    }

    private TreeMutationEvent updateEvent() {
        return TreeMutationEvent.builder()
                .eventId("e").instanceId("local").sequence(1L).occurredAt(T)
                .iceUser("u").operationType(OperationType.UPDATE)
                .payload(new UpdatePayload(100L, T, "u")).build();
    }

    @Test
    void publish_serializes_and_routes_through_bus() {
        publisher.publish(updateEvent());

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(bus).publish(eq(TOPIC), payload.capture());
        assertThat(payload.getValue()).contains("\"operationType\":\"UPDATE\"");
        assertThat(registry.counter("itemtree.event.published", "op", "UPDATE").count()).isOne();
    }

    @Test
    void publish_swallows_serialization_failure_and_increments_counter() throws Exception {
        ObjectMapper failing = mock(ObjectMapper.class);
        given(failing.writeValueAsString(org.mockito.ArgumentMatchers.any()))
                .willThrow(new JsonProcessingException("boom") {});
        LocalLoopbackEventPublisher p2 = new LocalLoopbackEventPublisher(
                failing, bus, new SolaceProperties(TOPIC,
                        new SolaceProperties.Reconnect(Duration.ofMinutes(1), Duration.ofHours(1)),
                        new SolaceProperties.Health(Duration.ofHours(4))), registry);

        assertThatCode(() -> p2.publish(updateEvent())).doesNotThrowAnyException();

        verifyNoInteractions(bus);
        assertThat(registry.counter("itemtree.event.publish.serialization_failure").count()).isOne();
        assertThat(registry.counter("itemtree.event.published", "op", "UPDATE").count()).isZero();
    }

    @Test
    void publish_swallows_bus_exception_and_increments_failure_counter() {
        willThrow(new RuntimeException("bus down"))
                .given(bus).publish(anyString(), anyString());

        assertThatCode(() -> publisher.publish(updateEvent())).doesNotThrowAnyException();

        assertThat(registry.counter("itemtree.event.publish.failure").count()).isOne();
    }
}

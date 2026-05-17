package com.myxcomp.ice.xtree.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.myxcomp.ice.xtree.common.InstanceIdProvider;
import com.myxcomp.ice.xtree.messaging.event.OperationType;
import com.myxcomp.ice.xtree.messaging.event.TreeMutationEvent;
import com.myxcomp.ice.xtree.messaging.event.payload.CreatePayload;
import com.myxcomp.ice.xtree.messaging.event.payload.UpdatePayload;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EventConsumerServiceTest {

    private static final Instant T = Instant.parse("2026-05-17T10:00:00Z");
    private static final String LOCAL = "local-instance";
    private static final String PEER  = "peer-instance";

    private ObjectMapper mapper;
    private MeterRegistry registry;
    private EventDispatcher dispatcher;
    private InstanceIdProvider idProvider;
    private EventConsumerService consumer;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        registry = new SimpleMeterRegistry();
        dispatcher = mock(EventDispatcher.class);
        idProvider = mock(InstanceIdProvider.class);
        when(idProvider.getInstanceId()).thenReturn(LOCAL);
        consumer = new EventConsumerService(mapper, dispatcher, idProvider, registry);
    }

    private String json(TreeMutationEvent e) throws Exception {
        return mapper.writeValueAsString(e);
    }

    private TreeMutationEvent peerCreate(long seq) {
        return TreeMutationEvent.builder()
                .eventId("e-" + seq).instanceId(PEER).sequence(seq).occurredAt(T)
                .iceUser("u").operationType(OperationType.CREATE)
                .payload(new CreatePayload(100L + seq, 1L, "N", "Folder", T, "u"))
                .build();
    }

    @Test
    void happy_path_dispatches_and_increments_consumed_counter() throws Exception {
        consumer.processPayload(json(peerCreate(1L)));

        ArgumentCaptor<TreeMutationEvent> cap = ArgumentCaptor.forClass(TreeMutationEvent.class);
        verify(dispatcher).dispatch(cap.capture());
        assertThat(cap.getValue().getOperationType()).isEqualTo(OperationType.CREATE);
        assertThat(registry.counter("itemtree.event.consumed", "op", "CREATE").count()).isOne();
    }

    @Test
    void self_echo_is_dropped_and_self_dropped_counter_incremented() throws Exception {
        TreeMutationEvent self = TreeMutationEvent.builder()
                .eventId("e").instanceId(LOCAL).sequence(1L).occurredAt(T)
                .iceUser("u").operationType(OperationType.UPDATE)
                .payload(new UpdatePayload(100L, T, "u")).build();
        consumer.processPayload(json(self));

        verifyNoInteractions(dispatcher);
        assertThat(registry.counter("itemtree.event.self_dropped").count()).isOne();
        assertThat(registry.counter("itemtree.event.consumed", "op", "UPDATE").count()).isZero();
    }

    @Test
    void malformed_json_increments_deserialize_failure_and_does_not_throw() {
        consumer.processPayload("{ not valid json");

        verifyNoInteractions(dispatcher);
        assertThat(registry.counter("itemtree.event.consume.deserialize.failure").count()).isOne();
    }

    @Test
    void dispatch_failure_increments_apply_failure_and_does_not_throw() throws Exception {
        willThrow(new RuntimeException("boom")).given(dispatcher).dispatch(any());
        consumer.processPayload(json(peerCreate(1L)));

        assertThat(registry.counter("itemtree.event.consume.apply.failure").count()).isOne();
        assertThat(registry.counter("itemtree.event.consumed", "op", "CREATE").count()).isZero();
    }

    @Test
    void sequence_gap_increments_counter_once_per_gap() throws Exception {
        consumer.processPayload(json(peerCreate(1L)));
        consumer.processPayload(json(peerCreate(2L)));
        consumer.processPayload(json(peerCreate(5L))); // gap between 2 and 5

        assertThat(registry.counter("itemtree.event.sequence.gap").count()).isOne();
        assertThat(registry.counter("itemtree.event.consumed", "op", "CREATE").count()).isEqualTo(3.0);
    }

    @Test
    void first_event_from_an_instance_is_not_a_gap() throws Exception {
        consumer.processPayload(json(peerCreate(42L))); // starting at 42, not 1
        assertThat(registry.counter("itemtree.event.sequence.gap").count()).isZero();
    }

    @Test
    void out_of_order_or_duplicate_sequence_does_not_count_as_gap() throws Exception {
        consumer.processPayload(json(peerCreate(5L)));
        consumer.processPayload(json(peerCreate(3L)));
        consumer.processPayload(json(peerCreate(5L)));
        assertThat(registry.counter("itemtree.event.sequence.gap").count()).isZero();
    }

    @Test
    void null_payload_throws_NPE() {
        assertThatThrownBy(() -> consumer.processPayload(null))
                .isInstanceOf(NullPointerException.class);
    }
}

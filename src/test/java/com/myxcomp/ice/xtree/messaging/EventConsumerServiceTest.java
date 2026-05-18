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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

    @Nested
    class ConcurrencyTests {

        /**
         * Two threads each deliver a strictly-increasing sequence of events from the same
         * PEER instance simultaneously. The test asserts:
         * <ul>
         *   <li>No {@link NullPointerException} is thrown (was the risk with get/put).</li>
         *   <li>The recorded gap count does not exceed the maximum number of gaps that
         *       could legitimately occur given the two interleaved sequences — i.e. gap
         *       counting is never double-incremented for the same slot.</li>
         * </ul>
         *
         * <p>The {@link CyclicBarrier} forces both threads to reach the start line before
         * either begins processing, maximising the chance of a real concurrent collision.
         */
        @Test
        void concurrent_events_from_same_peer_do_not_throw_and_gap_count_is_stable()
                throws Exception {
            // Thread A sends sequences 1,3,5,7,9  (gaps at 3,5,7,9 relative to each other)
            // Thread B sends sequences 2,4,6,8,10 (interleaved)
            // Together they form a contiguous run 1-10 — worst-case 0 gaps if perfectly
            // ordered, but because threads race the actual count may vary. What must NOT
            // happen is an NPE or a count > 9 (the maximum possible gaps for 10 events
            // from a single source seen for the first time).
            int eventsPerThread = 5;
            int totalEvents     = eventsPerThread * 2;

            List<String> threadAPayloads = new ArrayList<>();
            List<String> threadBPayloads = new ArrayList<>();
            for (int i = 0; i < eventsPerThread; i++) {
                threadAPayloads.add(mapper.writeValueAsString(peerCreate(2L * i + 1)));  // 1,3,5,7,9
                threadBPayloads.add(mapper.writeValueAsString(peerCreate(2L * i + 2)));  // 2,4,6,8,10
            }

            CyclicBarrier startGate = new CyclicBarrier(2);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<Void> futureA = pool.submit(() -> {
                    startGate.await(5, TimeUnit.SECONDS);
                    for (String payload : threadAPayloads) {
                        consumer.processPayload(payload);
                    }
                    return null;
                });
                Future<Void> futureB = pool.submit(() -> {
                    startGate.await(5, TimeUnit.SECONDS);
                    for (String payload : threadBPayloads) {
                        consumer.processPayload(payload);
                    }
                    return null;
                });

                // Propagate any exception (including NPE) as a test failure.
                futureA.get(10, TimeUnit.SECONDS);
                futureB.get(10, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }

            double gapCount = registry.counter("itemtree.event.sequence.gap").count();
            // At most n-1 gap comparisons for n sequential events from one source.
            double maxPossibleGaps = (double) totalEvents - 1;
            assertThat(gapCount).isLessThanOrEqualTo(maxPossibleGaps);
        }
    }
}

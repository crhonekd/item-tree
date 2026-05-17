package com.myxcomp.ice.xtree.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxcomp.ice.xtree.common.InstanceIdProvider;
import com.myxcomp.ice.xtree.messaging.event.TreeMutationEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Entry point for inbound {@link TreeMutationEvent}s (design §6).
 *
 * <p>Single public method {@link #processPayload(String)} accepts a JSON-encoded envelope,
 * deserialises it, drops self-echoes by instanceId, tracks sequence gaps, and delegates
 * to {@link EventDispatcher}. Every failure path emits a metric; the method never throws.
 */
@Component
public class EventConsumerService {

    private static final Logger log = LoggerFactory.getLogger(EventConsumerService.class);

    private final ObjectMapper objectMapper;
    private final EventDispatcher dispatcher;
    private final InstanceIdProvider instanceIdProvider;
    private final MeterRegistry meterRegistry;

    private final ConcurrentHashMap<String, Long> lastSequenceByInstance = new ConcurrentHashMap<>();

    public EventConsumerService(ObjectMapper objectMapper,
                                EventDispatcher dispatcher,
                                InstanceIdProvider instanceIdProvider,
                                MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.dispatcher = dispatcher;
        this.instanceIdProvider = instanceIdProvider;
        this.meterRegistry = meterRegistry;
    }

    public void processPayload(String payload) {
        Objects.requireNonNull(payload, "payload");

        TreeMutationEvent event;
        try {
            event = objectMapper.readValue(payload, TreeMutationEvent.class);
        } catch (Exception e) {
            meterRegistry.counter("itemtree.event.consume.deserialize.failure").increment();
            log.warn("Failed to deserialize event payload (prefix='{}'): {}",
                    payload.substring(0, Math.min(80, payload.length())), e.toString());
            return;
        }

        if (instanceIdProvider.getInstanceId().equals(event.getInstanceId())) {
            meterRegistry.counter("itemtree.event.self_dropped").increment();
            return;
        }

        trackSequenceGap(event);

        try {
            dispatcher.dispatch(event);
            meterRegistry.counter("itemtree.event.consumed", "op", event.getOperationType().name()).increment();
        } catch (RuntimeException e) {
            meterRegistry.counter("itemtree.event.consume.apply.failure").increment();
            log.warn("Apply failed for event id={} op={}: {}",
                    event.getEventId(), event.getOperationType(), e.toString());
        }
    }

    private void trackSequenceGap(TreeMutationEvent event) {
        String src = event.getInstanceId();
        if (src == null) return;
        long current = event.getSequence();
        Long previous = lastSequenceByInstance.get(src);
        if (previous == null) {
            lastSequenceByInstance.put(src, current);
            return;
        }
        if (current > previous + 1) {
            meterRegistry.counter("itemtree.event.sequence.gap").increment();
        }
        if (current > previous) {
            lastSequenceByInstance.put(src, current);
        }
    }
}

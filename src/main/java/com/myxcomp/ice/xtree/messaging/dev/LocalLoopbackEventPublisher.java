package com.myxcomp.ice.xtree.messaging.dev;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxcomp.ice.xtree.config.SolaceProperties;
import com.myxcomp.ice.xtree.messaging.EventPublisher;
import com.myxcomp.ice.xtree.messaging.event.TreeMutationEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Phase A {@link EventPublisher}: serialises {@link TreeMutationEvent} to JSON and routes
 * through {@link InMemoryEventBus} on the topic configured by {@link SolaceProperties}.
 * Replaces the Phase 7 placeholder {@code NoOpEventPublisher}.
 */
@Component
@Profile("dev")
public class LocalLoopbackEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LocalLoopbackEventPublisher.class);

    private final ObjectMapper objectMapper;
    private final InMemoryEventBus bus;
    private final SolaceProperties solaceProperties;
    private final MeterRegistry meterRegistry;

    public LocalLoopbackEventPublisher(ObjectMapper objectMapper,
                                       InMemoryEventBus bus,
                                       SolaceProperties solaceProperties,
                                       MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.bus = bus;
        this.solaceProperties = solaceProperties;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void publish(TreeMutationEvent event) {
        Objects.requireNonNull(event, "event");

        String json;
        try {
            json = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            meterRegistry.counter("itemtree.event.publish.serialization_failure").increment();
            log.warn("Failed to serialize event id={} op={}: {}",
                    event.getEventId(), event.getOperationType(), e.toString());
            return;
        }

        try {
            bus.publish(solaceProperties.topic(), json);
            meterRegistry.counter("itemtree.event.published", "op", event.getOperationType().name()).increment();
        } catch (RuntimeException e) {
            meterRegistry.counter("itemtree.event.publish.failure").increment();
            log.warn("InMemoryEventBus.publish failed for event id={} op={}: {}",
                    event.getEventId(), event.getOperationType(), e.toString());
        }
    }
}

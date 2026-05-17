package com.myxcomp.ice.xtree.messaging.dev;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Phase A in-memory replacement for the Solace topic bus. Subscribers invoked synchronously
 * on the publisher thread. Subscriber exceptions are isolated.
 */
@Component
@Profile("dev")
public class InMemoryEventBus {

    private static final Logger log = LoggerFactory.getLogger(InMemoryEventBus.class);

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Consumer<String>>> subs =
            new ConcurrentHashMap<>();

    public void publish(String topic, String payload) {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(payload, "payload");
        List<Consumer<String>> handlers = subs.get(topic);
        if (handlers == null || handlers.isEmpty()) return;
        for (Consumer<String> h : handlers) {
            try {
                h.accept(payload);
            } catch (RuntimeException e) {
                log.warn("InMemoryEventBus subscriber on topic '{}' threw: {}", topic, e.toString());
            }
        }
    }

    public void subscribe(String topic, Consumer<String> handler) {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(handler, "handler");
        subs.computeIfAbsent(topic, t -> new CopyOnWriteArrayList<>()).add(handler);
    }
}

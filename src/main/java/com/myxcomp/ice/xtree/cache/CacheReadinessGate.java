package com.myxcomp.ice.xtree.cache;

import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class CacheReadinessGate {

    private final AtomicBoolean ready = new AtomicBoolean(false);
    private final ApplicationEventPublisher eventPublisher;

    public CacheReadinessGate(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public void markReady() {
        if (ready.compareAndSet(false, true)) {
            AvailabilityChangeEvent.publish(eventPublisher, this, ReadinessState.ACCEPTING_TRAFFIC);
        }
    }

    public boolean isReady() {
        return ready.get();
    }
}

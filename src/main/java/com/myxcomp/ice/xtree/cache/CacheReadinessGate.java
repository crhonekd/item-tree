package com.myxcomp.ice.xtree.cache;

import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class CacheReadinessGate {

    private volatile boolean ready = false;
    private final ApplicationEventPublisher eventPublisher;

    public CacheReadinessGate(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public void markReady() {
        ready = true;
        AvailabilityChangeEvent.publish(eventPublisher, this, ReadinessState.ACCEPTING_TRAFFIC);
    }

    public boolean isReady() {
        return ready;
    }
}

package com.myxcomp.ice.xtree.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CacheReadinessGateTest {

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Test
    void startsNotReady() {
        CacheReadinessGate gate = new CacheReadinessGate(eventPublisher);
        assertThat(gate.isReady()).isFalse();
    }

    @Test
    void markReadyFlipsFlag() {
        CacheReadinessGate gate = new CacheReadinessGate(eventPublisher);
        gate.markReady();
        assertThat(gate.isReady()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void markReadyPublishesAcceptingTrafficEvent() {
        CacheReadinessGate gate = new CacheReadinessGate(eventPublisher);
        gate.markReady();

        ArgumentCaptor<ApplicationEvent> captor = ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue())
                .isInstanceOf(AvailabilityChangeEvent.class)
                .extracting(e -> ((AvailabilityChangeEvent<?>) e).getState())
                .isEqualTo(ReadinessState.ACCEPTING_TRAFFIC);
    }

    @Test
    void markReadyIsIdempotent() {
        CacheReadinessGate gate = new CacheReadinessGate(eventPublisher);
        gate.markReady();
        gate.markReady();
        assertThat(gate.isReady()).isTrue();
        verify(eventPublisher, times(2)).publishEvent(any(ApplicationEvent.class));
    }
}

package com.myxcomp.ice.xtree.messaging;

import com.myxcomp.ice.xtree.common.TimeMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConnectionStateTrackerTest {

    private static final Instant T0 = Instant.parse("2026-05-18T10:00:00Z");

    private TimeMapper timeMapper;
    private SimpleMeterRegistry meterRegistry;
    private RecoveryListenerHook hook;
    private ConnectionStateTracker tracker;

    @BeforeEach
    void setUp() {
        timeMapper = mock(TimeMapper.class);
        meterRegistry = new SimpleMeterRegistry();
        hook = mock(RecoveryListenerHook.class);
        when(timeMapper.now()).thenReturn(T0);
        tracker = new ConnectionStateTracker(hook, timeMapper, meterRegistry,
                mock(ReconnectReconciler.class));
        tracker.registerWithHook();
    }

    @Test
    void registersItselfWithTheHook() {
        verify(hook).addRecoveryListener(tracker);
    }

    @Test
    void initialStateIsDisconnectedNoOutage() {
        assertThat(tracker.isConnected()).isFalse();
        assertThat(tracker.disconnectedAt()).isNull();
        assertThat(tracker.lastConnectedAt()).isNull();
        assertThat(tracker.lastEventReceivedAt()).isNull();
        assertThat(meterRegistry.get("itemtree.solace.connected").gauge().value()).isZero();
        assertThat(meterRegistry.get("itemtree.solace.outage_seconds").gauge().value()).isZero();
        assertThat(meterRegistry.get("itemtree.solace.last_event_age_seconds").gauge().value()).isZero();
    }

    @Nested
    class OnConnectionLost {

        @Test
        void setsDisconnectedAtAndIncrementsCounter() {
            tracker.onConnectionLost("itemtree");

            assertThat(tracker.disconnectedAt()).isEqualTo(T0);
            assertThat(tracker.isConnected()).isFalse();
            assertThat(meterRegistry.counter("itemtree.solace.connection_lost_total").count()).isOne();
        }

        @Test
        void secondLostWhileAlreadyDisconnectedLeavesDisconnectedAtUnchanged() {
            tracker.onConnectionLost("itemtree");
            Instant later = T0.plusSeconds(30);
            when(timeMapper.now()).thenReturn(later);

            tracker.onConnectionLost("itemtree");

            assertThat(tracker.disconnectedAt()).isEqualTo(T0);
            assertThat(meterRegistry.counter("itemtree.solace.connection_lost_total").count()).isEqualTo(2.0);
        }

        @Test
        void outageGaugeReflectsElapsedSeconds() {
            tracker.onConnectionLost("itemtree");
            when(timeMapper.now()).thenReturn(T0.plusSeconds(45));

            assertThat(meterRegistry.get("itemtree.solace.outage_seconds").gauge().value())
                    .isEqualTo(45.0);
        }
    }

    @Nested
    class OnConnectionRecovered {

        @Test
        void firstConnectSetsLastConnectedAtAndMarkConnected() {
            tracker.onConnectionRecovered("itemtree");

            assertThat(tracker.isConnected()).isTrue();
            assertThat(tracker.lastConnectedAt()).isEqualTo(T0);
            assertThat(tracker.disconnectedAt()).isNull();
            assertThat(meterRegistry.get("itemtree.solace.connected").gauge().value()).isOne();
            assertThat(meterRegistry.counter("itemtree.solace.connection_recovered_total").count()).isOne();
        }

        @Test
        void recoveryAfterPriorLossClearsDisconnectedAt() {
            tracker.onConnectionLost("itemtree");
            Instant later = T0.plusSeconds(30);
            when(timeMapper.now()).thenReturn(later);

            tracker.onConnectionRecovered("itemtree");

            assertThat(tracker.isConnected()).isTrue();
            assertThat(tracker.disconnectedAt()).isNull();
            assertThat(tracker.lastConnectedAt()).isEqualTo(later);
            assertThat(meterRegistry.get("itemtree.solace.outage_seconds").gauge().value()).isZero();
        }

        @Test
        void spuriousSecondRecoveryDoesNotCorruptState() {
            tracker.onConnectionRecovered("itemtree");
            tracker.onConnectionRecovered("itemtree");

            assertThat(tracker.isConnected()).isTrue();
            assertThat(meterRegistry.counter("itemtree.solace.connection_recovered_total").count())
                    .isEqualTo(2.0);
        }
    }

    @Nested
    class ReconcileWiring {

        private ReconnectReconciler reconciler;

        @BeforeEach
        void wireReconciler() {
            reconciler = mock(ReconnectReconciler.class);
            tracker = new ConnectionStateTracker(hook, timeMapper, meterRegistry, reconciler);
            tracker.registerWithHook();
        }

        @Test
        void firstConnectDoesNotReconcile() {
            tracker.onConnectionRecovered("itemtree");

            verify(reconciler, never()).reconcile(any());
        }

        @Test
        void recoveryAfterLossPassesOutageDurationToReconciler() {
            tracker.onConnectionLost("itemtree");
            when(timeMapper.now()).thenReturn(T0.plusSeconds(45));

            tracker.onConnectionRecovered("itemtree");

            verify(reconciler).reconcile(Duration.ofSeconds(45));
        }

        @Test
        void spuriousSecondRecoveryDoesNotCallReconcileAgain() {
            tracker.onConnectionLost("itemtree");
            when(timeMapper.now()).thenReturn(T0.plusSeconds(45));
            tracker.onConnectionRecovered("itemtree");
            reset(reconciler);

            tracker.onConnectionRecovered("itemtree");

            verify(reconciler, never()).reconcile(any());
        }
    }

    @Nested
    class RecordEventReceived {

        @Test
        void updatesLastEventReceivedAt() {
            tracker.recordEventReceived();

            assertThat(tracker.lastEventReceivedAt()).isEqualTo(T0);
        }

        @Test
        void lastEventAgeGaugeReflectsElapsedSeconds() {
            tracker.recordEventReceived();
            when(timeMapper.now()).thenReturn(T0.plusSeconds(7));

            assertThat(meterRegistry.get("itemtree.solace.last_event_age_seconds").gauge().value())
                    .isEqualTo(7.0);
        }
    }
}

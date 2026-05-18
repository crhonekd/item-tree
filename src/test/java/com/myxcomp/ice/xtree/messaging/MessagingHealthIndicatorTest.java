package com.myxcomp.ice.xtree.messaging;

import com.myxcomp.ice.xtree.common.TimeMapper;
import com.myxcomp.ice.xtree.config.SolaceProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MessagingHealthIndicatorTest {

    private static final Instant T0 = Instant.parse("2026-05-18T10:00:00Z");

    private ConnectionStateTracker tracker;
    private TimeMapper timeMapper;
    private SolaceProperties props;
    private MessagingHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        // Use a real tracker (driven through its callbacks) over mocked collaborators.
        timeMapper = mock(TimeMapper.class);
        when(timeMapper.now()).thenReturn(T0);
        RecoveryListenerHook hook = mock(RecoveryListenerHook.class);
        ReconnectReconciler reconciler = mock(ReconnectReconciler.class);
        tracker = new ConnectionStateTracker(hook, timeMapper, new SimpleMeterRegistry(), reconciler);
        tracker.registerWithHook();

        props = new SolaceProperties(
                "BC/ICE/ITEMTREE",
                new SolaceProperties.Reconnect(Duration.ofMinutes(1), Duration.ofHours(1)),
                new SolaceProperties.Health(Duration.ofHours(4)));
        indicator = new MessagingHealthIndicator(tracker, timeMapper, props);
    }

    @Test
    void neverConnectedReportsUp() {
        Health h = indicator.health();

        assertThat(h.getStatus()).isEqualTo(Status.UP);
        assertThat(h.getDetails()).containsEntry("connected", false);
        assertThat(h.getDetails()).containsEntry("outageSeconds", 0L);
    }

    @Test
    void currentlyConnectedReportsUp() {
        tracker.onConnectionRecovered("itemtree");

        Health h = indicator.health();

        assertThat(h.getStatus()).isEqualTo(Status.UP);
        assertThat(h.getDetails()).containsEntry("connected", true);
        assertThat(h.getDetails()).containsEntry("outageSeconds", 0L);
    }

    @Test
    void outageBelowMarkDownAfterReportsUp() {
        tracker.onConnectionLost("itemtree");
        when(timeMapper.now()).thenReturn(T0.plus(Duration.ofHours(3).plusMinutes(59)));

        Health h = indicator.health();

        assertThat(h.getStatus()).isEqualTo(Status.UP);
        assertThat(h.getDetails()).containsEntry("connected", false);
    }

    @Test
    void outageAtOrAboveMarkDownAfterReportsDown() {
        tracker.onConnectionLost("itemtree");
        when(timeMapper.now()).thenReturn(T0.plus(Duration.ofHours(4)));

        Health h = indicator.health();

        assertThat(h.getStatus()).isEqualTo(Status.DOWN);
        assertThat(h.getDetails()).containsEntry("connected", false);
        assertThat(h.getDetails()).containsEntry("outageSeconds", Duration.ofHours(4).toSeconds());
    }

    @Test
    void detailsMapContainsAllThreeKeys() {
        Health h = indicator.health();

        assertThat(h.getDetails()).containsKeys("connected", "outageSeconds", "lastEventAgeSeconds");
    }
}

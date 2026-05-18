package com.myxcomp.ice.xtree.messaging;

import com.myxcomp.ice.xtree.common.TimeMapper;
import com.myxcomp.ice.xtree.config.SolaceProperties;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Spring Boot {@link HealthIndicator} for the messaging subsystem (design §6 "Failure handling").
 * Exposed at {@code /actuator/health/messaging}.
 *
 * <p>Reports UP when connected or outage duration is below
 * {@code itemtree.solace.health.mark-down-after}. Reports DOWN when outage ≥ that threshold.
 */
@Component
public class MessagingHealthIndicator implements HealthIndicator {

    private final ConnectionStateTracker tracker;
    private final TimeMapper timeMapper;
    private final SolaceProperties props;

    public MessagingHealthIndicator(ConnectionStateTracker tracker,
                                    TimeMapper timeMapper,
                                    SolaceProperties props) {
        this.tracker = Objects.requireNonNull(tracker, "tracker");
        this.timeMapper = Objects.requireNonNull(timeMapper, "timeMapper");
        this.props = Objects.requireNonNull(props, "props");
    }

    @Override
    public Health health() {
        Instant disconnectedAt = tracker.disconnectedAt();
        long outageSeconds = disconnectedAt == null
                ? 0L
                : Duration.between(disconnectedAt, timeMapper.now()).toSeconds();
        Instant lastEvent = tracker.lastEventReceivedAt();
        long lastEventAgeSeconds = lastEvent == null
                ? 0L
                : Duration.between(lastEvent, timeMapper.now()).toSeconds();

        Health.Builder builder = outageSeconds >= props.health().markDownAfter().toSeconds()
                ? Health.down()
                : Health.up();

        return builder
                .withDetail("connected", tracker.isConnected())
                .withDetail("outageSeconds", outageSeconds)
                .withDetail("lastEventAgeSeconds", lastEventAgeSeconds)
                .build();
    }
}

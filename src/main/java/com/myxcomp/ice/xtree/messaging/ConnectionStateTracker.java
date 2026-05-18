package com.myxcomp.ice.xtree.messaging;

import com.myxcomp.ice.xtree.common.TimeMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Tracks broker connection state (design §6 "Reconnect reconciliation", §18 "Messaging metrics").
 *
 * <p>Implements {@link ConnectionRecoveryListener} and self-registers with the injected
 * {@link RecoveryListenerHook} in {@link #registerWithHook()}. Owns four §18 lifecycle metrics.
 *
 * <p>State fields are {@code volatile}; no locks needed — single-writer invariant is enforced
 * by the library thread (Phase B) and single-threaded test driving (Phase A).
 */
@Component
public class ConnectionStateTracker implements ConnectionRecoveryListener {

    private static final Logger log = LoggerFactory.getLogger(ConnectionStateTracker.class);

    private final RecoveryListenerHook hook;
    private final TimeMapper timeMapper;
    private final MeterRegistry meterRegistry;

    private volatile Instant disconnectedAt;
    private volatile Instant lastConnectedAt;
    private volatile Instant lastEventReceivedAt;
    private volatile boolean connected;

    private Counter connectionLostCounter;
    private Counter connectionRecoveredCounter;

    public ConnectionStateTracker(RecoveryListenerHook hook,
                                  TimeMapper timeMapper,
                                  MeterRegistry meterRegistry) {
        this.hook = Objects.requireNonNull(hook, "hook");
        this.timeMapper = Objects.requireNonNull(timeMapper, "timeMapper");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
    }

    @PostConstruct
    public void registerWithHook() {
        Gauge.builder("itemtree.solace.connected", this, t -> t.connected ? 1.0 : 0.0)
                .register(meterRegistry);
        Gauge.builder("itemtree.solace.outage_seconds", this, ConnectionStateTracker::outageSeconds)
                .register(meterRegistry);
        Gauge.builder("itemtree.solace.last_event_age_seconds", this, ConnectionStateTracker::lastEventAgeSeconds)
                .register(meterRegistry);
        connectionLostCounter = meterRegistry.counter("itemtree.solace.connection_lost_total");
        connectionRecoveredCounter = meterRegistry.counter("itemtree.solace.connection_recovered_total");
        hook.addRecoveryListener(this);
        log.info("ConnectionStateTracker registered with RecoveryListenerHook");
    }

    @Override
    public void onConnectionLost(String serviceName) {
        if (disconnectedAt == null) {
            disconnectedAt = timeMapper.now();
        }
        connected = false;
        connectionLostCounter.increment();
        log.warn("Connection lost: service={} disconnectedAt={}", serviceName, disconnectedAt);
    }

    @Override
    public void onConnectionRecovered(String serviceName) {
        Instant now = timeMapper.now();
        lastConnectedAt = now;
        disconnectedAt = null;
        connected = true;
        connectionRecoveredCounter.increment();
        log.info("Connection recovered: service={} at={}", serviceName, now);
    }

    public void recordEventReceived() {
        lastEventReceivedAt = timeMapper.now();
    }

    public boolean isConnected()           { return connected; }
    public Instant disconnectedAt()        { return disconnectedAt; }
    public Instant lastConnectedAt()       { return lastConnectedAt; }
    public Instant lastEventReceivedAt()   { return lastEventReceivedAt; }

    public double outageSeconds() {
        Instant d = disconnectedAt;
        return d == null ? 0.0 : Duration.between(d, timeMapper.now()).toSeconds();
    }

    public double lastEventAgeSeconds() {
        Instant e = lastEventReceivedAt;
        return e == null ? 0.0 : Duration.between(e, timeMapper.now()).toSeconds();
    }
}

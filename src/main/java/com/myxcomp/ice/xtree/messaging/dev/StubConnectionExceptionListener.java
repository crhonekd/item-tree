package com.myxcomp.ice.xtree.messaging.dev;

import com.myxcomp.ice.xtree.messaging.ConnectionRecoveryListener;
import com.myxcomp.ice.xtree.messaging.RecoveryListenerHook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Phase A scaffolding for the company {@code ConnectionExceptionListener} hook (design §6).
 * Tests drive {@link #simulateDisconnect()} and {@link #simulateRecovery()} directly;
 * Phase 11's {@code ConnectionStateTracker} will register itself via
 * {@link #addRecoveryListener(ConnectionRecoveryListener)}.
 */
@Component
@Profile("dev")
public class StubConnectionExceptionListener implements RecoveryListenerHook {

    private static final Logger log = LoggerFactory.getLogger(StubConnectionExceptionListener.class);
    private static final String SERVICE_NAME = "itemtree";

    private final CopyOnWriteArrayList<ConnectionRecoveryListener> listeners = new CopyOnWriteArrayList<>();

    @Override
    public void addRecoveryListener(ConnectionRecoveryListener listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
    }

    /** Test helper: emits {@code onConnectionLost(serviceName)} to every registered listener. */
    public void simulateDisconnect() {
        for (ConnectionRecoveryListener l : listeners) {
            try {
                l.onConnectionLost(SERVICE_NAME);
            } catch (RuntimeException e) {
                log.warn("ConnectionRecoveryListener.onConnectionLost threw: {}", e.toString());
            }
        }
    }

    /** Test helper: emits {@code onConnectionRecovered(serviceName)} to every registered listener. */
    public void simulateRecovery() {
        for (ConnectionRecoveryListener l : listeners) {
            try {
                l.onConnectionRecovered(SERVICE_NAME);
            } catch (RuntimeException e) {
                log.warn("ConnectionRecoveryListener.onConnectionRecovered threw: {}", e.toString());
            }
        }
    }
}

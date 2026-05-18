package com.myxcomp.ice.xtree.messaging;

/**
 * Callback fired by the messaging library's connection-exception listener around outages.
 *
 * <p>Design §6. {@code onConnectionLost} fires at the start of the library's
 * {@code onException(JMSException)}; {@code onConnectionRecovered} fires after the
 * library's recovery loop breaks successfully. Implementations must not throw —
 * exceptions inside callbacks are swallowed by the caller.
 *
 * <p>Implemented by {@code ConnectionStateTracker} in Phase 11.
 * In Phase A the {@code StubConnectionExceptionListener} provides the callback
 * dispatch site so tests can drive disconnect/recovery transitions deterministically.
 */
public interface ConnectionRecoveryListener {

    void onConnectionLost(String serviceName);

    void onConnectionRecovered(String serviceName);
}

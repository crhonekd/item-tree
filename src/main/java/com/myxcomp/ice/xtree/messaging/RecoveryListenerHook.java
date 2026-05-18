package com.myxcomp.ice.xtree.messaging;

/**
 * Registration site for {@link ConnectionRecoveryListener}s.
 *
 * <p>Phase A: implemented by {@code StubConnectionExceptionListener}.
 * Phase B: implemented by a thin adapter bean (in {@code @Profile("prod")} config)
 * that delegates to the company {@code com.barcap.ice.service.jms.ConnectionExceptionListener}.
 *
 * <p>Decouples {@code ConnectionStateTracker} from the concrete listener type so the
 * tracker remains profile-agnostic.
 */
public interface RecoveryListenerHook {

    void addRecoveryListener(ConnectionRecoveryListener listener);
}

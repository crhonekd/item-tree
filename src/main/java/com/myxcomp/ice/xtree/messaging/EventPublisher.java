package com.myxcomp.ice.xtree.messaging;

import com.myxcomp.ice.xtree.messaging.event.TreeMutationEvent;

/**
 * Outbound side of the Solace broadcast contract (design §6).
 *
 * <p>Phase 7 creates the interface so {@code ItemService} has something to call;
 * Phase 10 supplies the production implementation backed by {@code JMSPublisherService}.
 *
 * <p>Implementations are best-effort: failure is logged and counted but never propagates
 * to the caller. The DB commit and the local cache update have already happened — peer
 * instances reconcile via the next refresh if the broadcast was lost.
 */
public interface EventPublisher {

    /** Best-effort broadcast of {@code event}. Implementations MUST NOT throw. */
    void publish(TreeMutationEvent event);
}

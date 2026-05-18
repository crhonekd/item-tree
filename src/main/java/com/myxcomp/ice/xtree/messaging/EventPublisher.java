package com.myxcomp.ice.xtree.messaging;

import com.myxcomp.ice.xtree.messaging.event.TreeMutationEvent;

/**
 * Outbound side of the broadcast contract (design §6).
 *
 * <p>Phase A: {@code LocalLoopbackEventPublisher} (dev profile) serialises the event to JSON
 * and publishes onto {@code InMemoryEventBus}. Phase B: a {@code prod}-profile bean backed by
 * {@code JMSPublisherService.reliablePublish(String)}.
 *
 * <p>Implementations are best-effort: failure is logged and counted but never propagates
 * to the caller. The DB commit and the local cache update have already happened — peer
 * instances reconcile via the next refresh if the broadcast was lost.
 */
public interface EventPublisher {

    /** Best-effort broadcast of {@code event}. Implementations MUST NOT throw. */
    void publish(TreeMutationEvent event);
}

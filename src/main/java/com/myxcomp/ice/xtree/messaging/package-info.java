/**
 * Publish / consume contracts and dispatch logic for tree-mutation events (design §6).
 *
 * <p>Production-shape (no profile): {@link com.myxcomp.ice.xtree.messaging.EventPublisher},
 * {@link com.myxcomp.ice.xtree.messaging.EventConsumerService},
 * {@link com.myxcomp.ice.xtree.messaging.EventDispatcher},
 * {@link com.myxcomp.ice.xtree.messaging.SequenceGenerator},
 * {@link com.myxcomp.ice.xtree.messaging.ConnectionRecoveryListener},
 * {@link com.myxcomp.ice.xtree.messaging.ConnectionStateTracker},
 * {@link com.myxcomp.ice.xtree.messaging.ReconnectReconciler},
 * {@link com.myxcomp.ice.xtree.messaging.MessagingHealthIndicator},
 * {@link com.myxcomp.ice.xtree.messaging.RecoveryListenerHook}.
 *
 * <p>Phase A stubs live in {@code messaging/dev/} under {@code @Profile("dev")}; Phase B
 * substitutes JMS-backed implementations in a {@code prod}-profile config.
 */
package com.myxcomp.ice.xtree.messaging;

/**
 * Phase A in-memory stubs for the Solace messaging layer.
 * Active under the {@code dev} Spring profile only.
 * Contains {@link com.myxcomp.ice.xtree.messaging.dev.InMemoryEventBus},
 * {@link com.myxcomp.ice.xtree.messaging.dev.LocalLoopbackEventPublisher},
 * {@link com.myxcomp.ice.xtree.messaging.dev.LocalLoopbackEventConsumerStarter}, and
 * {@link com.myxcomp.ice.xtree.messaging.dev.StubConnectionExceptionListener}.
 * Replaced in Phase B by a {@code messaging/prod/} implementation
 * wrapping the real Solace JMS connection.
 */
package com.myxcomp.ice.xtree.messaging.dev;

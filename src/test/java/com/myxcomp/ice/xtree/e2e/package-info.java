/**
 * Two-instance end-to-end tests (design §6, §7, IMPLEMENTATION_NOTES Phase 13).
 *
 * <p>Boots two Spring {@link org.springframework.context.ConfigurableApplicationContext}s in
 * one JVM, sharing the in-memory H2 database (via the {@code jdbc:h2:mem:itemtree;...} URL)
 * and the in-memory {@link com.myxcomp.ice.xtree.messaging.dev.InMemoryEventBus} (via
 * {@link com.myxcomp.ice.xtree.e2e.SharedBusHolder}). Proves cache convergence on peer
 * mutations, self-echo suppression on the originator, and reconcile-on-reconnect for both
 * the delta-refresh and full-reload outage thresholds.
 */
package com.myxcomp.ice.xtree.e2e;

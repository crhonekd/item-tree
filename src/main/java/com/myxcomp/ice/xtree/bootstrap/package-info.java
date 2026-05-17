/**
 * Startup wiring. Holds {@link com.myxcomp.ice.xtree.bootstrap.TreeCacheBootstrap}
 * ({@code @Order(1)} ApplicationRunner — loads cache, flips readiness) and the {@link
 * com.myxcomp.ice.xtree.bootstrap.Sleeper} test seam used by its retry/backoff loop.
 * The Phase 10 {@code MessagingStarter} ({@code @Order(2)}) will live alongside these.
 */
package com.myxcomp.ice.xtree.bootstrap;

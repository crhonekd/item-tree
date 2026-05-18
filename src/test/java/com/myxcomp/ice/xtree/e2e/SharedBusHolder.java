package com.myxcomp.ice.xtree.e2e;

import com.myxcomp.ice.xtree.messaging.dev.InMemoryEventBus;

/**
 * Hands the same {@link InMemoryEventBus} instance to every caller within a JUnit test, so
 * two Spring contexts booted in the same JVM can publish/subscribe on one bus. The static
 * field is intentionally mutable: {@link #reset()} swaps in a fresh bus per {@code @BeforeEach}
 * so accumulated subscriptions from a prior test do not leak.
 *
 * <p>Not thread-safe across tests; JUnit Jupiter runs tests sequentially by default.
 */
public final class SharedBusHolder {

    private static InMemoryEventBus bus = new InMemoryEventBus();

    private SharedBusHolder() {}

    public static InMemoryEventBus get() {
        return bus;
    }

    public static void reset() {
        bus = new InMemoryEventBus();
    }
}

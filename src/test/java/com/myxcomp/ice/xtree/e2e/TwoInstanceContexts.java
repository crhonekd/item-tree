package com.myxcomp.ice.xtree.e2e;

import com.myxcomp.ice.xtree.ItemTreeApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Boots two independent {@link ConfigurableApplicationContext}s sharing the in-memory bus
 * (via {@link SharedBusHolder}) and the in-memory H2 database (via the same JDBC URL in the
 * same JVM). Each context owns its own {@link com.myxcomp.ice.xtree.common.InstanceIdProvider}
 * (auto-random UUID), its own {@link com.myxcomp.ice.xtree.cache.TreeCache}, its own
 * {@link com.myxcomp.ice.xtree.messaging.EventConsumerService}, and its own
 * {@link com.myxcomp.ice.xtree.messaging.ConnectionStateTracker}.
 *
 * <p>Callers must invoke {@link SharedBusHolder#reset()} <b>before</b> {@link #boot()} for
 * each test method so the bus has no stale subscriptions from a prior test.
 */
public final class TwoInstanceContexts implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TwoInstanceContexts.class);

    private final ConfigurableApplicationContext a;
    private final ConfigurableApplicationContext b;

    private TwoInstanceContexts(ConfigurableApplicationContext a, ConfigurableApplicationContext b) {
        this.a = a;
        this.b = b;
    }

    public static TwoInstanceContexts boot() {
        ConfigurableApplicationContext a = launch("itemtree-A");
        ConfigurableApplicationContext b = launch("itemtree-B");
        return new TwoInstanceContexts(a, b);
    }

    private static ConfigurableApplicationContext launch(String label) {
        return new SpringApplicationBuilder(ItemTreeApplication.class, E2ETestConfig.class)
                .profiles("dev")
                .properties(
                        "spring.main.allow-bean-definition-overriding=true",
                        "spring.application.name=" + label
                )
                .web(WebApplicationType.NONE)
                .run();
    }

    public ConfigurableApplicationContext a() { return a; }
    public ConfigurableApplicationContext b() { return b; }

    @Override
    public void close() {
        try { a.close(); } catch (RuntimeException e) { log.debug("context A shutdown failed: {}", e.toString()); }
        try { b.close(); } catch (RuntimeException e) { log.debug("context B shutdown failed: {}", e.toString()); }
    }
}

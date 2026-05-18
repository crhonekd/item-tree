package com.myxcomp.ice.xtree.messaging.dev;

import com.myxcomp.ice.xtree.config.SolaceProperties;
import com.myxcomp.ice.xtree.messaging.EventConsumerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Phase A counterpart to the Phase B {@code MessagingStarter}. Subscribes
 * {@link EventConsumerService#processPayload(String)} to the in-memory bus on the
 * configured topic. {@code @Order(2)} so it runs after {@code TreeCacheBootstrap @Order(1)}.
 */
@Component
@Order(2)
@Profile("dev")
public class LocalLoopbackEventConsumerStarter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalLoopbackEventConsumerStarter.class);

    private final InMemoryEventBus bus;
    private final EventConsumerService consumer;
    private final SolaceProperties solaceProperties;

    public LocalLoopbackEventConsumerStarter(InMemoryEventBus bus,
                                             EventConsumerService consumer,
                                             SolaceProperties solaceProperties) {
        this.bus = bus;
        this.consumer = consumer;
        this.solaceProperties = solaceProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        String topic = solaceProperties.topic();
        bus.subscribe(topic, consumer::processPayload);
        log.info("LocalLoopbackEventConsumerStarter subscribed to '{}'", topic);
    }
}

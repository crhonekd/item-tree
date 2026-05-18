package com.myxcomp.ice.xtree.messaging.dev;

import com.myxcomp.ice.xtree.config.SolaceProperties;
import com.myxcomp.ice.xtree.messaging.EventConsumerService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class LocalLoopbackEventConsumerStarterTest {

    private static final String TOPIC = "BC/ICE/ITEMTREE";

    @Test
    @SuppressWarnings("unchecked")
    void run_subscribes_consumer_processPayload_to_topic() throws Exception {
        InMemoryEventBus bus = mock(InMemoryEventBus.class);
        EventConsumerService consumer = mock(EventConsumerService.class);
        LocalLoopbackEventConsumerStarter starter = new LocalLoopbackEventConsumerStarter(
                bus, consumer, new SolaceProperties(TOPIC,
                        new SolaceProperties.Reconnect(Duration.ofMinutes(1), Duration.ofHours(1)),
                        new SolaceProperties.Health(Duration.ofHours(4))));

        starter.run(null);

        ArgumentCaptor<Consumer<String>> handlerCap = ArgumentCaptor.forClass(Consumer.class);
        verify(bus).subscribe(eq(TOPIC), handlerCap.capture());

        // The handler must delegate to processPayload — drive it and verify.
        handlerCap.getValue().accept("PAYLOAD");
        verify(consumer).processPayload("PAYLOAD");
    }
}

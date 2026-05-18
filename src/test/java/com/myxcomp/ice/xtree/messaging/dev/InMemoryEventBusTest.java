package com.myxcomp.ice.xtree.messaging.dev;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryEventBusTest {

    private final InMemoryEventBus bus = new InMemoryEventBus();

    @Test
    void publish_with_no_subscribers_is_a_no_op() {
        assertThatCode(() -> bus.publish("topic", "payload")).doesNotThrowAnyException();
    }

    @Test
    void subscriber_receives_published_payload() {
        List<String> received = new ArrayList<>();
        bus.subscribe("t", received::add);
        bus.publish("t", "hello");
        assertThat(received).containsExactly("hello");
    }

    @Test
    void multiple_subscribers_on_same_topic_all_receive() {
        AtomicInteger a = new AtomicInteger();
        AtomicInteger b = new AtomicInteger();
        bus.subscribe("t", x -> a.incrementAndGet());
        bus.subscribe("t", x -> b.incrementAndGet());
        bus.publish("t", "x");
        assertThat(a.get()).isOne();
        assertThat(b.get()).isOne();
    }

    @Test
    void subscriber_on_other_topic_does_not_receive() {
        AtomicInteger a = new AtomicInteger();
        bus.subscribe("other", x -> a.incrementAndGet());
        bus.publish("t", "x");
        assertThat(a.get()).isZero();
    }

    @Test
    void throwing_subscriber_does_not_prevent_other_subscribers_or_propagate() {
        AtomicInteger reached = new AtomicInteger();
        bus.subscribe("t", x -> { throw new RuntimeException("boom"); });
        bus.subscribe("t", x -> reached.incrementAndGet());
        assertThatCode(() -> bus.publish("t", "x")).doesNotThrowAnyException();
        assertThat(reached.get()).isOne();
    }

    @Test
    void null_topic_or_payload_throws_NPE() {
        assertThatThrownBy(() -> bus.publish(null, "x")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> bus.publish("t", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> bus.subscribe(null, x -> {})).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> bus.subscribe("t", null)).isInstanceOf(NullPointerException.class);
    }
}

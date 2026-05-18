package com.myxcomp.ice.xtree.e2e;

import com.myxcomp.ice.xtree.messaging.dev.InMemoryEventBus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SharedBusHolderTest {

    @Test
    void getReturnsSameInstanceUntilReset() {
        InMemoryEventBus a = SharedBusHolder.get();
        InMemoryEventBus b = SharedBusHolder.get();
        assertThat(a).isSameAs(b);
    }

    @Test
    void resetSwapsInANewBus() {
        InMemoryEventBus before = SharedBusHolder.get();
        SharedBusHolder.reset();
        InMemoryEventBus after = SharedBusHolder.get();
        assertThat(after).isNotSameAs(before);
    }
}

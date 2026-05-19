package com.myxcomp.ice.xtree.e2e;

import com.myxcomp.ice.xtree.cache.TreeCache;
import com.myxcomp.ice.xtree.common.InstanceIdProvider;
import com.myxcomp.ice.xtree.common.TreeConstants;
import com.myxcomp.ice.xtree.messaging.dev.InMemoryEventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TwoInstanceContextsTest {

    private TwoInstanceContexts pair;

    @BeforeEach
    void resetBusAndBoot() {
        SharedBusHolder.reset();
        pair = TwoInstanceContexts.boot();
    }

    @AfterEach
    void close() {
        if (pair != null) pair.close();
    }

    @Test
    void bothContextsHaveDistinctInstanceIds() {
        String idA = pair.a().getBean(InstanceIdProvider.class).getInstanceId();
        String idB = pair.b().getBean(InstanceIdProvider.class).getInstanceId();
        assertThat(idA).isNotEqualTo(idB);
    }

    @Test
    void bothContextsShareTheSameBus() {
        InMemoryEventBus busA = pair.a().getBean(InMemoryEventBus.class);
        InMemoryEventBus busB = pair.b().getBean(InMemoryEventBus.class);
        assertThat(busA).isSameAs(busB).isSameAs(SharedBusHolder.get());
    }

    @Test
    void bothCachesLoadedRootOnBootstrap() {
        TreeCache cacheA = pair.a().getBean(TreeCache.class);
        TreeCache cacheB = pair.b().getBean(TreeCache.class);
        assertThat(cacheA.getById(TreeConstants.ROOT_ID)).isPresent();
        assertThat(cacheB.getById(TreeConstants.ROOT_ID)).isPresent();
    }
}

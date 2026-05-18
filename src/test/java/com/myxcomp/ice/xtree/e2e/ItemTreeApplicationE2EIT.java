package com.myxcomp.ice.xtree.e2e;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.TreeCache;
import com.myxcomp.ice.xtree.common.UserContext;
import com.myxcomp.ice.xtree.service.ItemService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ItemTreeApplicationE2EIT {

    private TwoInstanceContexts pair;

    @BeforeEach
    void bootBoth() {
        SharedBusHolder.reset();
        pair = TwoInstanceContexts.boot();
    }

    @AfterEach
    void shutdown() {
        if (pair != null) pair.close();
    }

    @Test
    void createOnAReachesBsCache() {
        ItemService itemServiceA = pair.a().getBean(ItemService.class);
        TreeCache cacheA = pair.a().getBean(TreeCache.class);
        TreeCache cacheB = pair.b().getBean(TreeCache.class);

        // 'Users' folder (id=2 in data.sql) is a valid parent.
        CachedNode created = itemServiceA.createItem(
                2L, "E2E_PeerCreate", "Folder", null,
                new UserContext("alice", null));

        Optional<CachedNode> onA = cacheA.getById(created.itemTreeId());
        Optional<CachedNode> onB = cacheB.getById(created.itemTreeId());

        assertThat(onA).as("originator cache").isPresent();
        assertThat(onA.get().name()).isEqualTo("E2E_PeerCreate");
        assertThat(onB).as("peer cache").isPresent();
        assertThat(onB.get().name()).isEqualTo("E2E_PeerCreate");
        assertThat(onB.get().parentId()).isEqualTo(2L);
    }

    @Test
    void selfEchoSuppressedOnOriginator() {
        ItemService itemServiceA = pair.a().getBean(ItemService.class);
        io.micrometer.core.instrument.MeterRegistry registryA =
                pair.a().getBean(io.micrometer.core.instrument.MeterRegistry.class);
        io.micrometer.core.instrument.MeterRegistry registryB =
                pair.b().getBean(io.micrometer.core.instrument.MeterRegistry.class);

        double aDroppedBefore  = registryA.counter("itemtree.event.self_dropped").count();
        double bConsumedBefore = registryB.counter("itemtree.event.consumed", "op", "CREATE").count();

        itemServiceA.createItem(2L, "E2E_SelfEcho", "Folder", null,
                new UserContext("alice", null));

        assertThat(registryA.counter("itemtree.event.self_dropped").count())
                .isEqualTo(aDroppedBefore + 1.0);
        assertThat(registryB.counter("itemtree.event.consumed", "op", "CREATE").count())
                .isEqualTo(bConsumedBefore + 1.0);
        assertThat(registryB.counter("itemtree.event.self_dropped").count())
                .isZero();
    }
}

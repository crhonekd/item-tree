package com.myxcomp.ice.xtree.e2e;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.TreeCache;
import com.myxcomp.ice.xtree.common.UserContext;
import com.myxcomp.ice.xtree.service.ItemService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    @ParameterizedTest(name = "{0} propagates A -> B")
    @ValueSource(strings = {"UPDATE", "MOVE", "RENAME", "DELETE"})
    void mutationPropagatesAcrossInstances(String operation) {
        ItemService itemServiceA = pair.a().getBean(ItemService.class);
        TreeCache cacheA = pair.a().getBean(TreeCache.class);
        TreeCache cacheB = pair.b().getBean(TreeCache.class);
        UserContext alice = new UserContext("alice", null);

        // Seed a target Folder node under Users (id=2) via ItemService so both caches see it.
        CachedNode target = itemServiceA.createItem(
                2L, "E2E_" + operation + "_target", "Folder", null, alice);
        long id = target.itemTreeId();
        assertThat(cacheB.getById(id)).as("seed visible on B").isPresent();

        switch (operation) {
            case "UPDATE" -> {
                // updateItemData requires a data-bearing type — delete the Folder and create a Report.
                itemServiceA.deleteItem(id, alice);
                CachedNode report = itemServiceA.createItem(
                        3L, "E2E_UPDATE_report", "Report",
                        "{\"name\":\"before\",\"n\":1}", alice);
                long reportId = report.itemTreeId();
                assertThat(cacheB.getById(reportId)).isPresent();
                itemServiceA.updateItemData(reportId, "{\"name\":\"after\",\"n\":2}", alice);
                // UPDATE payload does not carry JSON data — verify event arrived on B via counter.
                io.micrometer.core.instrument.MeterRegistry registryB =
                        pair.b().getBean(io.micrometer.core.instrument.MeterRegistry.class);
                assertThat(registryB.counter("itemtree.event.consumed", "op", "UPDATE").count())
                        .as("UPDATE event consumed by B")
                        .isGreaterThanOrEqualTo(1.0);
            }
            case "MOVE" -> {
                itemServiceA.moveItem(id, 3L, alice);   // move under Reports (id=3)
                assertThat(cacheA.getById(id).orElseThrow().parentId()).isEqualTo(3L);
                assertThat(cacheB.getById(id).orElseThrow().parentId())
                        .as("B sees the new parent").isEqualTo(3L);
            }
            case "RENAME" -> {
                itemServiceA.renameItem(id, "E2E_RENAME_after", alice);
                assertThat(cacheB.getById(id).orElseThrow().name())
                        .as("B sees the new name").isEqualTo("E2E_RENAME_after");
            }
            case "DELETE" -> {
                itemServiceA.deleteItem(id, alice);
                assertThat(cacheA.getById(id)).as("A removed").isEmpty();
                assertThat(cacheB.getById(id)).as("B removed").isEmpty();
            }
            default -> throw new IllegalArgumentException("unknown op " + operation);
        }
    }
}

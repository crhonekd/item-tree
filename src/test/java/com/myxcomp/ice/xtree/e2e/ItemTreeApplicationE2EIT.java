package com.myxcomp.ice.xtree.e2e;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.TreeCache;
import com.myxcomp.ice.xtree.common.TimeMapper;
import com.myxcomp.ice.xtree.common.UserContext;
import com.myxcomp.ice.xtree.messaging.dev.StubConnectionExceptionListener;
import com.myxcomp.ice.xtree.service.ItemService;
import io.micrometer.core.instrument.MeterRegistry;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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
        if (pair != null) {
            // Delete E2E-created rows (allocated IDs >= 100000) before closing the context,
            // so the shared H2 in-memory DB is clean for subsequent test classes.
            pair.a().getBean(JdbcClient.class)
                    .sql("DELETE FROM ITEMTREE WHERE ITEMTREEID >= 100000")
                    .update();
            pair.close();
        }
    }

    @Test
    void createOnAReachesBsCache() {
        ItemService itemServiceA = pair.a().getBean(ItemService.class);
        TreeCache cacheA = pair.a().getBean(TreeCache.class);
        TreeCache cacheB = pair.b().getBean(TreeCache.class);

        // testuser1 home folder (id=10 in data.sql) is a valid parent for user 'testuser1'.
        CachedNode created = itemServiceA.createItem(
                10L, "E2E_PeerCreate", "Folder", null,
                new UserContext("testuser1", null));

        Optional<CachedNode> onA = cacheA.getById(created.itemTreeId());
        Optional<CachedNode> onB = cacheB.getById(created.itemTreeId());

        assertThat(onA).as("originator cache").isPresent();
        assertThat(onA.get().name()).isEqualTo("E2E_PeerCreate");
        assertThat(onB).as("peer cache").isPresent();
        assertThat(onB.get().name()).isEqualTo("E2E_PeerCreate");
        assertThat(onB.get().parentId()).isEqualTo(10L);
    }

    @Test
    void selfEchoSuppressedOnOriginator() {
        ItemService itemServiceA = pair.a().getBean(ItemService.class);
        MeterRegistry registryA =
                pair.a().getBean(MeterRegistry.class);
        MeterRegistry registryB =
                pair.b().getBean(MeterRegistry.class);

        double aDroppedBefore  = registryA.counter("itemtree.event.self_dropped").count();
        double bConsumedBefore = registryB.counter("itemtree.event.consumed", "op", "CREATE").count();

        itemServiceA.createItem(10L, "E2E_SelfEcho", "Folder", null,
                new UserContext("testuser1", null));

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
        UserContext alice = new UserContext("testuser1", null);

        // Seed a target Folder node under testuser1 home folder (id=10) via ItemService so both caches see it.
        CachedNode target = itemServiceA.createItem(
                10L, "E2E_" + operation + "_target", "Folder", null, alice);
        long id = target.itemTreeId();
        assertThat(cacheB.getById(id)).as("seed visible on B").isPresent();

        switch (operation) {
            case "UPDATE" -> {
                // updateItemData requires a data-bearing type — delete the Folder and create a Report.
                itemServiceA.deleteItem(id, alice);
                CachedNode report = itemServiceA.createItem(
                        10L, "E2E_UPDATE_report", "Report",
                        "{\"name\":\"before\",\"n\":1}", alice);
                long reportId = report.itemTreeId();
                assertThat(cacheB.getById(reportId)).isPresent();
                itemServiceA.updateItemData(reportId, "{\"name\":\"after\",\"n\":2}", alice);
                // UPDATE payload carries metadata (lastUpdate, lastUpdateUser) but not JSON data.
                // Verify the event arrived via counter and was applied via the metadata field on B.
                MeterRegistry registryB =
                        pair.b().getBean(MeterRegistry.class);
                assertThat(registryB.counter("itemtree.event.consumed", "op", "UPDATE").count())
                        .as("UPDATE event consumed by B")
                        .isGreaterThanOrEqualTo(1.0);
                assertThat(cacheB.getById(reportId).orElseThrow().lastUpdateUser())
                        .as("B sees the updated lastUpdateUser")
                        .isEqualTo("testuser1");
            }
            case "MOVE" -> {
                // Create a second folder under testuser1's home so we have an owned destination.
                CachedNode moveDestination = itemServiceA.createItem(
                        10L, "E2E_MOVE_destination", "Folder", null, alice);
                long destId = moveDestination.itemTreeId();
                assertThat(cacheB.getById(destId)).as("destination visible on B").isPresent();

                itemServiceA.moveItem(id, destId, alice);
                assertThat(cacheA.getById(id).orElseThrow().parentId()).isEqualTo(destId);
                assertThat(cacheB.getById(id).orElseThrow().parentId())
                        .as("B sees the new parent").isEqualTo(destId);
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

    private long insertRowDirectlyIntoH2(long parentId, String name, String type) {
        JdbcClient jdbc =
                pair.b().getBean(JdbcClient.class);
        Long newId = jdbc.sql("SELECT ITEMTREE_ID_SQN.NEXTVAL FROM DUAL")
                .query(Long.class).single();
        jdbc.sql("""
                INSERT INTO ITEMTREE
                (ITEMTREEID, PARENTID, NAME, TYPE, XML, LASTUPDATEUSER, LASTUPDATE, JSON)
                VALUES (:id, :pid, :name, :type, NULL, 'direct', :lastUpdate, NULL)
                """)
                .param("id", newId)
                .param("pid", parentId)
                .param("name", name)
                .param("type", type)
                .param("lastUpdate", E2ETestConfig.DEFAULT_TEST_INSTANT  // fixed 1 day after test instant
                        .plus(Duration.ofDays(1))
                        .atOffset(ZoneOffset.UTC)
                        .toLocalDateTime())
                .update();
        return newId;
    }

    @Test
    void shortOutageReconcilesViaDeltaRefresh() {
        TreeCache cacheB =
                pair.b().getBean(TreeCache.class);
        StubConnectionExceptionListener stubB =
                pair.b().getBean(StubConnectionExceptionListener.class);
        TimeMapper clockB =
                pair.b().getBean(TimeMapper.class);
        MeterRegistry registryB =
                pair.b().getBean(MeterRegistry.class);

        // 1) First connect — establishes tracker baseline (no reconcile).
        stubB.simulateRecovery();
        assertThat(registryB.find("itemtree.solace.reconnect_reconcile").counters()).isEmpty();

        // 2) Disconnect at T0.
        Instant t0 = E2ETestConfig.DEFAULT_TEST_INSTANT;
        Mockito.when(clockB.now()).thenReturn(t0);
        stubB.simulateDisconnect();

        // 3) Write directly to H2 — no event published, so B's cache is blind.
        long missedId = insertRowDirectlyIntoH2(2L, "E2E_DeltaMissed", "Folder");
        assertThat(cacheB.getById(missedId)).as("B blind before reconcile").isEmpty();

        // 4) Reconnect 10 min later (> PT1M, < PT1H) → triggers delta refresh.
        Mockito.when(clockB.now()).thenReturn(t0.plus(Duration.ofMinutes(10)));
        stubB.simulateRecovery();

        // 5) ReconnectReconciler submits runDelta() to the TaskScheduler (async) — await effect.
        Awaitility.await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(cacheB.getById(missedId)).isPresent());

        assertThat(registryB.counter("itemtree.solace.reconnect_reconcile", "type", "delta").count())
                .as("delta reconcile counter ticked exactly once")
                .isEqualTo(1.0);
        assertThat(registryB.counter("itemtree.solace.reconnect_reconcile", "type", "full").count())
                .as("full-reload counter NOT touched for short outage")
                .isZero();
    }

    @Test
    void longOutageReconcilesViaFullReload() {
        TreeCache cacheB =
                pair.b().getBean(TreeCache.class);
        StubConnectionExceptionListener stubB =
                pair.b().getBean(StubConnectionExceptionListener.class);
        TimeMapper clockB =
                pair.b().getBean(TimeMapper.class);
        MeterRegistry registryB =
                pair.b().getBean(MeterRegistry.class);

        stubB.simulateRecovery();   // first connect baseline

        Instant t0 = E2ETestConfig.DEFAULT_TEST_INSTANT;
        Mockito.when(clockB.now()).thenReturn(t0);
        stubB.simulateDisconnect();

        long missedId = insertRowDirectlyIntoH2(2L, "E2E_FullMissed", "Folder");
        assertThat(cacheB.getById(missedId)).as("B blind before reconcile").isEmpty();

        // Reconnect 2 h later — exceeds longThreshold PT1H → full reload.
        Mockito.when(clockB.now()).thenReturn(t0.plus(Duration.ofHours(2)));
        stubB.simulateRecovery();

        Awaitility.await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(cacheB.getById(missedId)).isPresent());

        assertThat(registryB.counter("itemtree.solace.reconnect_reconcile", "type", "full").count())
                .as("full reconcile counter ticked exactly once")
                .isEqualTo(1.0);
        assertThat(registryB.counter("itemtree.solace.reconnect_reconcile", "type", "delta").count())
                .as("delta counter NOT touched for long outage")
                .isZero();
    }

    @Test
    void cascadeDeletePropagatesSubtreeRemovalToB() {
        ItemService itemServiceA = pair.a().getBean(ItemService.class);
        TreeCache cacheA = pair.a().getBean(TreeCache.class);
        TreeCache cacheB = pair.b().getBean(TreeCache.class);
        UserContext alice = new UserContext("testuser1", null);

        // Build a three-level subtree under testuser1 home folder (id=10): parent → child → grandchild.
        CachedNode parent = itemServiceA.createItem(10L, "E2E_CascadeParent", "Folder", null, alice);
        CachedNode child = itemServiceA.createItem(parent.itemTreeId(), "E2E_CascadeChild", "Folder", null, alice);
        CachedNode grandchild = itemServiceA.createItem(child.itemTreeId(), "E2E_CascadeGrandchild", "Folder", null, alice);

        long parentId = parent.itemTreeId();
        long childId = child.itemTreeId();
        long grandchildId = grandchild.itemTreeId();

        // All three nodes must be visible on B before the delete.
        assertThat(cacheB.getById(parentId)).as("parent visible on B").isPresent();
        assertThat(cacheB.getById(childId)).as("child visible on B").isPresent();
        assertThat(cacheB.getById(grandchildId)).as("grandchild visible on B").isPresent();

        // All three nodes must also be visible on A before the delete.
        assertThat(cacheA.getById(parentId)).as("parent visible on A before delete").isPresent();
        assertThat(cacheA.getById(childId)).as("child visible on A before delete").isPresent();
        assertThat(cacheA.getById(grandchildId)).as("grandchild visible on A before delete").isPresent();

        // Delete the root of the subtree — cascades to child and grandchild.
        // InMemoryEventBus dispatches synchronously, so B's cache is updated before this returns.
        itemServiceA.deleteItem(parentId, alice);

        // All three IDs must be absent from A's cache.
        assertThat(cacheA.getById(parentId)).as("A: parent removed").isEmpty();
        assertThat(cacheA.getById(childId)).as("A: child removed").isEmpty();
        assertThat(cacheA.getById(grandchildId)).as("A: grandchild removed").isEmpty();

        // All three IDs must be absent from B's cache.
        assertThat(cacheB.getById(parentId)).as("B: parent removed").isEmpty();
        assertThat(cacheB.getById(childId)).as("B: child removed").isEmpty();
        assertThat(cacheB.getById(grandchildId)).as("B: grandchild removed").isEmpty();
    }

    @Test
    void bothCachesBootstrappedToIdenticalSize() {
        TreeCache cacheA = pair.a().getBean(TreeCache.class);
        TreeCache cacheB = pair.b().getBean(TreeCache.class);

        int sizeA = cacheA.size();
        int sizeB = cacheB.size();
        assertThat(sizeA).isPositive();
        assertThat(sizeB).isEqualTo(sizeA);

        // Verify specific seed nodes are present in both caches
        for (long seedId : new long[]{1L, 2L, 12L, 25L}) { // root, Users, deepuser, leafItem
            assertThat(cacheA.getById(seedId))
                    .as("cacheA missing seed id=" + seedId).isPresent();
            assertThat(cacheB.getById(seedId))
                    .as("cacheB missing seed id=" + seedId).isPresent();
        }
    }

    @Test
    void copyPropagatesAcrossInstances() throws Exception {
        ItemService itemServiceA = pair.a().getBean(ItemService.class);
        TreeCache cacheB = pair.b().getBean(TreeCache.class);

        long sourceId = 25L;    // leafItem seed (Report under deepuser depth-7 chain)
        long destId   = 12L;    // deepuser home folder
        UserContext ctx = new UserContext("deepuser", null);

        List<CachedNode> result = itemServiceA.copyItem(sourceId, destId, ctx);
        assertThat(result).hasSize(1);
        long newId = result.get(0).itemTreeId();

        Awaitility.await().atMost(java.time.Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(cacheB.getById(newId)).isPresent());
        assertThat(cacheB.getById(newId)).get()
                .extracting(CachedNode::parentId).isEqualTo(destId);
    }

    @Test
    void copySubtreePropagatesAcrossInstances() {
        ItemService itemServiceA = pair.a().getBean(ItemService.class);
        TreeCache cacheB = pair.b().getBean(TreeCache.class);

        long destId = 12L;    // deepuser home folder
        UserContext ctx = new UserContext("deepuser", null);

        // 1) Build a 2-level folder subtree under deepuser's home folder on A.
        CachedNode sourceParent = itemServiceA.createItem(
                destId, "E2E_CopySubtree_Parent", "Folder", null, ctx);
        CachedNode sourceChild = itemServiceA.createItem(
                sourceParent.itemTreeId(), "E2E_CopySubtree_Child", "Folder", null, ctx);

        long sourceParentId = sourceParent.itemTreeId();
        long sourceChildId  = sourceChild.itemTreeId();

        // 2) Wait for both source folders to be visible in B's cache.
        Awaitility.await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> {
                    assertThat(cacheB.getById(sourceParentId)).isPresent();
                    assertThat(cacheB.getById(sourceChildId)).isPresent();
                });

        // 3) Copy the parent folder subtree from A to destId.
        List<CachedNode> result = itemServiceA.copyItem(sourceParentId, destId, ctx);

        // 4) The copy should return 2 nodes: copied parent + copied child.
        assertThat(result).hasSize(2);

        // 5) Extract the new IDs from the copy result.
        long newParentId = result.get(0).itemTreeId();
        long newChildId  = result.get(1).itemTreeId();

        // 6) Wait until both new nodes are present in B's cache.
        Awaitility.await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> {
                    assertThat(cacheB.getById(newParentId)).isPresent();
                    assertThat(cacheB.getById(newChildId)).isPresent();
                });

        // 7) Assert correct parent relationships on B.
        assertThat(cacheB.getById(newParentId)).get()
                .extracting(CachedNode::parentId)
                .as("copied parent's parent should be destId")
                .isEqualTo(destId);
        assertThat(cacheB.getById(newChildId)).get()
                .extracting(CachedNode::parentId)
                .as("copied child's parent should be the new parent")
                .isEqualTo(newParentId);
    }
}

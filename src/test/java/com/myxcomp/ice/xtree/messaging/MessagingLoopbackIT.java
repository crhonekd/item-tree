package com.myxcomp.ice.xtree.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.TreeCache;
import com.myxcomp.ice.xtree.common.InstanceIdProvider;
import com.myxcomp.ice.xtree.common.UserContext;
import com.myxcomp.ice.xtree.config.SolaceProperties;
import com.myxcomp.ice.xtree.messaging.dev.InMemoryEventBus;
import com.myxcomp.ice.xtree.messaging.event.OperationType;
import com.myxcomp.ice.xtree.messaging.event.TreeMutationEvent;
import com.myxcomp.ice.xtree.messaging.event.payload.CreatePayload;
import com.myxcomp.ice.xtree.service.ItemService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dev")
@Transactional
class MessagingLoopbackIT {

    @Autowired private InMemoryEventBus bus;
    @Autowired private SolaceProperties solaceProperties;
    @Autowired private TreeCache cache;
    @Autowired private InstanceIdProvider instanceIdProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private ItemService itemService;

    /** IDs injected into the live cache during a test — removed in @AfterEach. */
    private final Set<Long> toCleanUp = new HashSet<>();

    @AfterEach
    void cleanUpCacheNodes() {
        cache.applyDelete(toCleanUp);
        toCleanUp.clear();
    }

    @Test
    void peer_event_is_applied_to_local_cache() throws Exception {
        long newId = 88_888L;
        toCleanUp.add(newId);
        TreeMutationEvent peerEvent = TreeMutationEvent.builder()
                .eventId("peer-evt-1")
                .instanceId("some-other-instance")
                .sequence(1L)
                .occurredAt(Instant.parse("2026-05-17T10:00:00Z"))
                .iceUser("peer-user")
                .operationType(OperationType.CREATE)
                .payload(new CreatePayload(newId, 1L, "FromPeer", "Folder",
                        Instant.parse("2026-05-17T10:00:00Z"), "peer-user"))
                .build();
        bus.publish(solaceProperties.topic(), objectMapper.writeValueAsString(peerEvent));

        assertThat(cache.getById(newId)).isPresent();
        assertThat(cache.getById(newId).get().name()).isEqualTo("FromPeer");
        assertThat(meterRegistry.counter("itemtree.event.consumed", "op", "CREATE").count())
                .isGreaterThanOrEqualTo(1.0);
    }

    @Test
    void self_echo_is_dropped() throws Exception {
        long newId = 99_999L;
        toCleanUp.add(newId); // no-op delete since self-echo is dropped and node is never added
        TreeMutationEvent selfEvent = TreeMutationEvent.builder()
                .eventId("self-evt-1")
                .instanceId(instanceIdProvider.getInstanceId())
                .sequence(1L)
                .occurredAt(Instant.parse("2026-05-17T10:00:00Z"))
                .iceUser("local-user")
                .operationType(OperationType.CREATE)
                .payload(new CreatePayload(newId, 1L, "Echo", "Folder",
                        Instant.parse("2026-05-17T10:00:00Z"), "local-user"))
                .build();
        double before = meterRegistry.counter("itemtree.event.self_dropped").count();
        bus.publish(solaceProperties.topic(), objectMapper.writeValueAsString(selfEvent));

        assertThat(cache.getById(newId)).isEmpty();
        assertThat(meterRegistry.counter("itemtree.event.self_dropped").count())
                .isEqualTo(before + 1.0);
    }

    @Test
    void itemService_create_publishes_and_self_drops() {
        double publishedBefore = meterRegistry.counter("itemtree.event.published", "op", "CREATE").count();
        double droppedBefore = meterRegistry.counter("itemtree.event.self_dropped").count();

        itemService.createItem(1L, "PhaseTenTestFolder", "Folder", null,
                new UserContext("alice", null));

        // Capture the auto-generated ID so @AfterEach can evict it from the cache.
        // The self-echo path drops the event, so the node should NOT be in the cache;
        // this lookup is a safety net in case that assumption ever changes.
        cache.searchByName("PhaseTenTestFolder", OptionalInt.empty())
                .forEach(n -> toCleanUp.add(n.itemTreeId()));

        assertThat(meterRegistry.counter("itemtree.event.published", "op", "CREATE").count())
                .isEqualTo(publishedBefore + 1.0);
        assertThat(meterRegistry.counter("itemtree.event.self_dropped").count())
                .isEqualTo(droppedBefore + 1.0);
    }

    /**
     * Verifies the COPY round-trip via the in-memory loopback bus.
     *
     * <p>This test uses a single Spring context (no peer cache), so the self-echo suppression
     * path is exercised: {@code copyItem} writes the new node into the local cache via
     * {@code applyCopy}, then publishes a COPY event; the consumer drops the event because the
     * instance-id matches. The assertions confirm:
     * <ol>
     *   <li>The service returns the copied node(s).</li>
     *   <li>The local cache contains the new node (placed there by {@code applyCopy}).</li>
     *   <li>A COPY event was published (metric).</li>
     *   <li>The self-echo was dropped (metric).</li>
     * </ol>
     *
     * <p>Seed data: id=25 is {@code leafItem} (Report, deep under deepuser); id=12 is the
     * {@code deepuser} home folder — a valid copy destination for user "deepuser".
     */
    @Test
    void itemService_copy_publishes_and_self_drops() {
        // deepuser home folder (id=12) is a valid destination for user "deepuser"
        long sourceId = 25L;   // leafItem — Report under deepuser depth chain
        long destId   = 12L;   // deepuser home folder

        double publishedBefore = meterRegistry.counter("itemtree.event.published", "op", "COPY").count();
        double droppedBefore   = meterRegistry.counter("itemtree.event.self_dropped").count();

        List<CachedNode> result = itemService.copyItem(sourceId, destId,
                new UserContext("deepuser", null));

        // Register all new IDs for cache cleanup after this test.
        result.forEach(n -> toCleanUp.add(n.itemTreeId()));

        long newId = result.get(0).itemTreeId();

        assertThat(result).hasSize(1);

        // The local cache holds the copy (written by applyCopy before the event is published).
        assertThat(cache.getById(newId)).isPresent();
        assertThat(cache.getById(newId).get().parentId()).isEqualTo(destId);

        // A COPY event was published to the bus …
        assertThat(meterRegistry.counter("itemtree.event.published", "op", "COPY").count())
                .isEqualTo(publishedBefore + 1.0);
        // … and the local self-echo was suppressed.
        assertThat(meterRegistry.counter("itemtree.event.self_dropped").count())
                .isEqualTo(droppedBefore + 1.0);
    }
}

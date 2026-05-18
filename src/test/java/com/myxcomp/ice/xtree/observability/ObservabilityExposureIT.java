package com.myxcomp.ice.xtree.observability;

import com.myxcomp.ice.xtree.cache.CacheReadinessGate;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end smoke test verifying that every §18 metric appears on
 * {@code /actuator/prometheus} after a representative workload, and that the
 * three health endpoints respond correctly.
 *
 * <p>The test runs with {@code show-details: always} so the health sub-paths
 * return full JSON even without authentication headers.
 *
 * <p>Uses {@code RANDOM_PORT} — all HTTP writes are real and committed.
 * {@link #cleanUpCreatedItems()} in {@code @AfterEach} removes any items
 * inserted during the workload so that the shared H2 database is not polluted
 * for other IT classes that run in the same JVM.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        // Expose health sub-paths with full detail (avoids 'when-authorized' hiding bodies in tests)
        "management.endpoint.health.show-details=always"
})
class ObservabilityExposureIT {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private CacheReadinessGate gate;

    /** Item ids created during a test that must be deleted in @AfterEach. */
    private final List<Long> createdIds = new ArrayList<>();

    @BeforeEach
    void waitForCache() {
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .until(gate::isReady);
    }

    @AfterEach
    void cleanUpCreatedItems() {
        // Delete in reverse order so children are removed before parents.
        // Items already deleted during the test will return 404, which we ignore.
        List<Long> reversed = new ArrayList<>(createdIds);
        java.util.Collections.reverse(reversed);
        for (Long id : reversed) {
            rest.exchange(
                    "/api/v1/itemtree/items/" + id,
                    HttpMethod.DELETE,
                    new HttpEntity<>(iceHeaders()),
                    Void.class);
        }
        createdIds.clear();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private HttpHeaders iceHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Ice-User", "testuser");
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    /**
     * POST /api/v1/itemtree/items, assert 201, register id for cleanup, and return the id.
     */
    @SuppressWarnings("unchecked")
    private long createItem(long parentId, String name, String type, Map<String, Object> data) {
        Map<String, Object> body = data == null
                ? Map.of("parentId", parentId, "name", name, "type", type)
                : Map.of("parentId", parentId, "name", name, "type", type, "data", data);

        ResponseEntity<Map> resp = rest.exchange(
                "/api/v1/itemtree/items",
                HttpMethod.POST,
                new HttpEntity<>(body, iceHeaders()),
                Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Number id = (Number) resp.getBody().get("itemTreeId");
        assertThat(id).isNotNull();
        long itemId = id.longValue();
        createdIds.add(itemId);
        return itemId;
    }

    /**
     * Attempt to create an item — may succeed or fail (e.g. validation rejection).
     * If a 201 is returned the created id is registered for cleanup.
     */
    @SuppressWarnings("unchecked")
    private ResponseEntity<String> tryCreateItem(long parentId, String name, String type,
                                                  Map<String, Object> data) {
        Map<String, Object> body = data == null
                ? Map.of("parentId", parentId, "name", name, "type", type)
                : Map.of("parentId", parentId, "name", name, "type", type, "data", data);

        ResponseEntity<String> resp = rest.exchange(
                "/api/v1/itemtree/items",
                HttpMethod.POST,
                new HttpEntity<>(body, iceHeaders()),
                String.class);

        if (resp.getStatusCode() == HttpStatus.CREATED && resp.getBody() != null) {
            // Parse the id from the JSON body so we can clean up later.
            try {
                String body2 = resp.getBody();
                int idx = body2.indexOf("\"itemTreeId\":");
                if (idx >= 0) {
                    int start = idx + "\"itemTreeId\":".length();
                    int end = body2.indexOf(',', start);
                    if (end < 0) end = body2.indexOf('}', start);
                    String idStr = body2.substring(start, end).trim();
                    createdIds.add(Long.parseLong(idStr));
                }
            } catch (NumberFormatException ignored) { }
        }
        return resp;
    }

    private void deleteItem(long id) {
        ResponseEntity<Void> resp = rest.exchange(
                "/api/v1/itemtree/items/" + id,
                HttpMethod.DELETE,
                new HttpEntity<>(iceHeaders()),
                Void.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Workload + Prometheus scrape
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void allSection18MetricsArePresentOnPrometheusEndpoint() {
        // 1. Create a Folder under root (parentId=1)
        long folderId = createItem(1L, "ObsIT_Folder", "Folder", null);

        // 2. Create a Report under that folder (has data)
        long reportId = createItem(folderId, "ObsIT_Report", "Report",
                Map.of("k", 1));

        // 3. Bulk get by ids — exercises /items/get
        Map<String, Object> getReq = Map.of("ids", List.of(folderId, reportId));
        ResponseEntity<List> getResp = rest.exchange(
                "/api/v1/itemtree/items/get",
                HttpMethod.POST,
                new HttpEntity<>(getReq, iceHeaders()),
                List.class);
        assertThat(getResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 4. Delete the folder — cascades the report → fires itemtree.delete.cascade.size
        //    Also remove them from the cleanup list (already deleted).
        deleteItem(folderId);
        createdIds.remove(Long.valueOf(folderId));
        createdIds.remove(Long.valueOf(reportId));

        // 5. POST /actuator/itemtree-refresh/delta — fires refresh metrics
        ResponseEntity<String> refreshResp = rest.exchange(
                "/actuator/itemtree-refresh/delta",
                HttpMethod.POST,
                new HttpEntity<>(iceHeaders()),
                String.class);
        assertThat(refreshResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 6. Attempt to create a Folder WITH data → TYPE_CANNOT_HAVE_DATA validation rejection
        //    This is rejected (400) so no item is persisted; nothing to clean up.
        ResponseEntity<String> rejResp = tryCreateItem(1L, "ObsIT_InvalidFolder", "Folder",
                Map.of("illegal", true));
        assertThat(rejResp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 7. Create an item with unknown type WITH data → unknown_type counter is incremented.
        //    The item is persisted (unknown types are not in types-without-data, so data is
        //    accepted); it will be cleaned up by @AfterEach.
        tryCreateItem(1L, "ObsIT_Unknown", "Phase12_Unknown", Map.of("x", 1));

        // ── Scrape /actuator/prometheus ──────────────────────────────────────
        ResponseEntity<String> prom = rest.getForEntity("/actuator/prometheus", String.class);
        assertThat(prom.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = prom.getBody();
        assertThat(body).isNotBlank();

        // §18 Cache metrics
        assertThat(body).contains("itemtree_cache_size");
        assertThat(body).contains("itemtree_cache_bootstrap_duration_seconds");
        assertThat(body).contains("itemtree_cache_bootstrap_rows");
        assertThat(body).contains("itemtree_cache_bootstrap_attempts_total");
        assertThat(body).contains("itemtree_cache_refresh_delta_duration_seconds");
        assertThat(body).contains("itemtree_cache_last_refresh_age_seconds");

        // §18 Messaging / Solace metrics
        assertThat(body).contains("itemtree_solace_connected");
        assertThat(body).contains("itemtree_solace_outage_seconds");
        assertThat(body).contains("itemtree_solace_last_event_age_seconds");

        // §18 Business metrics
        assertThat(body).contains("itemtree_delete_cascade_size");
        assertThat(body).contains("itemtree_policy_unknown_type_total");
        assertThat(body).contains("itemtree_policy_validation_rejection_total");

        // itemtree.conversion.xml_to_json.failure and itemtree.conversion.json_to_xml.failure
        // are error-path-only counters — they only appear in Prometheus after at least one
        // conversion failure. They are covered by ItemServiceMetricsTest unit tests.
        // Provoking a deliberate conversion failure in this IT would require seeding a
        // malformed payload row which would pollute the H2 DB for other tests.

        // Common instanceId tag applied to every meter
        assertThat(body).contains("instanceId=\"");

        // Standard Spring Boot / HikariCP / Micrometer metrics
        assertThat(body).contains("hikaricp_connections");
        assertThat(body).contains("http_server_requests_seconds");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Health endpoints
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void cacheHealthEndpointReturnsUp() {
        ResponseEntity<String> resp = rest.getForEntity("/actuator/health/cache", String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void dbHealthEndpointReturnsUp() {
        ResponseEntity<String> resp = rest.getForEntity("/actuator/health/db", String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void messagingHealthEndpointResponds() {
        // Messaging health may be UP or DOWN depending on stub connectivity;
        // we only assert the endpoint is reachable (2xx or 503 are both valid).
        ResponseEntity<String> resp = rest.getForEntity("/actuator/health/messaging", String.class);
        assertThat(resp.getStatusCode().value())
                .as("messaging health should return 200 or 503, not a missing-endpoint 404")
                .isNotEqualTo(404);
    }

    @Test
    void deltaRefreshActuatorReturns200() {
        ResponseEntity<String> resp = rest.exchange(
                "/actuator/itemtree-refresh/delta",
                HttpMethod.POST,
                new HttpEntity<>(iceHeaders()),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}

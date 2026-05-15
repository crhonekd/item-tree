# IMPLEMENTATION_NOTES.md — ITEMTREE Service

Phased build plan. Each phase produces something testable. Order can flex but the dependency arrows below are real.

For project context and conventions, see `CLAUDE.md`. For the design, see `itemtree-service-design.md`.

---

## Two-phase environment

This codebase is built across two environments — read `CLAUDE.md` → "Development environment — two phases" for the full picture.

- **Phase A (current, personal PC):** No Oracle, no company libraries, no internal artifact repos. Everything below is implemented against H2 (Oracle compat mode) and against in-memory stubs of company libraries. Spring profile `dev` is the default. Claude Code manages `build.gradle.kts` using only public-repo dependencies.
- **Phase B (later, work PC, user-managed):** Oracle replaces H2; company JMS library and in-house XML/JSON converter replace the stubs via `@Profile("prod")` beans. The user edits `build.gradle.kts` to add internal artifact repos. Application code is unchanged.

Every phase below is implementable in Phase A. **There is no need to wait for company libraries.** The only true blocker is Phase 13 (full end-to-end against real Oracle + real Solace), which is naturally a Phase B activity.

---

## External dependencies

| Dependency | Phase | Owner | Notes |
|---|---|---|---|
| H2 (Oracle compat mode) | A | this codebase | Maven Central; `MODE=Oracle` |
| `JMSListenerService`, `JMSPublisherService`, `ConnectionExceptionListener` | B | Deployment / messaging team | Stubbed in Phase A |
| `addRecoveryListener(ConnectionRecoveryListener)` hook | B | Same | Stub fires programmatically in Phase A |
| In-house XML/JSON converter | B | Internal | Jackson XML-mapper stub in Phase A |
| `LASTUPDATE` index on `ITEMTREE` | B | DBA | Phase A H2 schema includes it as a normal `CREATE INDEX` |
| Real Oracle 19c | B | Ops | Phase A uses H2 |

---

## Phase 0 — Scaffolding ✅ COMPLETE (2026-05-14, tagged `phase-0-scaffolding`)

**Deviations from plan (reviewed and approved):**
- `springdoc-openapi-starter-webmvc-ui` bumped `2.7.0` → `2.8.6` (3.4.x-aligned release)
- `jackson-datatype-jsr310` removed from explicit deps (redundant — transitive via `spring-boot-starter-web`)
- `gradlePluginPortal()` removed from `repositories {}` (not needed for project deps)

**Goal:** runnable empty Spring Boot app with the agreed package layout, H2 wired, and the `dev` profile active by default.

- Author `build.gradle.kts` and `settings.gradle.kts` with **public-repo dependencies only**:
  - Spring Boot starter web, validation, jdbc, actuator
  - HikariCP (transitive via spring-boot-starter-jdbc)
  - H2 (`runtimeOnly` / `testRuntimeOnly`)
  - Jackson `jackson-datatype-jsr310`, `jackson-dataformat-xml`
  - Lombok (compileOnly + annotationProcessor)
  - Micrometer + `micrometer-registry-prometheus`
  - SpringDoc OpenAPI starter (for `/v3/api-docs`)
  - openapi-generator-gradle-plugin
  - JUnit 5 (`spring-boot-starter-test`), AssertJ (transitive), Mockito (transitive)
- Package layout per design §16, all under `com.myxcomp.ice.xtree`.
- `ItemTreeApplication` with `@SpringBootApplication`.
- `application.yml` with `spring.profiles.active: dev` and the section headers from design §17 as placeholders.
- `application-dev.yml` with H2 datasource:
  ```yaml
  spring:
    datasource:
      url: jdbc:h2:mem:itemtree;MODE=Oracle;DB_CLOSE_DELAY=-1
      driver-class-name: org.h2.Driver
      username: sa
      password: ""
    sql:
      init:
        mode: always
        schema-locations: classpath:db/schema.sql
        data-locations: classpath:db/data.sql
  ```
- `src/main/resources/db/schema.sql` — mirrors production DDL:
  ```sql
  CREATE TABLE ITEMTREE (
      ITEMTREEID     NUMBER(10) PRIMARY KEY,
      PARENTID       NUMBER(10) NOT NULL,
      NAME           VARCHAR2(70) NOT NULL,
      TYPE           VARCHAR2(30) NOT NULL,
      XML            CLOB,
      LASTUPDATEUSER VARCHAR2(20),
      LASTUPDATE     DATE,
      JSON           CLOB
  );
  CREATE INDEX IDX_ITEMTREE_PARENTID  ON ITEMTREE(PARENTID);
  CREATE INDEX IDX_ITEMTREE_LASTUPDATE ON ITEMTREE(LASTUPDATE);
  CREATE SEQUENCE ITEMTREE_ID_SQN START WITH 100000 INCREMENT BY 1;
  ```
- `src/main/resources/db/data.sql` — dummy data covering at minimum:
  - The root folder (`ITEMTREEID=1, PARENTID=0, NAME='root', TYPE='Folder'`)
  - A few first-level folders, including a `Users` folder
  - User home folders under `Users` (e.g. `testuser1`, `testuser2`, each a `Folder` whose name matches the username)
  - A deep nested branch (depth 7+) to test ancestor-chain assembly
  - At least one item of each major type from design §10:
    - `Folder`, `Shortcut`, `Shortcut.Report`, `Shortcut.Filter`, `Shortcut.Filter.Nested` (no data)
    - `DrillDown.Set`, `Report`, `Filter`, `Details.Column.Collection`, `Numeric.Bucket.Collection`, `Discrete.Bucket.Collection`, `Bucket.Collection` (JSON + XML populated)
    - `View`, `UDF.Context`, `Eval` (JSON only)
  - At least one row with `JSON IS NULL AND XML IS NOT NULL` to exercise the read-side fallback + backfill path
- `logback-spring.xml` configured for plain-text logging.
- `/actuator/health` returns UP.

**Done when:** the app starts, `curl /actuator/health` returns 200, and the H2 console (if enabled) shows the schema and rows.

**Actual done state:** app starts in ~1.5 s; `/actuator/health` → `{"status":"UP"}`; `/actuator/prometheus` → exposition format; `./gradlew clean build` → BUILD SUCCESSFUL; `ItemTreeApplicationTests` (3 tests) green.

---

## Phase 1 — API contract ✅ COMPLETE (2026-05-14)

**Deviations from plan (reviewed and approved):**
- `useTags=true` added to openapi-generator config (required to split by tag into separate interfaces)
- `doLast` block added to delete generated `ApiApi.java` (avoids duplicate route mappings)
- `springdoc.api-docs.version: openapi_3_0` added to application.yml (forces 3.0 format for test assertion)
- `OpenApiConfig` bean added in `config/` to set title "ItemTree API" (springdoc defaults to "OpenAPI definition" without it)
- `schema.sql` updated to use `CREATE TABLE IF NOT EXISTS` / `CREATE INDEX IF NOT EXISTS` / `CREATE SEQUENCE IF NOT EXISTS` (idempotent for multiple test ApplicationContexts sharing the named H2 DB)
- `data.sql` prefixed with `DELETE FROM ITEMTREE` (idempotent re-seeding across multiple test contexts)
- Test assertion updated: `$.openapi` uses `startsWith("3.0")` instead of exact `"3.0.3"` (springdoc with `openapi_3_0` produces "3.0.1")

**Goal:** OpenAPI spec authored; generated stubs land in the right package; stub controllers prove the wiring.

- Author `src/main/resources/openapi/itemtree-api.yaml` per design §3.
- The openapi-generator plugin (user owns Gradle config) targets `com.myxcomp.ice.xtree.generated.api` for interfaces and `com.myxcomp.ice.xtree.generated.model` for DTOs.
- For each generated `*Api` interface, create a stub controller in `api/controller/` that throws `UnsupportedOperationException`.
- `springdoc-openapi` exposes `/v3/api-docs` matching the source spec.

**Done when:** `./gradlew build` generates DTOs without errors, the app starts, and `/v3/api-docs` returns the spec.

---

## Phase 2 — Domain types and common primitives ✅ COMPLETE (2026-05-14)

**Goal:** all immutable value types in place; `TimeMapper` is the only thing that touches timezone conversion.

- `common/`: `TreeConstants`, `Types`, `InstanceIdProvider`, `TimeMapper`, `UserContext`.
- `cache/`: `CachedNode`, `TreeSnapshot` records.
- `persistence/`: `StructuralRow`, `PayloadRow`, `JsonBackfillRow` records.
- `messaging/event/`: `TreeMutationEvent` envelope, `OperationType` enum, `payload/` records (`CreatePayload`, `UpdatePayload`, `MovePayload`, `RenamePayload`, `DeletePayload`).
- Polymorphic Jackson config on `TreeMutationEvent` for `payload` discrimination by `operationType`.

**Tests:**
- `TimeMapperTest` — round-trip `Instant` ↔ `LocalDateTime`, JVM-timezone independence.
- `TreeMutationEventTest` — serialize/deserialize each operation type round-trip.
- `InstanceIdProviderTest` — stable across the JVM lifetime.

**Deviations from plan (reviewed and approved):**
- `parentId` changed from `long` (primitive) to `Long` (boxed) in `CachedNode`, `StructuralRow`, and `CreatePayload`, matching the design doc spec (§4, §6, §12) and CLAUDE.md invariant "parentId is Long, not nullable." The plan mistakenly used primitive `long`; caught by code review.
- `TreeMutationEvent` implemented as `@Value @Builder @Jacksonized` class (not a record), required for reliable Jackson EXTERNAL_PROPERTY polymorphic deserialization via the builder path.

**Actual done state:** All Phase 2 types compile. TimeMapperTest (6), InstanceIdProviderTest (3), TreeMutationEventTest (9) all green. `./gradlew clean build` → BUILD SUCCESSFUL; 23 tests pass.

---

## Phase 3 — Persistence ✅ COMPLETE (2026-05-15, tagged `phase-3-persistence`)

**Goal:** `ItemTreeRepository` complete and verified against H2 (Oracle compat mode).

- `ItemTreeRepository` interface per §12.
- `JdbcItemTreeRepository` implementation using `JdbcClient` (plus `JdbcTemplate` for `streamAllStructural`).
- Row mappers in `persistence/rowmapper/`.
- CLOB column reads via `getString` by default.
- IN-list chunking at 1000 ids.
- Cascade delete: BFS traversal collects ids, chunked DELETE. (See deviation note below.)
- Sequence-based id generation. **Portability note:** `INSERT ... RETURNING ... INTO` is Oracle-specific. Prefer a portable two-step in `JdbcClient`: `SELECT ITEMTREE_ID_SQN.NEXTVAL FROM DUAL` (works on both Oracle and H2 in Oracle mode) → use the returned value in the INSERT. Wrap both in the same transaction.
- All write methods stamp `LASTUPDATE`/`LASTUPDATEUSER` (except `backfillJsonWhereNull`, which only writes the JSON column).

**Tests:**
- `JdbcItemTreeRepositoryIT` — uses the same H2 schema and seed data as the dev profile.
- Covers every method including edge cases: empty id list, 1001+ id chunking, cascade delete of an empty subtree, conditional backfill (returns 0 if JSON already populated), CLOB round-trip (8 KB payloads), empty-table streaming, full-tree cascade from root, batch backfill (mixed-state and all-null), sub-second boundary for delta query.
- 30 IT tests, 79 total tests — all green.

**Deviations from plan (reviewed and approved):**
- `cascadeDeleteSubtree` uses **BFS traversal** instead of the recursive CTE specified in design §12. H2 2.x cannot resolve a recursive CTE self-reference when SQL is sent as a `PreparedStatement` (which Spring's `JdbcClient`/`NamedParameterJdbcTemplate` always uses). Error: `Table "SUB" not found [42102-232]` during prepare-phase parsing. The CTE works with plain `Statement.execute()` but not `PreparedStatement`. BFS achieves identical semantics and is fully Oracle-portable. **Phase B note:** If Oracle 19c is tested in Phase 14, verify BFS performs acceptably for expected tree depths (max ~10 levels); if not, the implementation can be switched to a `StatementCallback` with the CTE at that point.
- `streamAllStructural` uses `JdbcTemplate.query(PreparedStatementCreator, RowCallbackHandler)` (not `JdbcClient`) to set `fetchSize=1000` per-query. H2 ignores fetchSize but the code is correct for Oracle.
- `backfillJsonWhereNull` uses `JdbcTemplate.batchUpdate` (not a per-row `JdbcClient` loop) to match the design §11 "batched per request" requirement. `@Transactional` added at the repository method level as well (service layer will also provide a transaction boundary in Phase 7).
- `cascadeDeleteSubtree` has `@Transactional` at the repository method level so the BFS reads and chunked DELETEs share one connection even before the service layer exists. This matches the design §12 "single transaction" requirement.
- `StructuralRowMapper` reads `PARENTID` via `rs.getObject("PARENTID", Long.class)` rather than `rs.getLong` so SQL NULL (schema-forbidden but defensively handled) is preserved as `null` on the boxed-`Long` record field rather than silently becoming `0`.
- Contract Javadoc added to all nine `ItemTreeRepository` interface methods (chunking, idempotency, no-LASTUPDATE-touch, return semantics).

---

## Phase 4 — Cache ✅ COMPLETE (2026-05-15)

**Goal:** in-memory tree with the full `apply*` tolerance contract; concurrency proven.

- `TreeCache` interface per §4.
- `DefaultTreeCache` implementation with `ReentrantReadWriteLock`.
- `SnapshotBuilder` — builds a `TreeSnapshot` row-by-row (used by bootstrap and full reload).
- `CacheReadinessGate` — volatile flag, surfaces Spring `AvailabilityChangeEvent`.
- `apply*` tolerance per design §4 — never throws on missing references.
- All read methods return defensive copies.
- Cycle defence in parent walks (cap iterations, log + flag).

**Tests:**
- Invariant tests for each `apply*`.
- Idempotency: applyCreate twice with same id; applyDelete on missing id; applyMove with missing newParent.
- Concurrency stress: N reader threads + 1 writer thread for M seconds; assert no exceptions and final state matches expected.
- `replaceAll` — snapshot swap is atomic from a reader's viewpoint.

**Deviations from plan (reviewed and approved):**
- `CachedNode` compact-constructor `Objects.requireNonNull(parentId, "parentId")` guard added as part of Phase 4 (was a follow-up note in Phase 3 quality review). Tests added in `CachedNodeTest`.
- `SnapshotBuilder.build()` deep-copies inner `Set<Long>` values via `Set.copyOf()` in addition to outer `Map.copyOf()`, producing a fully immutable `TreeSnapshot`. Plain shallow `Map.copyOf()` was rejected in code review as it left inner sets mutable.
- `replaceAllIsAtomicFromReadersView` concurrency test was redesigned from the original plan spec. The original used two separate read-lock acquisitions (`getChildren` then `exists`) creating a TOCTOU window; the final test verifies the correct atomicity guarantee: within a single `getChildren` call the returned children all belong to one snapshot (A or B), never a mix.
- `searchByName(null, ...)` guards with `Objects.requireNonNull` (defensive boundary at cache level, not just service level).
- `isAncestor(x, x)` returns `false` via an explicit early-return guard rather than relying on tree structure.
- `applyDelete` contract documented on the `TreeCache` interface: caller must pass the complete descendant set.

**Post-completion quality fixes (applied after audit, same phase):**
- `removeFromChildren` / `removeFromFoldersByName` helpers now prune empty `Set<Long>` entries from `childrenByParent` and `foldersByName` after removal using the two-arg `Map.remove(key, value)` to prevent unbounded map growth under churn.
- `Objects.requireNonNull` guards added to all six public mutation methods (`applyCreate`, `applyMetadataUpdate`, `applyMove`, `applyRename`, `applyDelete`, `replaceAll`) covering node, ids, snapshot, newName, lastUpdate, and lastUpdateUser parameters; all guards precede lock acquisition.
- `CacheReadinessGate.markReady()` replaced `volatile boolean` check-then-act with `AtomicBoolean.compareAndSet` so the `ACCEPTING_TRAFFIC` event is published exactly once regardless of concurrent callers.
- `MAX_ANCESTOR_WALK` Javadoc corrected: removed incorrect "independent of live map size" claim; the effective cap is `min(cache-size+1, 10_000)`.
- `DefaultTreeCacheTest` extended with: 10 null-guard assertion tests (`NullGuards` nested class); 4 edge-case tests (`EdgeCases` nested class) covering no-op move, no-op rename, rename-to-shared-folder-name, and orphan-parent create; concurrency stress writer broadened to rotate through all five mutation types (`applyCreate`, `applyMetadataUpdate`, `applyRename`, `applyMove`, `applyDelete`) with a second concurrent `replaceAll` writer thread.

**Actual done state:** 155 tests green; `./gradlew clean build` → BUILD SUCCESSFUL.

---

---

## Phase 5 — Type policy & conversion ✅ COMPLETE (2026-05-15)

**Goal:** `TypePolicy` validates at startup; `XmlJsonConverter` interface available; Phase A stub backed by Jackson.

- `DataProperties` `@ConfigurationProperties("itemtree.data")`.
- `TypePolicy` interface + `ConfigurableTypePolicy` impl per §10.
- Startup validation: `Folder` in `types-without-data`; no overlap between `types-without-data` and the other lists; no whitespace in entries; INFO/WARN log of types seen in DB vs configured.
- `XmlJsonConverter` interface in `conversion/`.
- **Phase A stub:** `JacksonXmlJsonConverter` in `conversion/dev/`, annotated `@Profile("dev")`. Uses `com.fasterxml.jackson.dataformat.xml.XmlMapper` for both directions. Good enough for unit tests and round-trip verification; structural fidelity to the company library's XML output is not guaranteed.
- **Phase B:** real `BarcapXmlJsonConverter` wrapping the in-house library — added later, in `conversion/prod/` with `@Profile("prod")`.

**Tests:**
- Policy decision matrix.
- Startup validation rejects Folder missing, overlapping lists, whitespace.
- Stub converter round-trips for representative payloads.
- Note in tests that the stub is not byte-identical with the production converter; assertions check structural equivalence, not exact XML strings.

**Deviations from plan (reviewed and approved):**
- `catch (java.io.IOException e)` clauses removed from `JacksonXmlJsonConverter` — `JsonProcessingException` extends `IOException`, making them dead code. Jackson's `getOriginalMessage()` used for clean error messages.
- Round-trip test (`roundTripXmlPreservesElementTree`) compares `XmlMapper.readTree()` output rather than raw XML strings, since `XmlMapper.readTree()` strips root element names — confirmed acceptable stub behaviour.

**Post-completion quality fixes (applied after audit, same day):**
- `Objects.requireNonNull(type, "type")` guards added to all four public `TypePolicy` methods (`hasData`, `isAlsoPersistedAsXmlOnWrite`, `isSentAsXmlToUi`, `isKnown`) in `ConfigurableTypePolicy`. Matches the Phase 4 post-audit pattern. Tests pin the NPE message.
- `JacksonXmlJsonConverter`: null + blank guards added to both `xmlToJson` and `jsonToXml` before any Jackson calls; `IllegalStateException` changed to `IllegalArgumentException` in both catch blocks (malformed input is a caller contract violation, not a state error). `XmlJsonConverter` interface Javadoc updated with `@throws` declarations for both exception types. Tests assert exception messages.
- `@Order(Ordered.LOWEST_PRECEDENCE)` added to `TypePolicyStartupAuditor` to document ordering intent ahead of Phase 9 (`TreeCacheBootstrap @Order(1)`, `MessagingStarter @Order(2)`).
- `permitsOverlapBetweenXmlOnWriteAndXmlToUi` test strengthened: now asserts all three policy methods return the expected values, not just that construction doesn't throw.

**Actual done state:** 202 tests green; `./gradlew clean build` → BUILD SUCCESSFUL.

---

## Phase 6 — `getTreeView` algorithm + path resolution ⬅ NEXT

**Goal:** the trimmed tree view assembly proven across all edge cases.

- `DefaultTreeCache.getTreeView(long homeFolderId)` per §8.
- `PathResolver` per §9 with in-call memoisation.
- Both functions execute under the read lock.

**Tests:**
- Home folder at depth 0, 1, 2, deep (e.g. 7).
- Home folder empty.
- Home folder with mixed-type children.
- Drift cases: home folder missing (throws), missing ancestor mid-walk (warning + partial result).
- Cycle defence trigger.
- Path resolution memoisation: ancestors walked once per `pathsOf` call regardless of input size.

---

## Phase 7 — Services

**Goal:** business logic complete; services don't depend on the HTTP layer or messaging impl.

- `ItemService` — create, delete, move, rename, updateData. Validates against cache; persists via repository; updates cache; publishes via `EventPublisher` interface.
- `TreeService` — `getTree`, `getSubtree`. Calls `TreeCache` then `PathResolver`.
- `SearchService` — by id or name; uses cache.
- `HomeFolderService` — wraps `cache.findHomeFolder`.
- `PathResolver` impl (interface from Phase 6).
- All services take `UserContext` explicitly as a method parameter.
- Async backfill `TaskExecutor` configured (single-threaded, bounded queue).

**Tests:**
- Service unit tests with mocked cache / repository / publisher / type policy.
- Cover validation rejection paths: `TYPE_CANNOT_HAVE_DATA`, `DATA_REQUIRED`, `PARENT_NOT_FOUND`, `PARENT_NOT_FOLDER`, `MOVE_INTO_DESCENDANT`, `NEW_PARENT_NOT_FOLDER`.
- Verify event publish is called *after* DB write and cache update.

---

## Phase 8 — HTTP layer

**Goal:** end-to-end request flow works; error responses follow RFC 7807.

- Controllers in `api/controller/` implementing generated `*Api` interfaces.
- `api/mapper/` — `ItemNodeMapper`, `ItemNodeWithDataMapper`.
- `GlobalExceptionHandler` + `ProblemFactory` — RFC 7807 with `errorCode` and `traceId` extensions.
- `UserContextInterceptor` — extracts `X-Ice-User` + `X-Impersonated-User`; populates a request attribute; controllers pull it and pass to services.
- `CacheReadinessFilter` — 503 + Problem when cache not ready.
- All controllers cover the operations listed in §3.

**Tests:**
- `@WebMvcTest` per controller with mocked service.
- Header parsing, error mapping, status code coverage.
- Filter behaviour: 503 when gate is closed; pass-through when open.

---

## Phase 9 — Bootstrap & refresh

**Goal:** instance loads cache on startup; periodic refresh works; readiness flips correctly.

- `TreeCacheBootstrap` (`ApplicationRunner @Order(1)`) with 3-retry exponential backoff (1s, 5s, 25s).
- Index-presence check: `SELECT INDEX_NAME FROM USER_INDEXES WHERE TABLE_NAME = 'ITEMTREE' AND COLUMN_NAME = 'LASTUPDATE'`; log WARN if missing.
- Readiness transitions: `REFUSING_TRAFFIC` until cache loaded, then `ACCEPTING_TRAFFIC`.
- `RefreshOrchestrator` — delta and full reload bodies.
- `DeltaReconciler` — row diff → `apply*` dispatch.
- `RefreshScheduler` — `@Scheduled` entry points; cron from config.
- `ScheduleConfig` — `ThreadPoolTaskScheduler` with `poolSize=1`.
- `RefreshActuatorEndpoint` — `POST /actuator/itemtree-refresh?type=delta|full`.
- Full reload computes drift summary and reports via metric counters.

**Tests:**
- Bootstrap retry on transient failure; abort after 3 fails.
- Delta reconciler dispatch — synthesized row deltas produce the expected `apply*` calls.
- Full reload swap is atomic.

---

## Phase 10 — Messaging — implementable in Phase A via stubs

**Goal:** events flow through the in-memory bus; self-echoes dropped; the contracts that the real JMS library will satisfy are exercised end-to-end.

### Production-shape components (always present)

- `EventPublisher` interface in `messaging/`.
- `EventConsumerService` — pure logic class with `processPayload(String payload)`. In Phase B, this class additionally implements `jakarta.jms.MessageListener` (its `onMessage(Message)` is a one-line wrapper that extracts `TextMessage.getText()` and calls `processPayload`). In Phase A, only `processPayload` is exercised.
- `EventDispatcher` — operation type → `TreeCache.apply*`.
- `SequenceGenerator` — `AtomicLong` per JVM.
- `ConnectionRecoveryListener` interface in `messaging/`.
- `ObjectMapperConfig` — `Instant` ISO-8601 Z; JSR-310 module registered.

### Phase A stubs (in `messaging/dev/`, `@Profile("dev")`)

- `InMemoryEventBus` — `@Component` with `publish(topic, payload)` and `subscribe(topic, Consumer<String>)`. Simple `Map<String, List<Consumer<String>>>` with synchronous dispatch. Self-publishes route back into the same JVM.
- `LocalLoopbackEventPublisher implements EventPublisher` — calls `InMemoryEventBus.publish`. Increments the same `itemtree.event.published{op}` counters as the real publisher would.
- `LocalLoopbackEventConsumerStarter` — `ApplicationRunner` that subscribes `eventConsumerService::processPayload` to the bus.
- `StubConnectionExceptionListener` — exposes `addRecoveryListener(ConnectionRecoveryListener)` plus test-helper methods `simulateDisconnect()` and `simulateRecovery()`. Used by resilience tests in Phase 11.

### Tests (Phase A)

- Round-trip: publish a `TreeMutationEvent` via `LocalLoopbackEventPublisher`; verify `EventDispatcher` is invoked with the right `apply*` call.
- Self-echo drop verified.
- Deserialize failure tolerated — logged + counter, payload dropped.
- Apply failure tolerated — logged + counter, no rethrow.
- The `EventConsumerService.processPayload(String)` is unit-testable in complete isolation from JMS.

> **Follow-up (quality review):** Add `@JsonIgnoreProperties(ignoreUnknown = true)` to `TreeMutationEvent` (or configure the consumer `ObjectMapper` with `DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES = false`) before Phase 10 goes live. A newer producer adding envelope fields (e.g. trace metadata) must not break older consumer instances.

---

## Phase 11 — Resilience — implementable in Phase A via stubs

**Goal:** reconnect-driven reconciliation works against the stub exception listener; thresholds verified.

- `ConnectionStateTracker implements ConnectionRecoveryListener` — registers itself with the injected `ConnectionExceptionListener` (the stub in Phase A; the real one in Phase B) via `addRecoveryListener(...)` in `@PostConstruct`.
- Tracks `disconnectedAt`, `lastConnectedAt`, `lastEventReceivedAt`.
- First-connect special case: no prior disconnect → no reconcile.
- `ReconnectReconciler` — outage duration → no-op / delta / full reload via `RefreshOrchestrator`.
- `MessagingHealthIndicator` — gauges current state; flips DOWN after configurable outage (default `PT4H`). (Renamed from `SolaceHealthIndicator` to be agnostic of the underlying impl.)
- Wire connection-state gauges and counters per §18.

**Tests (Phase A):**
- `StubConnectionExceptionListener.simulateDisconnect()` followed by `simulateRecovery()` with controllable elapsed time → asserts the matching `RefreshOrchestrator` action.
- Threshold matrix: 30s blip (no action), 10 min (delta), 2 h (full reload), 6 h (health DOWN).
- First-connect produces no reconcile.

---

## Phase 12 — Observability & polish

**Goal:** every metric from §18 is emitted; health endpoints accurate.

- Wire each Micrometer meter at the right boundary.
- `HealthIndicator` beans for cache, Solace, DB.
- Prometheus exposure verified via `/actuator/prometheus`.
- Startup log line listing the loaded type policy in human-readable form.
- Confirm Micrometer common tag `instanceId` applied across all metrics.

**Tests:**
- Smoke test that each named metric exists after a representative workload run.

> **Follow-up (quality review):** Add Micrometer Tracing dependency and `%X{traceId}` to `logback-spring.xml` pattern when wiring Phase 12 observability. The `traceId` field is referenced in the RFC 7807 `Problem` schema (design §3) and the design notes it propagates via Micrometer Tracing (§17).

---

## Phase 13 — End-to-end (Phase A)

**Goal:** two-instance integration test proves convergence via the in-memory bus.

- `e2e/ItemTreeApplicationE2E` — `@SpringBootTest` with H2 (Oracle compat mode) and the stub messaging stack.
- Spin up two `ApplicationContext`s in the same JVM with distinct `instanceId`s, sharing the same H2 instance and the same `InMemoryEventBus`.
- Issue a mutation on instance A; assert the event is delivered to instance B and B's cache reflects the change.
- Verify self-echo suppression on A.
- Simulate a disconnect of one instance (via `StubConnectionExceptionListener.simulateDisconnect()`); after >1 hour of synthetic clock time, simulate recovery; assert reconcile triggers full reload on reconnect.

---

## Phase 14 — Work PC wiring (Phase B, user-managed)

This phase is **not implemented on the personal PC**. Once the codebase moves to the work PC, the user (or Claude Code on the work PC) executes the following:

### Datasource
- Replace `application-dev.yml` H2 datasource with an Oracle datasource in `application-prod.yml` (or override).
- Remove H2 from runtime classpath; keep it only as a test dependency if convenient.
- Verify all SQL statements run against Oracle. Any portability tweaks needed are isolated to `JdbcItemTreeRepository`.

### Company libraries
- Add `com.barcap.ice.service.jms` artifact coordinates to `build.gradle.kts`.
- Add the in-house XML/JSON converter artifact coordinates.
- Add internal Maven repository declarations.

### Prod-profile bean wiring
Create a new `config/ProdMessagingConfig.java` annotated `@Profile("prod")` providing:

- `JMSConnectionFactory`, `RecoveryAgent`, `ConnectionExceptionListener`
- `JMSSingleConnectionFactory`, `JMSAbstractService`
- `JMSListenerService` (constructed with `EventConsumerService` as `MessageListener`)
- `JMSPublisherService` (with `max_attempts = 2`)
- `EventPublisher` bean — a new `JmsEventPublisher` class wrapping `JMSPublisherService.reliablePublish(String)`
- `MessagingStarter` (`ApplicationRunner @Order(2)`) — calls `jmsListenerService.start()`
- Wires `ConnectionStateTracker` into the real `ConnectionExceptionListener` via `addRecoveryListener(...)`

Create `config/ProdConversionConfig.java` annotated `@Profile("prod")` providing a `BarcapXmlJsonConverter` bean wrapping the in-house library.

### Activation
- Set `spring.profiles.active=prod` in deployment config.
- Ensure the `dev` profile beans (`@Profile("dev")` in `*/dev` packages) are no longer wired.
- Confirm via `/actuator/beans` that only one `EventPublisher` and one `XmlJsonConverter` bean exist.

### Verification
- Repository tests re-run against a real Oracle (Testcontainers Oracle XE if Docker is available, or a dedicated test schema).
- End-to-end test re-run with the real messaging stack against a dev Solace VPN.
- Resilience scenarios re-verified: simulated VPN disconnect for 2 min / 1 h / 2 h.

---

## Done state per phase

Each phase ends with:
- Passing unit tests for new code (plus integration tests where applicable).
- No regressions on prior phases.
- A short, focused commit per logical change.
- Any design ambiguity surfaced is captured as a follow-up note.

---

## Test stack

| Layer | Tool | Phase |
|---|---|---|
| Unit | JUnit 5 + Mockito + AssertJ | A & B |
| Persistence integration | H2 (Oracle compat mode) | A |
| Persistence integration (real Oracle) | Testcontainers Oracle XE or dedicated schema | B |
| HTTP slice | `@WebMvcTest` | A & B |
| End-to-end | `@SpringBootTest` + H2 + stub bus | A |
| End-to-end (real broker) | `@SpringBootTest` + real Oracle + dev Solace VPN | B |
| Concurrency | JUnit 5 + custom executor harness (not jcstress) | A & B |

No PowerMock. No `mockStatic` except as a last resort.

---

## Common pitfalls to avoid

1. **`LocalDateTime.now()` outside `TimeMapper`.** Will produce environment-dependent bugs (works locally, breaks in prod or vice versa).
2. **Live cache references escaping read methods.** Always defensive-copy collections. Callers iterating outside the read lock will hit `ConcurrentModificationException`.
3. **Holding the cache write lock across I/O.** No DB calls, no network calls, no logging-with-blocking-appender under the write lock. In-memory pointer work only.
4. **Synchronous backfill on the `/items/get` response path.** Always async via the dedicated `TaskExecutor`. The response goes out first; the backfill follows.
5. **Business logic in controllers.** Controllers map and call services. Validation, fan-out, conversion, type policy decisions, broadcasts — all in the service layer.
6. **Tests that expect `apply*` to throw on bad input.** They don't. Assert "cache unchanged" + "warning logged".
7. **Importing from `com.myxcomp.ice.xtree.generated.*` outside `api/mapper/`.** Add an ArchUnit rule if drift starts.
8. **Forgetting `setAutoStartup(false)` semantics on the JMS listener.** We control startup; the bean must not auto-start at context refresh.
9. **Publishing events before the DB commit.** Easy mistake when refactoring; the order is DB → cache → broadcast.
10. **Treating `parentId == null` as "root".** It's `parentId == ROOT_PARENT_ID` (i.e. `0`).
11. **Forgetting to filter self-echoes by `instanceId`.** Will cause double-applies on the originating instance; mostly harmless thanks to `apply*` idempotency, but pollutes metrics.
12. **Marking the application READY before the cache is loaded.** The whole resilience story falls apart if reads hit an empty cache. Readiness is gated on `cacheReady`.

---

## Suggested first session prompt for Claude Code

> Read `CLAUDE.md`, `IMPLEMENTATION_NOTES.md`, and `itemtree-service-design.md`. Confirm you've ingested them, and that you understand we are in Phase A (personal PC, H2, stubs for company libraries, public-repo Gradle dependencies). Then propose a concrete plan for Phase 0 (scaffolding) — list the files you'd create including `build.gradle.kts`, `settings.gradle.kts`, `application.yml`, `application-dev.yml`, `schema.sql`, `data.sql`, and the package skeleton. Describe what each contains and what dummy data the seed script will cover. Don't write code yet.

From there each phase can be its own session.

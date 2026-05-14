# CLAUDE.md — ITEMTREE Service

Persistent context for Claude Code working on this codebase. Read this on every session start.

---

## What this project is

A Spring Boot REST service fronting the `ITEMTREE` Oracle table. Caches the structural tree in memory for fast reads; mutations go DB → cache → Solace broadcast; up to 6 load-balanced instances reconcile via periodic refresh and JMS events. Serves the ICEX UI.

**Authoritative design specification:** `itemtree-service-design.md` in the repo root. Read the relevant section before non-trivial work. This file (CLAUDE.md) is the *operational* companion — how to work in the codebase, not what is being built.

**Phasing and build order:** `IMPLEMENTATION_NOTES.md` in the repo root.

---

## Development environment — two phases

This codebase is being developed in two phases on different machines.

### Phase A — personal PC (current implementation phase)

No access to Oracle, no access to company internal libraries, no access to internal artifact repositories. During Phase A, Claude Code:

- **Writes `build.gradle.kts` and `settings.gradle.kts`** using only public-repo dependencies (Maven Central, Gradle Plugin Portal).
- **Stubs the company libraries** behind clean Spring profiles:
  - JMS / Solace (`com.barcap.ice.service.jms.*`) → in-memory event bus + loopback publisher/consumer.
  - In-house XML ↔ JSON converter → Jackson XML-mapper backed stub.
- **Uses H2 in Oracle compatibility mode** (`MODE=Oracle`) for both runtime and tests, with a schema script matching the real `ITEMTREE` DDL and a dummy-data script covering the test scenarios.
- **Activates the `dev` Spring profile** by default. All stub beans are gated on `@Profile("dev")`.

### Phase B — work PC (later, user-managed)

When the user copies the codebase to the work PC, they will:

- Replace H2 with Oracle at the datasource layer.
- Add internal Maven coordinates for `com.barcap.ice.service.jms` and the in-house XML/JSON converter to `build.gradle.kts`.
- Add `@Profile("prod")` bean wiring that constructs `JMSListenerService` / `JMSPublisherService` from the real library and replaces the stub `EventPublisher` and `XmlJsonConverter` with real-library-backed implementations.
- Activate the `prod` Spring profile.

**Application code is unchanged between the two phases.** Only bean wiring, profile config, and Gradle differ. Do not bake company library imports into application code, services, controllers, repositories, or anywhere outside the `prod`-profile bean configuration class(es) — and those classes are written in Phase B, not Phase A.

If a task in Phase A appears to require a company library or live Oracle, the answer is to add a stub — not to write code that won't compile on the personal PC.

---

## What is externally owned

These are managed outside this codebase across both phases. Do not create or modify them.

1. **Database schema.** Frozen, DBA-owned in production. No Flyway, no Liquibase, no schema migrations in this codebase. The one approved index addition (non-unique on `LASTUPDATE`) is owned by the DBA. The app checks `USER_INDEXES` at startup and logs a WARN if the index is missing — it does not attempt to create it. The Phase A H2 schema script mirrors the production DDL exactly.
2. **Production deployment.** Unix via CI/CD pipeline; no Kubernetes, no Helm.
3. **The `ConnectionExceptionListener` hook.** The company's messaging team owns `com.barcap.ice.service.jms.ConnectionExceptionListener` and is adding `addRecoveryListener(ConnectionRecoveryListener)` to it. Our `ConnectionRecoveryListener` interface lives in this codebase (it's the contract). The Phase A stub mimics the call sites for testing.

If a task seems to require any of these, **stop and ask** rather than improvising.

---

## Tech stack — fixed

- Java 21, latest Spring Boot compatible with Java 21
- Spring MVC on Tomcat
- Spring `JdbcClient` + HikariCP (no JPA, no Hibernate, no jOOQ)
- Lombok permitted
- OpenAPI 3.0.3 + openapi-generator (generated code in `com.myxcomp.ice.xtree.generated`)
- Jackson with JSR-310 time module
- Micrometer + Prometheus
- Logback, plain-text logging (for now)

**Phase A only (personal PC):**
- H2 (Oracle compatibility mode) as the runtime and test database
- Jackson XML mapper as the basis for the stub XML/JSON converter
- In-memory event bus for Solace stubbing

**Phase B only (work PC):**
- Oracle 19c
- Internal library `com.barcap.ice.service.jms` (Spring JMS DMLC + Jakarta JMS + Solace JMS provider)
- In-house bidirectional XML/JSON converter library

---

## Critical invariants

These hold across the codebase. Violating them produces silent bugs that aren't caught by tests.

1. **All persisted timestamps are UTC.** Use `TimeMapper` at every JDBC boundary. Do not call `LocalDateTime.now()` or `Instant.now().atZone(...)` anywhere outside `TimeMapper`. Internal canonical type is `java.time.Instant`. Defence in depth: JVM is launched with `-Duser.timezone=UTC`.

2. **`apply*` methods on `TreeCache` are idempotent and tolerant.** They never throw on missing parent or missing id. `applyCreate` is upsert. `applyMetadataUpdate`, `applyMove`, `applyRename` log and skip on missing id/reference. `applyDelete` ignores missing ids. See design §4 "apply* idempotency contract." Tests asserting that bad input throws are wrong.

3. **Write order: DB → cache → broadcast.** Never broadcast before the DB commits. Never update the cache before the DB commits. The mutation methods on `TreeCache` are called by both `ItemService` (after DB write) and `EventConsumerService` (on incoming events). See §5.

4. **Readiness (T4) is independent of Solace.** `listener.start()` returns immediately; the application becomes `ACCEPTING_TRAFFIC` whether Solace is reachable or not. If the broker is down at startup, the cache still serves reads. See §7.

5. **Tree root convention.** `parentId` is always populated, never null. `0` represents the root's parent. The root's `itemTreeId` is `1`. Use `TreeConstants.ROOT_ID` and `TreeConstants.ROOT_PARENT_ID`.

6. **Folder type literal is `"Folder"`** (capital F). Use the `Types.FOLDER` constant and `Types.isFolder(String)` helper.

7. **Generated OpenAPI code is only imported by `api/mapper/`.** Services, the cache, persistence, and messaging code never import from `com.myxcomp.ice.xtree.generated.*`. Consider an ArchUnit rule.

8. **`TypePolicy` startup validation hard-fails the bean.** Folder must be in `types-without-data`; the lists must not overlap in disallowed ways; no whitespace in entries. See §10.

9. **`concurrentConsumers = 1`** on the JMS listener container. Preserves event order. Do not raise without revisiting `apply*` tolerance.

10. **Cache read methods return defensive copies.** Never return a live reference to internal maps or sets. The read lock is released on return; callers will get `ConcurrentModificationException` if iterating over the live structure.

---

## Package roots

All under `com.myxcomp.ice.xtree`:

| Package | Contents |
|---|---|
| `api/controller` | REST controllers implementing generated `*Api` interfaces |
| `api/advice` | `GlobalExceptionHandler`, `ProblemFactory` (RFC 7807) |
| `api/mapper` | `CachedNode` ↔ generated DTOs |
| `api/filter` | `CacheReadinessFilter`, `UserContextInterceptor` |
| `service` | `ItemService`, `TreeService`, `SearchService`, `HomeFolderService`, `PathResolver` |
| `cache` | `TreeCache`, `DefaultTreeCache`, `CachedNode`, `TreeSnapshot`, `SnapshotBuilder`, `CacheReadinessGate` |
| `persistence` | `ItemTreeRepository`, `JdbcItemTreeRepository`, row records, `rowmapper/` |
| `messaging` | `EventPublisher` interface, `EventConsumerService`, `EventDispatcher`, `ConnectionRecoveryListener` interface, `ConnectionStateTracker`, `ReconnectReconciler`, `SequenceGenerator` |
| `messaging/event`, `messaging/event/payload` | Event envelope + per-op payload records |
| `messaging/dev` *(Phase A only)* | `InMemoryEventBus`, `LocalLoopbackEventPublisher`, `LocalLoopbackEventConsumer`, `StubConnectionExceptionListener` — all gated on `@Profile("dev")` |
| `bootstrap` | `TreeCacheBootstrap` (@Order 1), `MessagingStarter` (@Order 2) |
| `refresh` | `RefreshScheduler`, `RefreshOrchestrator`, `DeltaReconciler`, `RefreshResult`, `RefreshActuatorEndpoint` |
| `conversion` | `XmlJsonConverter` interface |
| `conversion/dev` *(Phase A only)* | `JacksonXmlJsonConverter` — Jackson XML mapper backed stub, `@Profile("dev")` |
| `policy` | `TypePolicy`, `ConfigurableTypePolicy` |
| `config` | `@ConfigurationProperties` and `@Configuration` beans |
| `common` | `TreeConstants`, `InstanceIdProvider`, `TimeMapper`, `UserContext`, `Types` |

The `*/dev` sub-packages contain Phase A stubs. They are NOT deleted when moving to Phase B — they remain available for testing. Phase B adds `*/prod` sub-packages (or equivalent `@Profile("prod")` beans in `config/`) that supply the real-library-backed implementations.

Tests mirror these packages, plus an `e2e/` package for `@SpringBootTest` against H2 with the stub messaging stack.

---

## Testing expectations

- Unit tests for every concrete class. Mock collaborators with Mockito.
- Repository tests run against **H2 in Oracle compatibility mode**
  (`jdbc:h2:mem:itemtree;MODE=Oracle;DB_CLOSE_DELAY=-1`). Load the same schema
  and dummy-data script as the dev profile. Cover CLOB handling, IN-list chunking,
  recursive CTE for cascade delete, and conditional backfill.
- `DefaultTreeCache`: concurrency test with parallel readers and a writer.
- E2E in `e2e/`: full wiring against H2 with the stub messaging stack, two
  `ApplicationContext`s in one JVM proving cache convergence over the in-memory bus.
- Use JUnit 5, Mockito (no PowerMock), AssertJ. Don't test Spring's own behaviour.
- Cover all execution paths: happy path, edge cases, null inputs, boundary values,
  and expected exceptions. Use `@ParameterizedTest` for input variants;
  `@Nested` inner classes to group scenarios.
- **If a test fails:** diagnose first — fix the implementation when the test is
  logically correct; fix the test only when the expectation was wrong. Never
  `@Disabled` or comment out a failing test without a `// TODO` explaining why.
  Re-run the full suite and confirm green before committing.

---

## How to start a task

1. Skim the relevant section of `itemtree-service-design.md`.
2. Check phase context in `IMPLEMENTATION_NOTES.md`.
3. Read existing code in the package(s) you'll touch.
4. Write or modify tests **alongside** the implementation — not after.
5. Run tests; commit with a clear message.

---

## Things that will look wrong but aren't

- **No `@Entity` classes anywhere.** No JPA by design; `JdbcClient` only.
- **`parentId` is `Long`, not nullable.** `0` represents root by design.
- **Some `apply*` calls log warnings on bad input and continue.** This is the idempotency contract.
- **`getTree` builds paths at response time** instead of caching them on `CachedNode`. See §9 — avoids O(subtree) writes on rename/move.
- **`TreeMutationEvent` payload shape varies by `operationType`.** Polymorphic JSON deserialization is intentional.
- **The `data` field in `getItems` responses splits into `dataJson` and `dataXml`.** At most one populated per node.

---

## Things to NEVER do

- Introduce Hibernate, JPA, jOOQ, MyBatis, Spring Data, or any other persistence framework.
- Add WebSocket / SSE — explicitly out of scope.
- Add Flyway / Liquibase or any other schema migration tool. (Schema is initialised via plain `schema.sql` for H2 in Phase A; Oracle DBA owns it in Phase B.)
- Add per-user authorization checks — UI enforces permissions.
- Catch and swallow `Throwable` in mutation paths.
- Touch `LASTUPDATE` / `LASTUPDATEUSER` in the silent XML→JSON backfill.
- Hold the cache write lock across I/O (DB calls, network calls, anything that can block).
- Return live references to internal cache structures from read methods.
- Call time-of-day APIs (`LocalDateTime.now`, `LocalDate.now`, `ZonedDateTime.now`) outside `TimeMapper`.
- Import from `com.myxcomp.ice.xtree.generated.*` outside `api/mapper/`.
- Import from `com.barcap.ice.service.jms.*` or the in-house XML/JSON converter package anywhere — those imports belong in the Phase B prod-profile bean configuration only (not yet written).
- Write production database DDL files (the schema is DBA-owned). H2 `schema.sql` for Phase A development is fine and expected.

---

## Reference index — design doc sections

When the design doc is the source of truth:

| Topic | Section |
|---|---|
| REST endpoints, schemas, error model | §3 |
| Cache structure, locking, `apply*` contract | §4 |
| Write path ordering | §5 |
| Solace, recovery, reconnect reconciliation | §6 |
| Bootstrap, delta refresh, full reload | §7 |
| `getTreeView` algorithm | §8 |
| Path resolution | §9 |
| Type policy | §10 |
| XML / JSON conversion + backfill | §11 |
| SQL surface | §12 |
| Identity (two-user model) | §13 |
| Time handling | §14 |
| Module layout | §16 |
| Configuration | §17 |
| Metrics | §18 |

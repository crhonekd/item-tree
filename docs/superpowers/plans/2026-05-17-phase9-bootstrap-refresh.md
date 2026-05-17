# Phase 9 — Bootstrap & Refresh Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On startup, populate the in-memory `TreeCache` from the database with retry/backoff and flip the application to `ACCEPTING_TRAFFIC`. Wire two background refreshes (a 30-minute delta refresh and a nightly full reload), an actuator-driven manual trigger, and a `LASTUPDATE` index presence check — covering everything in design §7 ("Bootstrap & Refresh").

**Architecture:**
- `TreeCacheBootstrap` (`ApplicationRunner @Order(1)`) streams `ItemTreeRepository.streamAllStructural` through `SnapshotBuilder`, `TreeCache.replaceAll`s the result, then calls `CacheReadinessGate.markReady()`. Retries up to N times with exponential backoff (defaults: 3 attempts × `PT1S, PT5S, PT25S`); after the last failure the runner rethrows so Spring Boot exits with failure (CI/CD readiness probe fails → deployment aborts).
- `RefreshOrchestrator` owns the body of `runDelta()` and `runFullReload()`. `RefreshScheduler` is a thin `@Scheduled` wrapper that resolves cron expressions from `RefreshProperties`. `RefreshActuatorEndpoint` exposes the same orchestrator methods at `POST /actuator/itemtree-refresh?type=delta|full`.
- `DeltaReconciler` is a pure-logic class that compares one `StructuralRow` against the live cache and dispatches exactly the right `apply*` call (`applyCreate` / `applyMove` / `applyRename` / `applyMetadataUpdate`). A single row may legitimately produce both a move and a rename when both fields differ; idempotency of `apply*` guarantees correctness.
- The full reload builds a fresh `TreeSnapshot` outside the cache write lock, computes a drift summary (created / deleted / mutated vs the pre-swap cache) via a snapshot diff helper, then calls `cache.replaceAll(snapshot)` for the atomic swap. Drift counts are emitted as Micrometer counters — the canonical health signal that the event stream is intact.
- Single-threaded `ThreadPoolTaskScheduler` (`spring.task.scheduling.pool.size: 1`) serialises bootstrap (not actually scheduled but conceptually grouped), delta, full reload, and manual triggers within an instance.
- `lastUpdateIndexExists()` is added to `ItemTreeRepository` and implemented via `DatabaseMetaData.getIndexInfo` — JDBC-portable across H2 and Oracle. The bootstrap calls it after a successful load and logs WARN if the index is absent. The H2 schema in `db/schema.sql` already creates `IDX_ITEMTREE_LASTUPDATE`, so the warning never fires in tests by default; an integration test temporarily drops the index to prove the WARN path.

**Tech Stack:** Java 21, Spring Boot (`ApplicationRunner`, `@Scheduled`, `@EnableScheduling`, `@Endpoint` / `@WriteOperation`, `ApplicationAvailability`), Spring `JdbcClient` + `DatabaseMetaData`, Micrometer (counter, timer, gauge), JUnit 5, Mockito, AssertJ. No new third-party dependencies.

---

## File Structure

### New production files

| Path | Responsibility |
|---|---|
| `src/main/java/com/myxcomp/ice/xtree/config/RefreshProperties.java` | `@ConfigurationProperties("itemtree.cache.refresh")` record carrying `deltaCron`, `deltaOverlapSeconds`, `fullReloadCron`, `bootstrapRetries`, `bootstrapBackoff` (`List<Duration>`). |
| `src/main/java/com/myxcomp/ice/xtree/config/ScheduleConfig.java` | `@Configuration @EnableScheduling` — empty class body; the annotation switches on scheduling. Spring Boot auto-configures the `ThreadPoolTaskScheduler` from `spring.task.scheduling.pool.size`. |
| `src/main/java/com/myxcomp/ice/xtree/bootstrap/TreeCacheBootstrap.java` | `ApplicationRunner @Order(1)` — retry-wrapped DB load + `replaceAll` + `markReady` + index-presence WARN. |
| `src/main/java/com/myxcomp/ice/xtree/refresh/DeltaReconciler.java` | Pure-logic class. Method: `reconcileRow(StructuralRow, DeltaCounters)`. |
| `src/main/java/com/myxcomp/ice/xtree/refresh/DeltaCounters.java` | Mutable record-like holder (`int created, moved, renamed, meta`) returned from a delta run; also used inside the reconciler for tallying. |
| `src/main/java/com/myxcomp/ice/xtree/refresh/DriftCounters.java` | Mutable holder (`int created, deleted, mutated`) for full-reload diff. |
| `src/main/java/com/myxcomp/ice/xtree/refresh/RefreshResult.java` | Record describing one refresh: `type` (`DELTA` / `FULL`), `success`, `durationMs`, `deltaCounters` *or* `driftCounters` (whichever applies; the other is `null`), `errorMessage` (`null` on success). |
| `src/main/java/com/myxcomp/ice/xtree/refresh/SnapshotDiff.java` | `static DriftCounters diff(TreeSnapshot oldSnap, TreeSnapshot newSnap)` — compares `byId` maps and counts created / deleted / mutated. |
| `src/main/java/com/myxcomp/ice/xtree/refresh/RefreshOrchestrator.java` | `@Component` — `runDelta()` and `runFullReload()`, both returning `RefreshResult` and emitting Micrometer counters / timers. |
| `src/main/java/com/myxcomp/ice/xtree/refresh/RefreshScheduler.java` | `@Component` — two `@Scheduled` methods that invoke the orchestrator with cron expressions resolved via SpEL from `RefreshProperties`. |
| `src/main/java/com/myxcomp/ice/xtree/refresh/RefreshActuatorEndpoint.java` | `@Component @Endpoint(id = "itemtree-refresh")` — `@WriteOperation refresh(@Selector type)` dispatches to delta or full. |

### Modified production files

| Path | Change |
|---|---|
| `src/main/java/com/myxcomp/ice/xtree/persistence/ItemTreeRepository.java` | Add `boolean lastUpdateIndexExists();`. |
| `src/main/java/com/myxcomp/ice/xtree/persistence/JdbcItemTreeRepository.java` | Implement `lastUpdateIndexExists` via `DataSource.getConnection().getMetaData().getIndexInfo(...)`. |
| `src/main/java/com/myxcomp/ice/xtree/cache/TreeCache.java` | Add `TreeSnapshot snapshot();` — returns the current state as an immutable `TreeSnapshot` for pre-swap diff. |
| `src/main/java/com/myxcomp/ice/xtree/cache/DefaultTreeCache.java` | Implement `snapshot()` under read lock; produce immutable copies. |
| `src/main/java/com/myxcomp/ice/xtree/cache/package-info.java` | (no change — note only) Documents the new `snapshot()` accessor. |
| `src/main/resources/application.yml` | Add `itemtree.cache.refresh.*` keys, `spring.task.scheduling.pool.size: 1`, expose `itemtree-refresh` in `management.endpoints.web.exposure.include`. |
| `src/main/java/com/myxcomp/ice/xtree/bootstrap/package-info.java` | Update wording: Phase 9 implements `TreeCacheBootstrap`; `MessagingStarter` remains a Phase 10 item. |
| `src/main/java/com/myxcomp/ice/xtree/refresh/package-info.java` | Update wording: scheduler, orchestrator, reconciler, drift counters now live here. |
| `IMPLEMENTATION_NOTES.md` | Mark Phase 9 ✅ COMPLETE; move ⬅ NEXT marker to Phase 10; record any deviations. |

### New test files

| Path | Coverage |
|---|---|
| `src/test/java/com/myxcomp/ice/xtree/config/RefreshPropertiesTest.java` | Property binding — defaults applied, overrides parsed (cron strings + `Duration` list). |
| `src/test/java/com/myxcomp/ice/xtree/config/ScheduleConfigTest.java` | `@SpringBootTest` slice asserting a `TaskScheduler` bean exists and the pool size is 1. |
| `src/test/java/com/myxcomp/ice/xtree/persistence/JdbcItemTreeRepositoryIndexCheckIT.java` | IT — happy path returns `true`; after `DROP INDEX IDX_ITEMTREE_LASTUPDATE` returns `false` (then re-creates so other tests aren't affected). |
| `src/test/java/com/myxcomp/ice/xtree/bootstrap/TreeCacheBootstrapTest.java` | Unit — single-attempt success; two transient failures then success; all failures → throws + gate stays closed; index WARN; metrics increments. |
| `src/test/java/com/myxcomp/ice/xtree/refresh/DeltaReconcilerTest.java` | Unit — new row → applyCreate; moved → applyMove; renamed → applyRename; meta-only → applyMetadataUpdate; identical → no-op; combined parent+name → both apply calls. |
| `src/test/java/com/myxcomp/ice/xtree/refresh/SnapshotDiffTest.java` | Unit — empty/empty; only-old; only-new; mixed (created / deleted / mutated counts). |
| `src/test/java/com/myxcomp/ice/xtree/refresh/RefreshOrchestratorTest.java` | Unit — `runDelta` happy path advances `lastRefreshInstant`; failure leaves it unchanged; counters emitted. `runFullReload` builds snapshot + calls `replaceAll` + emits drift counters; failure leaves cache untouched. |
| `src/test/java/com/myxcomp/ice/xtree/refresh/RefreshSchedulerTest.java` | Unit — calling the methods directly invokes the orchestrator (no need to wait on real cron). |
| `src/test/java/com/myxcomp/ice/xtree/refresh/RefreshActuatorEndpointTest.java` | Unit — `type="delta"` → delta path; `type="full"` → full path; unknown type → `IllegalArgumentException`. |
| `src/test/java/com/myxcomp/ice/xtree/bootstrap/BootstrapStartupIT.java` | `@SpringBootTest` against the dev H2 dataset — after context start the cache size matches the seed-row count, `CacheReadinessGate.isReady() == true`, and Spring's readiness state is `ACCEPTING_TRAFFIC`. |

---

## Conventions used by the rest of this plan

- **Logger:** `private static final Logger log = LoggerFactory.getLogger(<owning class>.class);` — SLF4J, no Lombok `@Slf4j`. Matches existing code.
- **Now-source:** all "now" reads go through `TimeMapper.now()` (already on the bean). Never call `Instant.now()` directly. Tests inject a mock `TimeMapper` and stub `now()`.
- **Sleep during retry:** in production code use `Thread.sleep(duration.toMillis())`; in tests, inject a `Sleeper` interface so the unit test runs in milliseconds. (See Task 3 below.)
- **Cron format:** Spring `@Scheduled` 6-field cron (`sec min hour day month dayOfWeek`). The design's strings (`"0 */30 * * * *"`, `"0 0 2 * * MON-FRI"`) are already 6-field — use them verbatim.
- **Metric naming:** identical to design §18.
- **No `@Disabled`, no `@Ignore`.**
- **AssertJ semantic methods:** `isZero()`, `isOne()`, `isEmpty()`, `isTrue()` etc — never `isEqualTo(0)` on numbers, `isEqualTo(true)` on booleans, etc.
- **`@ParameterizedTest`** for behaviour variants on the same logic (multiple combined-change inputs to the reconciler etc.).
- **Imports:** static `org.assertj.core.api.Assertions.assertThat` / `assertThatThrownBy`; static `org.mockito.Mockito.*` / `BDDMockito.*`.

---

## Task 1: `RefreshProperties` + `application.yml` config keys

**Why first:** Every later task (bootstrap retry counts, scheduler cron expressions, actuator exposure) reads from these properties. Without them, downstream beans can't be constructed at all.

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/config/RefreshProperties.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/myxcomp/ice/xtree/config/RefreshPropertiesTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/com/myxcomp/ice/xtree/config/RefreshPropertiesTest.java`:

```java
package com.myxcomp.ice.xtree.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = RefreshPropertiesTest.Config.class)
@EnableConfigurationProperties(RefreshProperties.class)
@TestPropertySource(properties = {
        "itemtree.cache.refresh.delta-cron=0 */30 * * * *",
        "itemtree.cache.refresh.delta-overlap-seconds=60",
        "itemtree.cache.refresh.full-reload-cron=0 0 2 * * MON-FRI",
        "itemtree.cache.refresh.bootstrap-retries=3",
        "itemtree.cache.refresh.bootstrap-backoff=PT1S,PT5S,PT25S"
})
class RefreshPropertiesTest {

    @org.springframework.boot.SpringBootConfiguration
    static class Config {}

    @Autowired
    private RefreshProperties props;

    @Test
    void allFieldsBindFromProperties() {
        assertThat(props.deltaCron()).isEqualTo("0 */30 * * * *");
        assertThat(props.deltaOverlapSeconds()).isEqualTo(60);
        assertThat(props.fullReloadCron()).isEqualTo("0 0 2 * * MON-FRI");
        assertThat(props.bootstrapRetries()).isEqualTo(3);
        assertThat(props.bootstrapBackoff())
                .containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(25));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests RefreshPropertiesTest`
Expected: FAIL (compile error — `RefreshProperties` does not exist).

- [ ] **Step 3: Create `RefreshProperties`**

`src/main/java/com/myxcomp/ice/xtree/config/RefreshProperties.java`:

```java
package com.myxcomp.ice.xtree.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Refresh / bootstrap tuning knobs (design §17 → {@code itemtree.cache.refresh.*}).
 *
 * @param deltaCron              Spring 6-field cron — when to fire {@code runDelta}.
 * @param deltaOverlapSeconds    {@code since} marker is decremented by this many seconds before
 *                               each delta query, covering clock-skew between app and DB.
 * @param fullReloadCron         Spring 6-field cron — when to fire {@code runFullReload}.
 * @param bootstrapRetries       Total attempts the bootstrap will make before giving up.
 *                               Must be {@code >= 1}.
 * @param bootstrapBackoff       Sleeps between successive attempts. List size must be at least
 *                               {@code bootstrapRetries - 1}; entries beyond that are ignored.
 */
@ConfigurationProperties("itemtree.cache.refresh")
public record RefreshProperties(
        String deltaCron,
        int deltaOverlapSeconds,
        String fullReloadCron,
        int bootstrapRetries,
        List<Duration> bootstrapBackoff
) {}
```

- [ ] **Step 4: Add property keys to `application.yml`**

In `src/main/resources/application.yml`, replace the existing `itemtree.cache.refresh: {}` block with:

```yaml
itemtree:
  cache:
    refresh:
      delta-cron:             "0 */30 * * * *"
      delta-overlap-seconds:  60
      full-reload-cron:       "0 0 2 * * MON-FRI"
      bootstrap-retries:      3
      bootstrap-backoff:      PT1S,PT5S,PT25S
    name-search: {}
```

(Leave the other top-level keys — `solace`, `oracle`, `data` — untouched.)

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests RefreshPropertiesTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/config/RefreshProperties.java \
        src/test/java/com/myxcomp/ice/xtree/config/RefreshPropertiesTest.java \
        src/main/resources/application.yml
git commit -m "feat(config): add RefreshProperties for bootstrap and refresh schedule"
```

---

## Task 2: `ScheduleConfig` + `spring.task.scheduling.pool.size`

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/config/ScheduleConfig.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/myxcomp/ice/xtree/config/ScheduleConfigTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/com/myxcomp/ice/xtree/config/ScheduleConfigTest.java`:

```java
package com.myxcomp.ice.xtree.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dev")
class ScheduleConfigTest {

    @Autowired
    private TaskScheduler taskScheduler;

    @Test
    void taskSchedulerPoolSizeIsOne() {
        assertThat(taskScheduler).isInstanceOf(ThreadPoolTaskScheduler.class);
        ThreadPoolTaskScheduler tpts = (ThreadPoolTaskScheduler) taskScheduler;
        assertThat(tpts.getPoolSize()).isOne();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests ScheduleConfigTest`
Expected: FAIL — without `@EnableScheduling` Spring Boot may or may not provide a `TaskScheduler` bean depending on what else is on the classpath; even if present, the pool size will be the framework default rather than the one we want pinned.

- [ ] **Step 3: Create `ScheduleConfig`**

`src/main/java/com/myxcomp/ice/xtree/config/ScheduleConfig.java`:

```java
package com.myxcomp.ice.xtree.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Switches on {@code @Scheduled} processing. The actual {@link
 * org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler} bean is auto-configured by
 * Spring Boot from {@code spring.task.scheduling.pool.size} (set to 1 in {@code application.yml}
 * — design §7 "Scheduling concurrency").
 */
@Configuration
@EnableScheduling
public class ScheduleConfig {}
```

- [ ] **Step 4: Add scheduling pool-size property**

In `src/main/resources/application.yml`, add under the existing top-level `spring:` block:

```yaml
spring:
  application:
    name: itemtree
  profiles:
    active: dev
  jackson:
    serialization:
      write-dates-as-timestamps: false
  task:
    scheduling:
      pool:
        size: 1
```

(Insert the `task:` sub-tree — leave `application`, `profiles`, `jackson` as they are.)

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests ScheduleConfigTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/config/ScheduleConfig.java \
        src/test/java/com/myxcomp/ice/xtree/config/ScheduleConfigTest.java \
        src/main/resources/application.yml
git commit -m "feat(config): enable scheduling with single-thread task scheduler"
```

---

## Task 3: `Sleeper` indirection (test seam for retry timing)

**Why:** `TreeCacheBootstrap` needs to back off (1s, 5s, 25s) between retries. We don't want real sleeps in unit tests. A small interface, default implementation, and a test-only no-op double is enough.

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/bootstrap/Sleeper.java`

- [ ] **Step 1: Create `Sleeper`**

`src/main/java/com/myxcomp/ice/xtree/bootstrap/Sleeper.java`:

```java
package com.myxcomp.ice.xtree.bootstrap;

import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Test seam for {@code Thread.sleep(...)}. The default {@link DefaultSleeper} simply delegates
 * to {@link Thread#sleep(long)}; unit tests can pass a no-op or recording double instead.
 */
public interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;

    @Component
    class DefaultSleeper implements Sleeper {
        @Override
        public void sleep(Duration duration) throws InterruptedException {
            Thread.sleep(duration.toMillis());
        }
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/bootstrap/Sleeper.java
git commit -m "feat(bootstrap): add Sleeper indirection for testable retry backoff"
```

---

## Task 4: `lastUpdateIndexExists` on `ItemTreeRepository`

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/persistence/ItemTreeRepository.java`
- Modify: `src/main/java/com/myxcomp/ice/xtree/persistence/JdbcItemTreeRepository.java`
- Test: `src/test/java/com/myxcomp/ice/xtree/persistence/JdbcItemTreeRepositoryIndexCheckIT.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/com/myxcomp/ice/xtree/persistence/JdbcItemTreeRepositoryIndexCheckIT.java`:

```java
package com.myxcomp.ice.xtree.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dev")
class JdbcItemTreeRepositoryIndexCheckIT {

    @Autowired
    private JdbcItemTreeRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void restoreIndex() {
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS IDX_ITEMTREE_LASTUPDATE ON ITEMTREE(LASTUPDATE)");
    }

    @Test
    void returnsTrueWhenIndexPresent() {
        assertThat(repository.lastUpdateIndexExists()).isTrue();
    }

    @Test
    void returnsFalseAfterIndexDropped() {
        jdbcTemplate.execute("DROP INDEX IDX_ITEMTREE_LASTUPDATE");
        assertThat(repository.lastUpdateIndexExists()).isFalse();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests JdbcItemTreeRepositoryIndexCheckIT`
Expected: FAIL (compile error — `lastUpdateIndexExists` is missing).

- [ ] **Step 3: Add the interface method**

In `src/main/java/com/myxcomp/ice/xtree/persistence/ItemTreeRepository.java`, append to the interface:

```java
    /**
     * Returns {@code true} iff the {@code ITEMTREE} table has at least one index whose
     * column list includes {@code LASTUPDATE}. Implemented via JDBC {@code DatabaseMetaData}
     * for portability across H2 and Oracle. If the metadata lookup fails for any reason, the
     * implementation logs WARN and returns {@code false}.
     */
    boolean lastUpdateIndexExists();
```

- [ ] **Step 4: Implement in `JdbcItemTreeRepository`**

In `src/main/java/com/myxcomp/ice/xtree/persistence/JdbcItemTreeRepository.java`:

Add the imports (top of file, alongside the existing ones):

```java
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
```

Add a logger field next to the existing private statics:

```java
    private static final Logger log = LoggerFactory.getLogger(JdbcItemTreeRepository.class);
```

Append at the bottom of the class:

```java
    @Override
    public boolean lastUpdateIndexExists() {
        DataSource ds = jdbcTemplate.getDataSource();
        if (ds == null) {
            log.warn("DataSource is null — cannot check LASTUPDATE index");
            return false;
        }
        try (Connection conn = ds.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getIndexInfo(null, null, "ITEMTREE", false, true)) {
                while (rs.next()) {
                    String column = rs.getString("COLUMN_NAME");
                    if ("LASTUPDATE".equalsIgnoreCase(column)) {
                        return true;
                    }
                }
            }
        } catch (SQLException e) {
            log.warn("Index-presence check failed: {}", e.getMessage());
        }
        return false;
    }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests JdbcItemTreeRepositoryIndexCheckIT`
Expected: PASS — both methods green.

- [ ] **Step 6: Run the wider persistence suite to confirm no regression**

Run: `./gradlew test --tests "com.myxcomp.ice.xtree.persistence.*"`
Expected: all green.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/persistence/ItemTreeRepository.java \
        src/main/java/com/myxcomp/ice/xtree/persistence/JdbcItemTreeRepository.java \
        src/test/java/com/myxcomp/ice/xtree/persistence/JdbcItemTreeRepositoryIndexCheckIT.java
git commit -m "feat(persistence): add lastUpdateIndexExists via JDBC metadata"
```

---

## Task 5: `TreeCache.snapshot()` accessor

**Why:** The full-reload drift summary needs to compare the *current* cache state to the *new* snapshot. The cache already builds three internal indexes that are exactly the shape of `TreeSnapshot`; the cheapest way to expose them is a read-locked accessor that returns immutable copies.

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/cache/TreeCache.java`
- Modify: `src/main/java/com/myxcomp/ice/xtree/cache/DefaultTreeCache.java`
- Modify: `src/test/java/com/myxcomp/ice/xtree/cache/DefaultTreeCacheTest.java`

- [ ] **Step 1: Write the failing test**

Append to `src/test/java/com/myxcomp/ice/xtree/cache/DefaultTreeCacheTest.java` (inside the existing class — add as a new `@Nested` class at the bottom):

```java
    @Nested
    class SnapshotAccessor {

        @Test
        void snapshotReflectsCurrentState() {
            DefaultTreeCache cache = new DefaultTreeCache();
            cache.applyCreate(new CachedNode(1L, 0L, "root", "Folder", Instant.EPOCH, "sys"));
            cache.applyCreate(new CachedNode(2L, 1L, "child", "Folder", Instant.EPOCH, "sys"));

            TreeSnapshot snap = cache.snapshot();

            assertThat(snap.byId()).containsOnlyKeys(1L, 2L);
            assertThat(snap.childrenByParent().get(0L)).containsExactly(1L);
            assertThat(snap.childrenByParent().get(1L)).containsExactly(2L);
            assertThat(snap.foldersByName()).containsOnlyKeys("root", "child");
        }

        @Test
        void snapshotMapsAreUnmodifiable() {
            DefaultTreeCache cache = new DefaultTreeCache();
            cache.applyCreate(new CachedNode(1L, 0L, "root", "Folder", Instant.EPOCH, "sys"));
            TreeSnapshot snap = cache.snapshot();
            assertThatThrownBy(() -> snap.byId().put(99L, null))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> snap.childrenByParent().get(0L).add(99L))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
```

(If `assertThatThrownBy` is not already statically imported in this file, add `import static org.assertj.core.api.Assertions.assertThatThrownBy;` to the imports.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "DefaultTreeCacheTest$SnapshotAccessor"`
Expected: FAIL (`snapshot()` method does not exist).

- [ ] **Step 3: Declare on the interface**

In `src/main/java/com/myxcomp/ice/xtree/cache/TreeCache.java`, add inside the interface (e.g. just after `replaceAll`):

```java
    /**
     * Returns an immutable copy of the current cache state, captured under the read lock.
     * Used by the full-reload drift diff (design §7) and by tests that need a frozen view.
     */
    TreeSnapshot snapshot();
```

- [ ] **Step 4: Implement in `DefaultTreeCache`**

In `src/main/java/com/myxcomp/ice/xtree/cache/DefaultTreeCache.java`, append a method (sibling to `replaceAll`):

```java
    @Override
    public TreeSnapshot snapshot() {
        lock.readLock().lock();
        try {
            Map<Long, Set<Long>> childrenCopy = new HashMap<>(childrenByParent.size());
            childrenByParent.forEach((k, v) -> childrenCopy.put(k, Set.copyOf(v)));
            Map<String, Set<Long>> foldersCopy = new HashMap<>(foldersByName.size());
            foldersByName.forEach((k, v) -> foldersCopy.put(k, Set.copyOf(v)));
            return new TreeSnapshot(
                    Map.copyOf(byId),
                    Map.copyOf(childrenCopy),
                    Map.copyOf(foldersCopy));
        } finally {
            lock.readLock().unlock();
        }
    }
```

(`HashMap`, `Map`, `Set` are already imported via `java.util.*`.)

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests "DefaultTreeCacheTest$SnapshotAccessor"`
Expected: both nested tests PASS.

- [ ] **Step 6: Run the full cache suite for no regression**

Run: `./gradlew test --tests "com.myxcomp.ice.xtree.cache.*"`
Expected: all green.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/cache/TreeCache.java \
        src/main/java/com/myxcomp/ice/xtree/cache/DefaultTreeCache.java \
        src/test/java/com/myxcomp/ice/xtree/cache/DefaultTreeCacheTest.java
git commit -m "feat(cache): add snapshot() accessor for full-reload drift diff"
```

---

## Task 6: `TreeCacheBootstrap` — retry-wrapped startup load

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/bootstrap/TreeCacheBootstrap.java`
- Test: `src/test/java/com/myxcomp/ice/xtree/bootstrap/TreeCacheBootstrapTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/com/myxcomp/ice/xtree/bootstrap/TreeCacheBootstrapTest.java`:

```java
package com.myxcomp.ice.xtree.bootstrap;

import com.myxcomp.ice.xtree.cache.CacheReadinessGate;
import com.myxcomp.ice.xtree.cache.TreeCache;
import com.myxcomp.ice.xtree.cache.TreeSnapshot;
import com.myxcomp.ice.xtree.config.RefreshProperties;
import com.myxcomp.ice.xtree.persistence.ItemTreeRepository;
import com.myxcomp.ice.xtree.persistence.StructuralRow;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TreeCacheBootstrapTest {

    private ItemTreeRepository repo;
    private TreeCache cache;
    private CacheReadinessGate gate;
    private Sleeper sleeper;
    private SimpleMeterRegistry meterRegistry;
    private RefreshProperties props;
    private TreeCacheBootstrap bootstrap;

    @BeforeEach
    void setUp() {
        repo = mock(ItemTreeRepository.class);
        cache = mock(TreeCache.class);
        gate = mock(CacheReadinessGate.class);
        sleeper = mock(Sleeper.class);
        meterRegistry = new SimpleMeterRegistry();
        props = new RefreshProperties(
                "0 */30 * * * *", 60, "0 0 2 * * MON-FRI",
                3, List.of(Duration.ofMillis(1), Duration.ofMillis(1), Duration.ofMillis(1)));
        bootstrap = new TreeCacheBootstrap(repo, cache, gate, sleeper, meterRegistry, props);
        when(repo.lastUpdateIndexExists()).thenReturn(true);
    }

    @Test
    void firstAttemptSuccessLoadsCacheAndMarksReady() throws Exception {
        doAnswer(inv -> {
            Consumer<StructuralRow> handler = inv.getArgument(0);
            handler.accept(new StructuralRow(1L, 0L, "root", "Folder", Instant.EPOCH, "sys"));
            return null;
        }).when(repo).streamAllStructural(any());

        bootstrap.run(null);

        verify(cache).replaceAll(any(TreeSnapshot.class));
        verify(gate).markReady();
        verify(sleeper, never()).sleep(any());
        assertThat(meterRegistry.counter("itemtree.cache.bootstrap.attempts").count()).isEqualTo(1.0);
    }

    @Test
    void retriesAfterTransientFailures() throws Exception {
        doThrow(new DataAccessResourceFailureException("first"))
                .doThrow(new DataAccessResourceFailureException("second"))
                .doAnswer(inv -> {
                    Consumer<StructuralRow> handler = inv.getArgument(0);
                    handler.accept(new StructuralRow(1L, 0L, "root", "Folder", Instant.EPOCH, "sys"));
                    return null;
                })
                .when(repo).streamAllStructural(any());

        bootstrap.run(null);

        verify(repo, times(3)).streamAllStructural(any());
        verify(sleeper, times(2)).sleep(any());
        verify(gate).markReady();
        assertThat(meterRegistry.counter("itemtree.cache.bootstrap.attempts").count()).isEqualTo(3.0);
    }

    @Test
    void allAttemptsFailingRethrowsAndGateStaysClosed() throws Exception {
        doThrow(new DataAccessResourceFailureException("boom"))
                .when(repo).streamAllStructural(any());

        assertThatThrownBy(() -> bootstrap.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cache bootstrap failed");

        verify(repo, times(3)).streamAllStructural(any());
        verify(gate, never()).markReady();
        verify(cache, never()).replaceAll(any());
    }

    @Test
    void missingLastUpdateIndexLogsWarningButStillSucceeds() throws Exception {
        when(repo.lastUpdateIndexExists()).thenReturn(false);
        doNothing().when(repo).streamAllStructural(any());

        bootstrap.run(null);

        verify(gate).markReady();
        verify(cache).replaceAll(any());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests TreeCacheBootstrapTest`
Expected: FAIL — compile error (class doesn't exist).

- [ ] **Step 3: Create `TreeCacheBootstrap`**

`src/main/java/com/myxcomp/ice/xtree/bootstrap/TreeCacheBootstrap.java`:

```java
package com.myxcomp.ice.xtree.bootstrap;

import com.myxcomp.ice.xtree.cache.CacheReadinessGate;
import com.myxcomp.ice.xtree.cache.SnapshotBuilder;
import com.myxcomp.ice.xtree.cache.TreeCache;
import com.myxcomp.ice.xtree.cache.TreeSnapshot;
import com.myxcomp.ice.xtree.config.RefreshProperties;
import com.myxcomp.ice.xtree.persistence.ItemTreeRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Loads the structural cache from the database at startup, with retry / exponential backoff,
 * then flips {@link CacheReadinessGate} to ready. If every attempt fails, throws so Spring Boot
 * aborts startup (design §7 "Bootstrap reliability").
 */
@Component
@Order(1)
public class TreeCacheBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TreeCacheBootstrap.class);

    private final ItemTreeRepository repository;
    private final TreeCache cache;
    private final CacheReadinessGate gate;
    private final Sleeper sleeper;
    private final MeterRegistry meterRegistry;
    private final RefreshProperties props;

    private final AtomicLong rowsLoaded = new AtomicLong(0L);

    public TreeCacheBootstrap(ItemTreeRepository repository,
                              TreeCache cache,
                              CacheReadinessGate gate,
                              Sleeper sleeper,
                              MeterRegistry meterRegistry,
                              RefreshProperties props) {
        this.repository = repository;
        this.cache = cache;
        this.gate = gate;
        this.sleeper = sleeper;
        this.meterRegistry = meterRegistry;
        this.props = props;
        meterRegistry.gauge("itemtree.cache.bootstrap.rows", rowsLoaded);
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        int maxAttempts = Math.max(1, props.bootstrapRetries());
        List<Duration> backoff = props.bootstrapBackoff();
        Timer timer = meterRegistry.timer("itemtree.cache.bootstrap.duration");

        Throwable last = null;
        long start = System.nanoTime();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            meterRegistry.counter("itemtree.cache.bootstrap.attempts").increment();
            try {
                SnapshotBuilder builder = new SnapshotBuilder();
                AtomicInteger count = new AtomicInteger();
                repository.streamAllStructural(row -> {
                    builder.accept(row);
                    count.incrementAndGet();
                });
                TreeSnapshot snapshot = builder.build();
                cache.replaceAll(snapshot);
                rowsLoaded.set(count.get());
                long elapsedNs = System.nanoTime() - start;
                timer.record(Duration.ofNanos(elapsedNs));
                log.info("Cache bootstrap succeeded on attempt {}/{}: rows={}, elapsedMs={}",
                        attempt, maxAttempts, count.get(), Duration.ofNanos(elapsedNs).toMillis());
                checkIndex();
                gate.markReady();
                return;
            } catch (RuntimeException e) {
                last = e;
                log.warn("Cache bootstrap attempt {}/{} failed: {}", attempt, maxAttempts, e.toString());
                if (attempt < maxAttempts) {
                    Duration sleep = attempt - 1 < backoff.size()
                            ? backoff.get(attempt - 1) : Duration.ofSeconds(25);
                    log.info("Sleeping {} before next bootstrap attempt", sleep);
                    sleeper.sleep(sleep);
                }
            }
        }
        throw new IllegalStateException("Cache bootstrap failed after " + maxAttempts + " attempts", last);
    }

    private void checkIndex() {
        try {
            boolean present = repository.lastUpdateIndexExists();
            if (!present) {
                log.warn("ITEMTREE.LASTUPDATE index is missing — delta refresh will full-scan");
            } else {
                log.info("ITEMTREE.LASTUPDATE index confirmed present");
            }
        } catch (RuntimeException e) {
            log.warn("Index-presence check raised exception: {}", e.toString());
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests TreeCacheBootstrapTest`
Expected: PASS — four tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/bootstrap/TreeCacheBootstrap.java \
        src/test/java/com/myxcomp/ice/xtree/bootstrap/TreeCacheBootstrapTest.java
git commit -m "feat(bootstrap): TreeCacheBootstrap with retry/backoff and index check"
```

---

## Task 7: `DeltaCounters`, `DriftCounters`, `RefreshResult`

**Why:** Plain data holders. Putting them in one task avoids drip-feeding tiny commits and the subsequent tasks (reconciler, orchestrator, endpoint) all reference them.

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/refresh/DeltaCounters.java`
- Create: `src/main/java/com/myxcomp/ice/xtree/refresh/DriftCounters.java`
- Create: `src/main/java/com/myxcomp/ice/xtree/refresh/RefreshResult.java`

- [ ] **Step 1: Create `DeltaCounters`**

`src/main/java/com/myxcomp/ice/xtree/refresh/DeltaCounters.java`:

```java
package com.myxcomp.ice.xtree.refresh;

/** Tally of {@code apply*} dispatches performed during a single delta refresh. */
public final class DeltaCounters {
    private int created;
    private int moved;
    private int renamed;
    private int meta;

    public int created() { return created; }
    public int moved()   { return moved; }
    public int renamed() { return renamed; }
    public int meta()    { return meta; }

    public void incrementCreated() { created++; }
    public void incrementMoved()   { moved++; }
    public void incrementRenamed() { renamed++; }
    public void incrementMeta()    { meta++; }

    public int total() { return created + moved + renamed + meta; }

    @Override
    public String toString() {
        return "DeltaCounters{created=" + created + ", moved=" + moved
                + ", renamed=" + renamed + ", meta=" + meta + '}';
    }
}
```

- [ ] **Step 2: Create `DriftCounters`**

`src/main/java/com/myxcomp/ice/xtree/refresh/DriftCounters.java`:

```java
package com.myxcomp.ice.xtree.refresh;

/** Tally of ids that changed between the pre-reload and post-reload snapshots (design §7). */
public final class DriftCounters {
    private int created;
    private int deleted;
    private int mutated;

    public DriftCounters() {}

    public DriftCounters(int created, int deleted, int mutated) {
        this.created = created;
        this.deleted = deleted;
        this.mutated = mutated;
    }

    public int created() { return created; }
    public int deleted() { return deleted; }
    public int mutated() { return mutated; }

    public void incrementCreated() { created++; }
    public void incrementDeleted() { deleted++; }
    public void incrementMutated() { mutated++; }

    public int total() { return created + deleted + mutated; }

    @Override
    public String toString() {
        return "DriftCounters{created=" + created + ", deleted=" + deleted
                + ", mutated=" + mutated + '}';
    }
}
```

- [ ] **Step 3: Create `RefreshResult`**

`src/main/java/com/myxcomp/ice/xtree/refresh/RefreshResult.java`:

```java
package com.myxcomp.ice.xtree.refresh;

/**
 * Outcome of one refresh invocation. {@code deltaCounters} is non-null when {@code type == DELTA};
 * {@code driftCounters} is non-null when {@code type == FULL}.
 */
public record RefreshResult(
        Type type,
        boolean success,
        long durationMs,
        DeltaCounters deltaCounters,
        DriftCounters driftCounters,
        String errorMessage
) {
    public enum Type { DELTA, FULL }

    public static RefreshResult deltaSuccess(long durationMs, DeltaCounters counters) {
        return new RefreshResult(Type.DELTA, true, durationMs, counters, null, null);
    }

    public static RefreshResult deltaFailure(long durationMs, String error) {
        return new RefreshResult(Type.DELTA, false, durationMs, null, null, error);
    }

    public static RefreshResult fullSuccess(long durationMs, DriftCounters counters) {
        return new RefreshResult(Type.FULL, true, durationMs, null, counters, null);
    }

    public static RefreshResult fullFailure(long durationMs, String error) {
        return new RefreshResult(Type.FULL, false, durationMs, null, null, error);
    }
}
```

- [ ] **Step 4: Verify compile**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/refresh/DeltaCounters.java \
        src/main/java/com/myxcomp/ice/xtree/refresh/DriftCounters.java \
        src/main/java/com/myxcomp/ice/xtree/refresh/RefreshResult.java
git commit -m "feat(refresh): add DeltaCounters, DriftCounters, RefreshResult value types"
```

---

## Task 8: `DeltaReconciler` — row diff → `apply*` dispatch

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/refresh/DeltaReconciler.java`
- Test: `src/test/java/com/myxcomp/ice/xtree/refresh/DeltaReconcilerTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/com/myxcomp/ice/xtree/refresh/DeltaReconcilerTest.java`:

```java
package com.myxcomp.ice.xtree.refresh;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.TreeCache;
import com.myxcomp.ice.xtree.persistence.StructuralRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DeltaReconcilerTest {

    private static final Instant T1 = Instant.parse("2026-05-01T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-05-02T10:00:00Z");

    private TreeCache cache;
    private DeltaReconciler reconciler;

    @BeforeEach
    void setUp() {
        cache = mock(TreeCache.class);
        reconciler = new DeltaReconciler(cache);
    }

    @Test
    void newRowDispatchesToApplyCreate() {
        StructuralRow row = new StructuralRow(42L, 1L, "fresh", "Folder", T1, "sys");
        when(cache.getById(42L)).thenReturn(Optional.empty());

        DeltaCounters counters = new DeltaCounters();
        reconciler.reconcileRow(row, counters);

        verify(cache).applyCreate(new CachedNode(42L, 1L, "fresh", "Folder", T1, "sys"));
        assertThat(counters.created()).isOne();
        assertThat(counters.total()).isOne();
    }

    @Test
    void parentChangedDispatchesToApplyMove() {
        when(cache.getById(42L)).thenReturn(Optional.of(
                new CachedNode(42L, 1L, "name", "Folder", T1, "sys")));

        reconciler.reconcileRow(
                new StructuralRow(42L, 2L, "name", "Folder", T2, "sys"),
                new DeltaCounters());

        verify(cache).applyMove(42L, 2L, T2, "sys");
        verify(cache, never()).applyCreate(any());
        verify(cache, never()).applyMetadataUpdate(anyLong(), any(), any());
    }

    @Test
    void nameChangedDispatchesToApplyRename() {
        when(cache.getById(42L)).thenReturn(Optional.of(
                new CachedNode(42L, 1L, "old", "Folder", T1, "sys")));

        DeltaCounters counters = new DeltaCounters();
        reconciler.reconcileRow(
                new StructuralRow(42L, 1L, "new", "Folder", T2, "sys"),
                counters);

        verify(cache).applyRename(42L, "new", T2, "sys");
        assertThat(counters.renamed()).isOne();
    }

    @Test
    void metaOnlyChangeDispatchesToApplyMetadataUpdate() {
        when(cache.getById(42L)).thenReturn(Optional.of(
                new CachedNode(42L, 1L, "name", "Folder", T1, "sys")));

        DeltaCounters counters = new DeltaCounters();
        reconciler.reconcileRow(
                new StructuralRow(42L, 1L, "name", "Folder", T2, "sys2"),
                counters);

        verify(cache).applyMetadataUpdate(42L, T2, "sys2");
        assertThat(counters.meta()).isOne();
    }

    @Test
    void identicalRowDispatchesNothing() {
        when(cache.getById(42L)).thenReturn(Optional.of(
                new CachedNode(42L, 1L, "name", "Folder", T1, "sys")));

        DeltaCounters counters = new DeltaCounters();
        reconciler.reconcileRow(
                new StructuralRow(42L, 1L, "name", "Folder", T1, "sys"),
                counters);

        verify(cache).getById(42L);
        verifyNoMoreInteractions(cache);
        assertThat(counters.total()).isZero();
    }

    @Test
    void combinedParentAndNameChangeDispatchesBothMoveAndRename() {
        when(cache.getById(42L)).thenReturn(Optional.of(
                new CachedNode(42L, 1L, "old", "Folder", T1, "sys")));

        DeltaCounters counters = new DeltaCounters();
        reconciler.reconcileRow(
                new StructuralRow(42L, 2L, "new", "Folder", T2, "sys"),
                counters);

        verify(cache).applyMove(42L, 2L, T2, "sys");
        verify(cache).applyRename(42L, "new", T2, "sys");
        verify(cache, never()).applyMetadataUpdate(anyLong(), any(), any());
        assertThat(counters.moved()).isOne();
        assertThat(counters.renamed()).isOne();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests DeltaReconcilerTest`
Expected: FAIL — class doesn't exist.

- [ ] **Step 3: Create `DeltaReconciler`**

`src/main/java/com/myxcomp/ice/xtree/refresh/DeltaReconciler.java`:

```java
package com.myxcomp.ice.xtree.refresh;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.TreeCache;
import com.myxcomp.ice.xtree.persistence.StructuralRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;

/**
 * Per-row diff logic: compares one {@link StructuralRow} returned by the delta query against the
 * live cache and dispatches the right {@code apply*} call(s). Idempotent — a no-op when row and
 * cached node already agree. A single row may trigger both move and rename if both fields changed;
 * the metadata stamp is set as a side-effect of those calls, so the meta-only branch is taken only
 * when neither parent nor name differs.
 */
@Component
public class DeltaReconciler {

    private static final Logger log = LoggerFactory.getLogger(DeltaReconciler.class);

    private final TreeCache cache;

    public DeltaReconciler(TreeCache cache) {
        this.cache = cache;
    }

    public void reconcileRow(StructuralRow row, DeltaCounters counters) {
        Optional<CachedNode> existing = cache.getById(row.itemTreeId());
        if (existing.isEmpty()) {
            cache.applyCreate(toCachedNode(row));
            counters.incrementCreated();
            return;
        }
        CachedNode node = existing.get();
        boolean parentChanged = !Objects.equals(node.parentId(), row.parentId());
        boolean nameChanged   = !Objects.equals(node.name(), row.name());

        if (parentChanged) {
            cache.applyMove(row.itemTreeId(), row.parentId(), row.lastUpdate(), row.lastUpdateUser());
            counters.incrementMoved();
        }
        if (nameChanged) {
            cache.applyRename(row.itemTreeId(), row.name(), row.lastUpdate(), row.lastUpdateUser());
            counters.incrementRenamed();
        }
        if (!parentChanged && !nameChanged) {
            boolean metaChanged = !Objects.equals(node.lastUpdate(), row.lastUpdate())
                    || !Objects.equals(node.lastUpdateUser(), row.lastUpdateUser());
            if (metaChanged) {
                cache.applyMetadataUpdate(row.itemTreeId(), row.lastUpdate(), row.lastUpdateUser());
                counters.incrementMeta();
            } else {
                log.trace("Delta no-op for id {}", row.itemTreeId());
            }
        }
    }

    private static CachedNode toCachedNode(StructuralRow row) {
        return new CachedNode(
                row.itemTreeId(),
                row.parentId(),
                row.name(),
                row.type(),
                row.lastUpdate(),
                row.lastUpdateUser());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests DeltaReconcilerTest`
Expected: PASS — six tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/refresh/DeltaReconciler.java \
        src/test/java/com/myxcomp/ice/xtree/refresh/DeltaReconcilerTest.java
git commit -m "feat(refresh): add DeltaReconciler dispatching to apply* per row diff"
```

---

## Task 9: `SnapshotDiff` — full-reload drift summary

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/refresh/SnapshotDiff.java`
- Test: `src/test/java/com/myxcomp/ice/xtree/refresh/SnapshotDiffTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/com/myxcomp/ice/xtree/refresh/SnapshotDiffTest.java`:

```java
package com.myxcomp.ice.xtree.refresh;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.TreeSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotDiffTest {

    private static final Instant T1 = Instant.parse("2026-05-01T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-05-02T10:00:00Z");

    private static TreeSnapshot snapshot(Map<Long, CachedNode> byId) {
        return new TreeSnapshot(byId, Map.of(), Map.of());
    }

    private static CachedNode node(long id, long parent, String name, Instant lastUpdate) {
        return new CachedNode(id, parent, name, "Folder", lastUpdate, "sys");
    }

    @Test
    void emptyVsEmptyHasNoDrift() {
        DriftCounters d = SnapshotDiff.diff(snapshot(Map.of()), snapshot(Map.of()));
        assertThat(d.created()).isZero();
        assertThat(d.deleted()).isZero();
        assertThat(d.mutated()).isZero();
        assertThat(d.total()).isZero();
    }

    @Test
    void emptyOldOnlyNewYieldsCreations() {
        DriftCounters d = SnapshotDiff.diff(
                snapshot(Map.of()),
                snapshot(Map.of(1L, node(1L, 0L, "a", T1), 2L, node(2L, 0L, "b", T1))));
        assertThat(d.created()).isEqualTo(2);
        assertThat(d.deleted()).isZero();
        assertThat(d.mutated()).isZero();
    }

    @Test
    void emptyNewOnlyOldYieldsDeletions() {
        DriftCounters d = SnapshotDiff.diff(
                snapshot(Map.of(1L, node(1L, 0L, "a", T1))),
                snapshot(Map.of()));
        assertThat(d.created()).isZero();
        assertThat(d.deleted()).isOne();
        assertThat(d.mutated()).isZero();
    }

    @Test
    void identicalSnapshotsHaveNoDrift() {
        Map<Long, CachedNode> byId = Map.of(1L, node(1L, 0L, "a", T1), 2L, node(2L, 0L, "b", T1));
        DriftCounters d = SnapshotDiff.diff(snapshot(byId), snapshot(byId));
        assertThat(d.total()).isZero();
    }

    @Test
    void mixedChangesAreClassified() {
        Map<Long, CachedNode> oldById = Map.of(
                1L, node(1L, 0L, "a", T1),
                2L, node(2L, 0L, "b", T1),
                3L, node(3L, 0L, "c", T1));
        Map<Long, CachedNode> newById = Map.of(
                1L, node(1L, 0L, "a", T1),         // identical
                2L, node(2L, 0L, "b-renamed", T2), // mutated
                4L, node(4L, 0L, "d", T1));        // created

        DriftCounters d = SnapshotDiff.diff(snapshot(oldById), snapshot(newById));
        assertThat(d.created()).isOne();   // id 4
        assertThat(d.deleted()).isOne();   // id 3
        assertThat(d.mutated()).isOne();   // id 2
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests SnapshotDiffTest`
Expected: FAIL — class doesn't exist.

- [ ] **Step 3: Create `SnapshotDiff`**

`src/main/java/com/myxcomp/ice/xtree/refresh/SnapshotDiff.java`:

```java
package com.myxcomp.ice.xtree.refresh;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.TreeSnapshot;

import java.util.Map;
import java.util.Objects;

/**
 * Pure-function comparison of two {@link TreeSnapshot} instances. Used by the full-reload path
 * to compute the drift summary that surfaces gaps in the event stream (design §7).
 */
public final class SnapshotDiff {

    private SnapshotDiff() {}

    public static DriftCounters diff(TreeSnapshot oldSnap, TreeSnapshot newSnap) {
        DriftCounters d = new DriftCounters();
        Map<Long, CachedNode> oldById = oldSnap.byId();
        Map<Long, CachedNode> newById = newSnap.byId();

        for (Map.Entry<Long, CachedNode> e : newById.entrySet()) {
            CachedNode prior = oldById.get(e.getKey());
            if (prior == null) {
                d.incrementCreated();
            } else if (!Objects.equals(prior, e.getValue())) {
                d.incrementMutated();
            }
        }
        for (Long id : oldById.keySet()) {
            if (!newById.containsKey(id)) {
                d.incrementDeleted();
            }
        }
        return d;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests SnapshotDiffTest`
Expected: PASS — five tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/refresh/SnapshotDiff.java \
        src/test/java/com/myxcomp/ice/xtree/refresh/SnapshotDiffTest.java
git commit -m "feat(refresh): add SnapshotDiff for full-reload drift summary"
```

---

## Task 10: `RefreshOrchestrator` — delta + full reload bodies

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/refresh/RefreshOrchestrator.java`
- Test: `src/test/java/com/myxcomp/ice/xtree/refresh/RefreshOrchestratorTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/com/myxcomp/ice/xtree/refresh/RefreshOrchestratorTest.java`:

```java
package com.myxcomp.ice.xtree.refresh;

import com.myxcomp.ice.xtree.cache.TreeCache;
import com.myxcomp.ice.xtree.cache.TreeSnapshot;
import com.myxcomp.ice.xtree.common.TimeMapper;
import com.myxcomp.ice.xtree.config.RefreshProperties;
import com.myxcomp.ice.xtree.persistence.ItemTreeRepository;
import com.myxcomp.ice.xtree.persistence.StructuralRow;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RefreshOrchestratorTest {

    private static final Instant T0 = Instant.parse("2026-05-17T10:00:00Z");
    private static final Instant T_LATER = Instant.parse("2026-05-17T10:30:00Z");

    private ItemTreeRepository repo;
    private TreeCache cache;
    private DeltaReconciler reconciler;
    private TimeMapper timeMapper;
    private SimpleMeterRegistry meterRegistry;
    private RefreshProperties props;
    private RefreshOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        repo = mock(ItemTreeRepository.class);
        cache = mock(TreeCache.class);
        reconciler = mock(DeltaReconciler.class);
        timeMapper = mock(TimeMapper.class);
        meterRegistry = new SimpleMeterRegistry();
        props = new RefreshProperties(
                "0 */30 * * * *", 60, "0 0 2 * * MON-FRI",
                3, List.of(Duration.ZERO));
        orchestrator = new RefreshOrchestrator(
                repo, cache, reconciler, timeMapper, meterRegistry, props);
        when(timeMapper.now()).thenReturn(T0);
    }

    @Test
    void deltaQueriesSinceMinusOverlapAndAdvancesMarkerOnSuccess() {
        StructuralRow r1 = new StructuralRow(10L, 0L, "x", "Folder", T0, "sys");
        when(repo.findStructuralChangedSince(any())).thenReturn(List.of(r1));

        // First run: from epoch − 60s offset; advance to T0.
        RefreshResult first = orchestrator.runDelta();
        assertThat(first.success()).isTrue();
        assertThat(first.type()).isEqualTo(RefreshResult.Type.DELTA);
        verify(repo).findStructuralChangedSince(Instant.EPOCH.minusSeconds(60));
        verify(reconciler).reconcileRow(eq(r1), any(DeltaCounters.class));

        // Second run: now = T_LATER, since-marker should be T0 − 60s.
        when(timeMapper.now()).thenReturn(T_LATER);
        when(repo.findStructuralChangedSince(any())).thenReturn(List.of());
        orchestrator.runDelta();
        verify(repo).findStructuralChangedSince(T0.minusSeconds(60));
    }

    @Test
    void deltaFailureLeavesMarkerUnchanged() {
        when(repo.findStructuralChangedSince(any()))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        RefreshResult result = orchestrator.runDelta();

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("db down");
        assertThat(meterRegistry.counter("itemtree.cache.refresh.delta.failure").count()).isOne();

        // Re-run: since-marker still epoch (not advanced).
        reset(repo);
        when(repo.findStructuralChangedSince(any())).thenReturn(List.of());
        orchestrator.runDelta();
        verify(repo).findStructuralChangedSince(Instant.EPOCH.minusSeconds(60));
    }

    @Test
    void fullReloadBuildsSnapshotAndComputesDrift() {
        when(cache.snapshot()).thenReturn(new TreeSnapshot(Map.of(), Map.of(), Map.of()));
        doAnswer(inv -> {
            Consumer<StructuralRow> handler = inv.getArgument(0);
            handler.accept(new StructuralRow(1L, 0L, "root", "Folder", T0, "sys"));
            return null;
        }).when(repo).streamAllStructural(any());

        RefreshResult result = orchestrator.runFullReload();

        assertThat(result.success()).isTrue();
        assertThat(result.type()).isEqualTo(RefreshResult.Type.FULL);
        assertThat(result.driftCounters().created()).isOne();
        verify(cache).replaceAll(any(TreeSnapshot.class));
        assertThat(meterRegistry.counter("itemtree.cache.refresh.full.drift", "type", "created").count())
                .isOne();
    }

    @Test
    void fullReloadFailureLeavesCacheUntouched() {
        when(cache.snapshot()).thenReturn(new TreeSnapshot(Map.of(), Map.of(), Map.of()));
        doThrow(new DataAccessResourceFailureException("db down"))
                .when(repo).streamAllStructural(any());

        RefreshResult result = orchestrator.runFullReload();

        assertThat(result.success()).isFalse();
        verify(cache, never()).replaceAll(any());
        assertThat(meterRegistry.counter("itemtree.cache.refresh.full.failure").count()).isOne();
    }

    @Test
    void deltaCountersEmittedAsMicrometerTags() {
        StructuralRow r1 = new StructuralRow(10L, 0L, "x", "Folder", T0, "sys");
        when(repo.findStructuralChangedSince(any())).thenReturn(List.of(r1));
        doAnswer(inv -> {
            DeltaCounters c = inv.getArgument(1);
            c.incrementCreated();
            return null;
        }).when(reconciler).reconcileRow(eq(r1), any(DeltaCounters.class));

        orchestrator.runDelta();

        assertThat(meterRegistry.counter("itemtree.cache.refresh.delta.rows", "change", "created").count())
                .isOne();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests RefreshOrchestratorTest`
Expected: FAIL — class doesn't exist.

- [ ] **Step 3: Create `RefreshOrchestrator`**

`src/main/java/com/myxcomp/ice/xtree/refresh/RefreshOrchestrator.java`:

```java
package com.myxcomp.ice.xtree.refresh;

import com.myxcomp.ice.xtree.cache.SnapshotBuilder;
import com.myxcomp.ice.xtree.cache.TreeCache;
import com.myxcomp.ice.xtree.cache.TreeSnapshot;
import com.myxcomp.ice.xtree.common.TimeMapper;
import com.myxcomp.ice.xtree.config.RefreshProperties;
import com.myxcomp.ice.xtree.persistence.ItemTreeRepository;
import com.myxcomp.ice.xtree.persistence.StructuralRow;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bodies of delta refresh and full reload (design §7). Delta advances {@code lastRefreshInstant}
 * only on success; failures retry from the same point on the next scheduled call. Full reload
 * builds a fresh snapshot outside the cache write lock, computes a drift summary against the
 * pre-swap cache state, then atomically replaces the cache.
 */
@Component
public class RefreshOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(RefreshOrchestrator.class);

    private final ItemTreeRepository repository;
    private final TreeCache cache;
    private final DeltaReconciler reconciler;
    private final TimeMapper timeMapper;
    private final MeterRegistry meterRegistry;
    private final RefreshProperties props;

    private final AtomicReference<Instant> lastRefreshInstant = new AtomicReference<>(Instant.EPOCH);

    public RefreshOrchestrator(ItemTreeRepository repository,
                               TreeCache cache,
                               DeltaReconciler reconciler,
                               TimeMapper timeMapper,
                               MeterRegistry meterRegistry,
                               RefreshProperties props) {
        this.repository = repository;
        this.cache = cache;
        this.reconciler = reconciler;
        this.timeMapper = timeMapper;
        this.meterRegistry = meterRegistry;
        this.props = props;
    }

    public RefreshResult runDelta() {
        Timer.Sample sample = Timer.start(meterRegistry);
        long startNs = System.nanoTime();
        try {
            Instant since = lastRefreshInstant.get().minusSeconds(props.deltaOverlapSeconds());
            List<StructuralRow> rows = repository.findStructuralChangedSince(since);
            DeltaCounters counters = new DeltaCounters();
            for (StructuralRow row : rows) {
                reconciler.reconcileRow(row, counters);
            }
            lastRefreshInstant.set(timeMapper.now());
            recordDeltaCounters(counters);
            long durationMs = Duration.ofNanos(System.nanoTime() - startNs).toMillis();
            sample.stop(meterRegistry.timer("itemtree.cache.refresh.delta.duration"));
            log.info("Delta refresh ok in {}ms: {}", durationMs, counters);
            return RefreshResult.deltaSuccess(durationMs, counters);
        } catch (RuntimeException e) {
            long durationMs = Duration.ofNanos(System.nanoTime() - startNs).toMillis();
            sample.stop(meterRegistry.timer("itemtree.cache.refresh.delta.duration"));
            meterRegistry.counter("itemtree.cache.refresh.delta.failure").increment();
            log.warn("Delta refresh failed after {}ms: {}", durationMs, e.toString());
            return RefreshResult.deltaFailure(durationMs, e.toString());
        }
    }

    public RefreshResult runFullReload() {
        Timer.Sample sample = Timer.start(meterRegistry);
        long startNs = System.nanoTime();
        try {
            TreeSnapshot oldSnap = cache.snapshot();
            SnapshotBuilder builder = new SnapshotBuilder();
            repository.streamAllStructural(builder::accept);
            TreeSnapshot newSnap = builder.build();
            DriftCounters drift = SnapshotDiff.diff(oldSnap, newSnap);
            cache.replaceAll(newSnap);
            lastRefreshInstant.set(timeMapper.now());
            recordDriftCounters(drift);
            long durationMs = Duration.ofNanos(System.nanoTime() - startNs).toMillis();
            sample.stop(meterRegistry.timer("itemtree.cache.refresh.full.duration"));
            log.info("Full reload ok in {}ms: {}", durationMs, drift);
            return RefreshResult.fullSuccess(durationMs, drift);
        } catch (RuntimeException e) {
            long durationMs = Duration.ofNanos(System.nanoTime() - startNs).toMillis();
            sample.stop(meterRegistry.timer("itemtree.cache.refresh.full.duration"));
            meterRegistry.counter("itemtree.cache.refresh.full.failure").increment();
            log.warn("Full reload failed after {}ms: {}", durationMs, e.toString());
            return RefreshResult.fullFailure(durationMs, e.toString());
        }
    }

    Instant lastRefreshInstant() {
        return lastRefreshInstant.get();
    }

    private void recordDeltaCounters(DeltaCounters c) {
        if (c.created() > 0) meterRegistry.counter("itemtree.cache.refresh.delta.rows", "change", "created").increment(c.created());
        if (c.moved()   > 0) meterRegistry.counter("itemtree.cache.refresh.delta.rows", "change", "moved").increment(c.moved());
        if (c.renamed() > 0) meterRegistry.counter("itemtree.cache.refresh.delta.rows", "change", "renamed").increment(c.renamed());
        if (c.meta()    > 0) meterRegistry.counter("itemtree.cache.refresh.delta.rows", "change", "meta").increment(c.meta());
    }

    private void recordDriftCounters(DriftCounters d) {
        if (d.created() > 0) meterRegistry.counter("itemtree.cache.refresh.full.drift", "type", "created").increment(d.created());
        if (d.deleted() > 0) meterRegistry.counter("itemtree.cache.refresh.full.drift", "type", "deleted").increment(d.deleted());
        if (d.mutated() > 0) meterRegistry.counter("itemtree.cache.refresh.full.drift", "type", "mutated").increment(d.mutated());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests RefreshOrchestratorTest`
Expected: PASS — five tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/refresh/RefreshOrchestrator.java \
        src/test/java/com/myxcomp/ice/xtree/refresh/RefreshOrchestratorTest.java
git commit -m "feat(refresh): RefreshOrchestrator delta + full reload with metrics"
```

---

## Task 11: `RefreshScheduler` — cron-driven wrappers

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/refresh/RefreshScheduler.java`
- Test: `src/test/java/com/myxcomp/ice/xtree/refresh/RefreshSchedulerTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/com/myxcomp/ice/xtree/refresh/RefreshSchedulerTest.java`:

```java
package com.myxcomp.ice.xtree.refresh;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class RefreshSchedulerTest {

    @Test
    void scheduledDeltaInvokesOrchestratorDelta() {
        RefreshOrchestrator orchestrator = mock(RefreshOrchestrator.class);
        RefreshScheduler scheduler = new RefreshScheduler(orchestrator);

        scheduler.scheduledDelta();

        verify(orchestrator).runDelta();
        verify(orchestrator, never()).runFullReload();
    }

    @Test
    void scheduledFullReloadInvokesOrchestratorFull() {
        RefreshOrchestrator orchestrator = mock(RefreshOrchestrator.class);
        RefreshScheduler scheduler = new RefreshScheduler(orchestrator);

        scheduler.scheduledFullReload();

        verify(orchestrator).runFullReload();
        verify(orchestrator, never()).runDelta();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests RefreshSchedulerTest`
Expected: FAIL — class doesn't exist.

- [ ] **Step 3: Create `RefreshScheduler`**

`src/main/java/com/myxcomp/ice/xtree/refresh/RefreshScheduler.java`:

```java
package com.myxcomp.ice.xtree.refresh;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cron-driven entry points for delta refresh and full reload (design §7). Both methods are thin
 * wrappers — the actual work lives in {@link RefreshOrchestrator}, which is also reachable from
 * {@code POST /actuator/itemtree-refresh}.
 */
@Component
public class RefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(RefreshScheduler.class);

    private final RefreshOrchestrator orchestrator;

    public RefreshScheduler(RefreshOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Scheduled(cron = "${itemtree.cache.refresh.delta-cron}")
    public void scheduledDelta() {
        log.debug("scheduledDelta fired");
        orchestrator.runDelta();
    }

    @Scheduled(cron = "${itemtree.cache.refresh.full-reload-cron}")
    public void scheduledFullReload() {
        log.debug("scheduledFullReload fired");
        orchestrator.runFullReload();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests RefreshSchedulerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/refresh/RefreshScheduler.java \
        src/test/java/com/myxcomp/ice/xtree/refresh/RefreshSchedulerTest.java
git commit -m "feat(refresh): RefreshScheduler cron wrappers"
```

---

## Task 12: `RefreshActuatorEndpoint` — manual trigger

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/refresh/RefreshActuatorEndpoint.java`
- Test: `src/test/java/com/myxcomp/ice/xtree/refresh/RefreshActuatorEndpointTest.java`
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: Write the failing test**

`src/test/java/com/myxcomp/ice/xtree/refresh/RefreshActuatorEndpointTest.java`:

```java
package com.myxcomp.ice.xtree.refresh;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class RefreshActuatorEndpointTest {

    private RefreshOrchestrator orchestrator;
    private RefreshActuatorEndpoint endpoint;

    @BeforeEach
    void setUp() {
        orchestrator = mock(RefreshOrchestrator.class);
        endpoint = new RefreshActuatorEndpoint(orchestrator);
    }

    @Test
    void deltaTypeRunsDelta() {
        DeltaCounters c = new DeltaCounters();
        when(orchestrator.runDelta()).thenReturn(RefreshResult.deltaSuccess(123L, c));

        RefreshResult result = endpoint.refresh("delta");

        assertThat(result.type()).isEqualTo(RefreshResult.Type.DELTA);
        verify(orchestrator).runDelta();
        verify(orchestrator, never()).runFullReload();
    }

    @Test
    void fullTypeRunsFullReload() {
        DriftCounters c = new DriftCounters();
        when(orchestrator.runFullReload()).thenReturn(RefreshResult.fullSuccess(456L, c));

        RefreshResult result = endpoint.refresh("full");

        assertThat(result.type()).isEqualTo(RefreshResult.Type.FULL);
        verify(orchestrator).runFullReload();
    }

    @Test
    void unknownTypeThrows() {
        assertThatThrownBy(() -> endpoint.refresh("nonsense"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nonsense");
        verifyNoInteractions(orchestrator);
    }

    @Test
    void typeMatchingIsCaseInsensitive() {
        when(orchestrator.runDelta()).thenReturn(RefreshResult.deltaSuccess(1L, new DeltaCounters()));
        endpoint.refresh("DELTA");
        verify(orchestrator).runDelta();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests RefreshActuatorEndpointTest`
Expected: FAIL — class doesn't exist.

- [ ] **Step 3: Create `RefreshActuatorEndpoint`**

`src/main/java/com/myxcomp/ice/xtree/refresh/RefreshActuatorEndpoint.java`:

```java
package com.myxcomp.ice.xtree.refresh;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Manual refresh trigger (design §7 "Manual trigger").
 *
 * <p>Reached via {@code POST /actuator/itemtree-refresh/{type}} where {@code type} is
 * {@code delta} or {@code full}. Production deployments restrict the actuator port + path via
 * Spring Security; in Phase A it is exposed in the dev profile only.
 */
@Component
@Endpoint(id = "itemtree-refresh")
public class RefreshActuatorEndpoint {

    private final RefreshOrchestrator orchestrator;

    public RefreshActuatorEndpoint(RefreshOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @WriteOperation
    public RefreshResult refresh(@Selector String type) {
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "delta" -> orchestrator.runDelta();
            case "full"  -> orchestrator.runFullReload();
            default      -> throw new IllegalArgumentException(
                    "Unknown refresh type: " + type + " (expected 'delta' or 'full')");
        };
    }
}
```

- [ ] **Step 4: Expose the endpoint via configuration**

In `src/main/resources/application.yml`, modify the existing line:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
```

to:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,itemtree-refresh
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests RefreshActuatorEndpointTest`
Expected: PASS — four tests green.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/refresh/RefreshActuatorEndpoint.java \
        src/test/java/com/myxcomp/ice/xtree/refresh/RefreshActuatorEndpointTest.java \
        src/main/resources/application.yml
git commit -m "feat(refresh): RefreshActuatorEndpoint exposing delta/full triggers"
```

---

## Task 13: Bootstrap startup integration test

**Why:** Unit tests prove each piece works in isolation; this integration test proves the whole wiring fires correctly at startup against the dev profile (H2 + seed data). It is also the canary for any future bean-wiring regression.

**Files:**
- Create: `src/test/java/com/myxcomp/ice/xtree/bootstrap/BootstrapStartupIT.java`

- [ ] **Step 1: Write the failing test (only fails if the bootstrap is broken)**

`src/test/java/com/myxcomp/ice/xtree/bootstrap/BootstrapStartupIT.java`:

```java
package com.myxcomp.ice.xtree.bootstrap;

import com.myxcomp.ice.xtree.cache.CacheReadinessGate;
import com.myxcomp.ice.xtree.cache.TreeCache;
import com.myxcomp.ice.xtree.persistence.ItemTreeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dev")
class BootstrapStartupIT {

    @Autowired
    private TreeCache cache;

    @Autowired
    private CacheReadinessGate gate;

    @Autowired
    private ApplicationAvailability availability;

    @Autowired
    private ItemTreeRepository repository;

    @Test
    void cacheIsPopulatedAndGateIsReadyAfterStartup() {
        assertThat(cache.size()).isGreaterThan(0);
        assertThat(gate.isReady()).isTrue();
    }

    @Test
    void applicationReadinessStateIsAcceptingTraffic() {
        assertThat(availability.getReadinessState()).isEqualTo(ReadinessState.ACCEPTING_TRAFFIC);
    }

    @Test
    void lastUpdateIndexIsPresentInSeedSchema() {
        assertThat(repository.lastUpdateIndexExists()).isTrue();
    }
}
```

- [ ] **Step 2: Run the test**

Run: `./gradlew test --tests BootstrapStartupIT`
Expected: PASS — the bootstrap ran during context start, cache is loaded, gate is ready.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/myxcomp/ice/xtree/bootstrap/BootstrapStartupIT.java
git commit -m "test(bootstrap): integration test for startup wiring against H2"
```

---

## Task 14: Update package-info notes and `IMPLEMENTATION_NOTES.md`

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/bootstrap/package-info.java`
- Modify: `src/main/java/com/myxcomp/ice/xtree/refresh/package-info.java`
- Modify: `IMPLEMENTATION_NOTES.md`

- [ ] **Step 1: Update `bootstrap/package-info.java`**

Replace the existing body of `src/main/java/com/myxcomp/ice/xtree/bootstrap/package-info.java` with:

```java
/**
 * Startup wiring. Holds {@link com.myxcomp.ice.xtree.bootstrap.TreeCacheBootstrap}
 * ({@code @Order(1)} ApplicationRunner — loads cache, flips readiness) and the {@code Sleeper}
 * test seam used by its retry/backoff loop. The Phase 10 {@code MessagingStarter}
 * ({@code @Order(2)}) will live alongside these.
 */
package com.myxcomp.ice.xtree.bootstrap;
```

- [ ] **Step 2: Update `refresh/package-info.java`**

Replace the existing body with:

```java
/**
 * Scheduled delta refresh and full reload (design §7). Contains:
 * <ul>
 *   <li>{@link com.myxcomp.ice.xtree.refresh.RefreshOrchestrator} — body of delta + full reload.</li>
 *   <li>{@link com.myxcomp.ice.xtree.refresh.RefreshScheduler} — {@code @Scheduled} cron wrappers.</li>
 *   <li>{@link com.myxcomp.ice.xtree.refresh.RefreshActuatorEndpoint} — manual trigger via Actuator.</li>
 *   <li>{@link com.myxcomp.ice.xtree.refresh.DeltaReconciler} — per-row diff → {@code apply*}.</li>
 *   <li>{@link com.myxcomp.ice.xtree.refresh.SnapshotDiff} — full-reload drift summary.</li>
 *   <li>Plain holder types ({@code DeltaCounters}, {@code DriftCounters}, {@code RefreshResult}).</li>
 * </ul>
 */
package com.myxcomp.ice.xtree.refresh;
```

- [ ] **Step 3: Mark Phase 9 complete in `IMPLEMENTATION_NOTES.md`**

In `IMPLEMENTATION_NOTES.md`, replace the `## Phase 9 — Bootstrap & refresh ⬅ NEXT` heading line with:

```
## Phase 9 — Bootstrap & refresh ✅ COMPLETE (2026-05-17)
```

Then append a short summary block immediately after the existing bullet list of Phase 9 items (before the `## Phase 10 — Messaging` heading). Use this template — fill in any deviations actually encountered while executing the plan:

```markdown
**Deviations from plan:** _(record any deviations here, or write "none")._

**Actual done state:** `./gradlew clean build` → BUILD SUCCESSFUL; <N> tests green. Application starts; bootstrap loads <K> rows from H2; `CacheReadinessGate.isReady()` flips to `true`; Spring `ReadinessState` reaches `ACCEPTING_TRAFFIC`. `POST /actuator/itemtree-refresh/delta` and `.../full` both invoke the orchestrator and return 200 with a JSON `RefreshResult` body.
```

Also flip the ⬅ NEXT marker onto Phase 10 by adding it to the Phase 10 heading line:

```
## Phase 10 — Messaging ⬅ NEXT — implementable in Phase A via stubs
```

- [ ] **Step 4: Final full-build verification**

Run: `./gradlew clean build`
Expected: BUILD SUCCESSFUL. Note the final test count for the IMPLEMENTATION_NOTES summary above.

- [ ] **Step 5: Commit**

```bash
git add IMPLEMENTATION_NOTES.md \
        src/main/java/com/myxcomp/ice/xtree/bootstrap/package-info.java \
        src/main/java/com/myxcomp/ice/xtree/refresh/package-info.java
git commit -m "docs: mark Phase 9 complete and update package-info"
```

- [ ] **Step 6: Tag and surface for review**

Optionally tag the commit so the phase boundary is greppable in git history (the convention used by prior phases):

```bash
git tag phase-9-bootstrap-refresh
```

Then report the completion summary back to the user — final test count, any deviations, and whether the manual trigger was smoke-checked.

---

## Quality-review prompts for the executing agent

After every task ships:
- Did you actually run the test command and see PASS, or are you inferring success from "the implementation looks right"? **Verification before completion**: never claim PASS without seeing it.
- Are AssertJ assertions semantic? `isOne()`, `isZero()`, `isTrue()`, `isEmpty()` — not `isEqualTo(...)` for those cases.
- Any string literal repeated 3+ times in the same class? Extract to a `private static final String`.
- Any two tests that exercise the same logic with different inputs? Collapse via `@ParameterizedTest` + `@MethodSource`.
- Are there any `Instant.now()`, `LocalDateTime.now()`, etc. outside `TimeMapper`? If yes, route them through `timeMapper.now()`.
- Did you accidentally import from `com.myxcomp.ice.xtree.generated.*` outside `api/mapper/`? Move the dependency.
- Does any new file have multi-paragraph commentary or speculative TODOs? Trim to one line where possible.

---

## Cross-task checks

- **Type consistency:** `RefreshResult.Type` enum values `DELTA` / `FULL` are referenced from the orchestrator, the endpoint, and the test for both. If you renamed one, update all three.
- **Method names:** `runDelta()` and `runFullReload()` are referenced from `RefreshScheduler`, `RefreshActuatorEndpoint`, `RefreshOrchestratorTest`, `RefreshSchedulerTest`, and `RefreshActuatorEndpointTest`. Keep them in lockstep.
- **Property keys:** `delta-cron`, `delta-overlap-seconds`, `full-reload-cron`, `bootstrap-retries`, `bootstrap-backoff` must match exactly between `RefreshProperties` (kebab-cased after `@ConfigurationProperties`'s relaxed binding), `application.yml`, and the `@Scheduled(cron = "${...}")` SpEL references in `RefreshScheduler`.
- **Metric names:** the orchestrator emits `itemtree.cache.refresh.delta.duration`, `itemtree.cache.refresh.delta.rows{change=...}`, `itemtree.cache.refresh.delta.failure`, `itemtree.cache.refresh.full.duration`, `itemtree.cache.refresh.full.drift{type=...}`, `itemtree.cache.refresh.full.failure`. The bootstrap emits `itemtree.cache.bootstrap.duration`, `itemtree.cache.bootstrap.attempts`, `itemtree.cache.bootstrap.rows`. Tests reference these strings literally — update both sides together if renaming.

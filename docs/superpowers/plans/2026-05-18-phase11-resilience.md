# Phase 11 — Resilience Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire the broker reconnect-reconciliation pipeline. When the messaging library's `ConnectionExceptionListener` notifies us of an outage and subsequent recovery, classify the outage by duration and trigger the matching `RefreshOrchestrator` action: nothing (<short-threshold), delta refresh (<long-threshold), or full reload (≥long-threshold). Expose connection state via Micrometer gauges and counters per design §18, and flip a Spring Boot `HealthIndicator` to DOWN after the configurable health threshold. Everything is production-shape — Phase 11 introduces no Phase-B-only code; the only `@Profile("dev")` change is extending the existing `StubConnectionExceptionListener` to drive these new components in tests.

**Architecture:**
- **`ConnectionStateTracker`** (production, `@Component`, no profile) implements `ConnectionRecoveryListener`. Holds the only mutable state in the resilience subsystem: `disconnectedAt` (nullable), `lastConnectedAt` (nullable), `lastEventReceivedAt` (nullable), and a `connected` flag. All fields `volatile`; mutations happen only on the listener-callback thread (`StubConnectionExceptionListener.simulate*` in Phase A; the JMS library's exception-listener thread in Phase B).
- **`RecoveryListenerHook`** is a new interface in `messaging/` exposing one method, `addRecoveryListener(ConnectionRecoveryListener)`. The Phase-A `StubConnectionExceptionListener` already has that signature — we just declare it as the contract so `ConnectionStateTracker` can depend on the interface without importing a dev-only class. Phase B will provide a tiny adapter bean that delegates to the company `com.barcap.ice.service.jms.ConnectionExceptionListener`.
- `ConnectionStateTracker` registers itself with the injected `RecoveryListenerHook` in `@PostConstruct` (matches design §6 + IMPLEMENTATION_NOTES Phase 11 wording).
- **First-connect special case:** the very first `onConnectionRecovered` after startup has no prior `onConnectionLost` (the tracker's `disconnectedAt` is `null`). The tracker treats it as "first connect" — sets `lastConnectedAt`, marks `connected=true`, and **does not** invoke `ReconnectReconciler`. The cache is already fresh from `TreeCacheBootstrap`. Subsequent recoveries (those preceded by a `disconnectedAt`) drive reconciliation.
- **`ReconnectReconciler`** (production, `@Component`, no profile) is the single-method strategy class: `reconcile(Duration outage)`. Threshold matrix (defaults from design §17):
  - `outage < itemtree.solace.reconnect.short-threshold` (PT1M) → no-op
  - `outage < itemtree.solace.reconnect.long-threshold` (PT1H) → submit `runDelta` via `TaskScheduler`
  - `outage ≥ long-threshold` → submit `runFullReload` via `TaskScheduler`
  - The submitted work goes onto the auto-configured `taskScheduler` bean (pool size 1, shared with `RefreshScheduler`). This guarantees the reconcile is queued behind any in-flight scheduled refresh — never concurrent. Counters: `itemtree.solace.reconnect_reconcile{type=delta|full}` incremented at *submission* time (the design treats submission as the event; failure of the submitted work is counted by `RefreshOrchestrator` via its existing `delta.failure` / `full.failure` counters).
- **`MessagingHealthIndicator`** (production, `@Component`, no profile) — Spring Boot `HealthIndicator`. UP when `connected==true` OR `disconnectedAt` is `null` (i.e. application started but no recovery callback yet — treat as UNKNOWN-but-UP per design §6 "Solace down at startup … T4 fires; instance serves reads"). DOWN when `disconnectedAt != null` AND outage ≥ `itemtree.solace.health.mark-down-after` (PT4H default). Includes diagnostic detail map: `connected`, `outageSeconds`, `lastEventAgeSeconds`. Bean name `messagingHealthIndicator` → `/actuator/health/messaging` key.
- **Metrics** wired exactly per design §18 "Messaging" section. The tracker owns gauges (`itemtree.solace.connected`, `itemtree.solace.outage_seconds`, `itemtree.solace.last_event_age_seconds`) and lifetime counters (`itemtree.solace.connection_lost_total`, `itemtree.solace.connection_recovered_total`). The reconciler owns the reconcile-type counter. Existing `EventConsumerService` calls a new `tracker.recordEventReceived()` after a successful deserialise (before self-echo check, so the gauge reflects bus liveness, not peer activity).
- **No async/threading inside the tracker.** Listener callbacks run on whatever thread the library uses; tracker state writes are `volatile`-only, no synchronization. `ReconnectReconciler` is the one place that hops threads (submits to `TaskScheduler` via `schedule(Runnable, Instant)` — using `timeMapper.now()` as the trigger to dispatch immediately on the scheduler thread).

**Tech Stack:** Java 21, Spring Boot (`@Component`, `@PostConstruct`, `@ConfigurationProperties`, `HealthIndicator`, `TaskScheduler`), Micrometer (`Gauge.builder`, counters), JUnit 5, Mockito, AssertJ. No new third-party dependencies.

---

## Self-review note for the executing engineer

**Before any task:** re-read design §6 ("Distribution (Solace)" — especially "Reconnect reconciliation" and "Failure handling") and §18 ("Observability → Messaging"). Every metric name in this plan must match design §18 exactly. The tracker uses `TimeMapper.now()` — never `Instant.now()` directly.

**Idempotency / tolerance reminders:**
- Listener callbacks may fire in unexpected orders due to library retries. Two consecutive `onConnectionLost` calls without an intervening `onConnectionRecovered` must not lose the original `disconnectedAt` — the tracker only sets `disconnectedAt` if it is currently `null`.
- Two consecutive `onConnectionRecovered` calls (e.g. spurious extra notification) must not re-trigger reconciliation — the second call sees `disconnectedAt==null` (cleared by the first) and is a no-op for reconcile purposes (just updates `lastConnectedAt`).

**Things that will look wrong but aren't:**
- `ReconnectReconciler.reconcile(...)` returns **before** the refresh completes. The reconcile counter increments at *submission* time. Tests that wait for the refresh to actually finish are racy — assert the submit, then drive the executor synchronously via `taskScheduler.getScheduledThreadPoolExecutor()` flush in tests, or mock `TaskScheduler` and capture the `Runnable`.
- `MessagingHealthIndicator` reports **UP** when `disconnectedAt == null` even if there has never been a successful recovery. Design §6 + §7 "Critical resilience property: T4 does NOT wait for Solace" — the service is healthy enough to serve reads from cache even with the broker down.
- `ConnectionStateTracker` is `@Component` with **no profile annotation**. It is produced in both `dev` (Phase A, registers with the stub) and future `prod` (Phase B, registers with the adapter that wraps the company listener). The `RecoveryListenerHook` injection point is what makes this work across profiles.
- Gauges are registered exactly once, in `@PostConstruct`. They read live state from `volatile` fields. No lock needed — staleness of one second on a gauge that ticks every scrape is well within acceptable bounds.
- The reconciler increments `itemtree.solace.reconnect_reconcile{type=…}` **even when the submitted refresh later fails**. The metric measures *reconcile decisions*, not refresh outcomes. Refresh outcomes are already counted by `RefreshOrchestrator`'s existing failure counters.

---

## File Structure

### New production files

| Path | Responsibility |
|---|---|
| `src/main/java/com/myxcomp/ice/xtree/messaging/RecoveryListenerHook.java` | Single-method interface `void addRecoveryListener(ConnectionRecoveryListener)`. Decouples `ConnectionStateTracker` from `StubConnectionExceptionListener` so the tracker stays profile-agnostic. Phase B will provide a `@Profile("prod")` adapter implementation wrapping the company `ConnectionExceptionListener`. |
| `src/main/java/com/myxcomp/ice/xtree/messaging/ConnectionStateTracker.java` | `@Component`. Implements `ConnectionRecoveryListener`. Holds connection state (`disconnectedAt`, `lastConnectedAt`, `lastEventReceivedAt`, `connected`). Registers itself with the `RecoveryListenerHook` in `@PostConstruct`. Owns the four §18 lifecycle metrics (`connected` gauge, `outage_seconds` gauge, `last_event_age_seconds` gauge, `connection_lost_total` / `connection_recovered_total` counters). Delegates outage classification to `ReconnectReconciler` on non-first reconnects. |
| `src/main/java/com/myxcomp/ice/xtree/messaging/ReconnectReconciler.java` | `@Component`. Single public method `reconcile(Duration outage)`. Applies the threshold matrix from design §17 and submits the chosen refresh to the auto-configured `TaskScheduler`. Increments `itemtree.solace.reconnect_reconcile{type=delta\|full}` at submission. |
| `src/main/java/com/myxcomp/ice/xtree/messaging/MessagingHealthIndicator.java` | `@Component` implementing `org.springframework.boot.actuate.health.HealthIndicator`. Reports DOWN when outage ≥ `itemtree.solace.health.mark-down-after`; UP otherwise. Includes a small `Details` map with the current state. |

### Modified production files

| Path | Change |
|---|---|
| `src/main/java/com/myxcomp/ice/xtree/config/SolaceProperties.java` | Add three new bound fields: nested `Reconnect(Duration shortThreshold, Duration longThreshold)` and nested `Health(Duration markDownAfter)`. All `@NotNull`. Defaults supplied by `application.yml`, not the record (consistent with how `topic` works today). |
| `src/main/java/com/myxcomp/ice/xtree/messaging/dev/StubConnectionExceptionListener.java` | Declare `implements RecoveryListenerHook`. No behavioural change — its existing `addRecoveryListener` matches the interface signature exactly. |
| `src/main/java/com/myxcomp/ice/xtree/messaging/EventConsumerService.java` | Inject `ConnectionStateTracker`. After a successful `objectMapper.readValue(...)` (and before the self-echo check), call `tracker.recordEventReceived()`. |
| `src/main/resources/application.yml` | Under `itemtree.solace`, add `reconnect.short-threshold: PT1M`, `reconnect.long-threshold: PT1H`, `health.mark-down-after: PT4H`. |
| `IMPLEMENTATION_NOTES.md` | Mark Phase 11 ✅ COMPLETE; move ⬅ NEXT marker to Phase 12; record any deviations. |

### New test files

| Path | Coverage |
|---|---|
| `src/test/java/com/myxcomp/ice/xtree/config/SolacePropertiesTest.java` | Binding test (`@SpringBootTest` or `ApplicationContextRunner`) — yaml under `itemtree.solace` binds into the record, with the new `reconnect` and `health` nested groups populated from `application.yml` defaults. |
| `src/test/java/com/myxcomp/ice/xtree/messaging/ConnectionStateTrackerTest.java` | Unit. `@PostConstruct` registers tracker via `RecoveryListenerHook`. First `onConnectionRecovered` after startup (no prior lost) is the first-connect path: `lastConnectedAt` set, `connected=true`, **no reconcile call**. `onConnectionLost` sets `disconnectedAt`, marks `connected=false`, increments `connection_lost_total`. Second `onConnectionLost` while already disconnected leaves `disconnectedAt` unchanged. `onConnectionRecovered` after a prior lost: computes outage from `disconnectedAt` to `timeMapper.now()`, hands to `ReconnectReconciler`, clears `disconnectedAt`, sets `connected=true`, increments `connection_recovered_total`. Spurious second `onConnectionRecovered` does not call reconciler (disconnectedAt already null). `recordEventReceived()` updates `lastEventReceivedAt`. Gauges: `connected` returns 1 when connected else 0; `outage_seconds` returns 0 when connected else `now - disconnectedAt` in seconds; `last_event_age_seconds` returns 0 when no event yet else `now - lastEventReceivedAt` in seconds. Null-guard on `recordEventReceived`'s contract is via `timeMapper.now()` (TimeMapper never returns null). |
| `src/test/java/com/myxcomp/ice/xtree/messaging/ReconnectReconcilerTest.java` | Unit. Parameterised threshold matrix: 30s (no-op + zero counter increments), exactly short-threshold (delta), 10min (delta), exactly long-threshold (full), 2h (full). Submission path: a captured `Runnable` runs `RefreshOrchestrator.runDelta()` or `runFullReload()`. Counter increments at submit time (not after task execution). |
| `src/test/java/com/myxcomp/ice/xtree/messaging/MessagingHealthIndicatorTest.java` | Unit. UP states: never connected (`disconnectedAt==null`), currently connected (`connected==true`). DOWN state: outage ≥ `markDownAfter`. UP-with-warning state: outage > 0 but < `markDownAfter` (still UP — design only flips DOWN past the threshold). Details map asserts shape: keys `connected`, `outageSeconds`, `lastEventAgeSeconds`. |
| `src/test/java/com/myxcomp/ice/xtree/messaging/EventConsumerServiceTest.java` | Existing file — extend with one test: after a successful payload deserialise, `tracker.recordEventReceived()` is called exactly once. Verify the call happens even for self-echo messages (we still saw a message on the bus). |
| `src/test/java/com/myxcomp/ice/xtree/messaging/MessagingResilienceIT.java` | `@SpringBootTest(webEnvironment = NONE) @ActiveProfiles("dev")`. After context start: assert `ConnectionStateTracker.isConnected()==false` and `disconnectedAt==null` (never-connected). Drive `stubListener.simulateRecovery()` → first-connect: `isConnected()==true`, **no** refresh on the orchestrator (verified by spying or by counter). Drive `simulateDisconnect()` → `isConnected()==false`, `connection_lost_total==1`. Wait two synthetic clock ticks (Mockito-mock `TimeMapper`), then `simulateRecovery()` after 30s outage → no reconcile; after 10min outage → delta reconcile (`reconnect_reconcile{type=delta}==1`); after 2h outage → full reconcile (`reconnect_reconcile{type=full}==1`). Each scenario drives a fresh context or resets state. |

---

## Conventions used by the rest of this plan

- **Logger:** `private static final Logger log = LoggerFactory.getLogger(<owning class>.class);` — SLF4J, no Lombok `@Slf4j`.
- **`Objects.requireNonNull`** guards on every public method parameter that is not nullable per the design.
- **AssertJ semantic methods:** `isZero()`, `isOne()`, `isEmpty()`, `isTrue()`, `isFalse()`, `isNull()`, `isNotNull()`, `containsExactly(...)`.
- **Time:** never `Instant.now()`. Always `timeMapper.now()`. Mock `TimeMapper` in tests, return a fixed `Instant` per scenario.
- **Metric names:** identical to design §18 ("Messaging"). Tags use exact literal `type=delta|full`.
- **`@ParameterizedTest`** for op-by-op or threshold-by-threshold variants — one parameterised test per behaviour, not N copy-pasted tests.
- **Imports:** static `org.assertj.core.api.Assertions.assertThat`; static `org.mockito.Mockito.*` / `BDDMockito.*`.
- **No `mockStatic`, no PowerMock, no `@Disabled` without a `// TODO`.**
- **`@PostConstruct`** uses `jakarta.annotation.PostConstruct` (Spring 6 / Boot 3).

---

## Tasks

### Task 1: Extend `SolaceProperties` with reconnect + health thresholds

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/config/SolaceProperties.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/java/com/myxcomp/ice/xtree/config/SolacePropertiesTest.java` (create if missing)

- [ ] **Step 1: Write the failing test**

Create or open `src/test/java/com/myxcomp/ice/xtree/config/SolacePropertiesTest.java` and write:

```java
package com.myxcomp.ice.xtree.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SolacePropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(EnableConfig.class);

    @org.springframework.boot.context.properties.EnableConfigurationProperties(SolaceProperties.class)
    static class EnableConfig {}

    @Test
    void bindsTopicReconnectAndHealthFromYaml() {
        contextRunner
                .withPropertyValues(
                        "itemtree.solace.topic=BC/ICE/ITEMTREE",
                        "itemtree.solace.reconnect.short-threshold=PT1M",
                        "itemtree.solace.reconnect.long-threshold=PT1H",
                        "itemtree.solace.health.mark-down-after=PT4H")
                .run(ctx -> {
                    SolaceProperties props = ctx.getBean(SolaceProperties.class);
                    assertThat(props.topic()).isEqualTo("BC/ICE/ITEMTREE");
                    assertThat(props.reconnect().shortThreshold()).isEqualTo(Duration.ofMinutes(1));
                    assertThat(props.reconnect().longThreshold()).isEqualTo(Duration.ofHours(1));
                    assertThat(props.health().markDownAfter()).isEqualTo(Duration.ofHours(4));
                });
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests SolacePropertiesTest`
Expected: FAIL — `Reconnect` / `Health` types don't exist on `SolaceProperties` yet.

- [ ] **Step 3: Extend `SolaceProperties`**

Replace `src/main/java/com/myxcomp/ice/xtree/config/SolaceProperties.java` with:

```java
package com.myxcomp.ice.xtree.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Solace JMS knobs (design §17 → {@code itemtree.solace.*}).
 *
 * @param topic     JMS topic name. Drives both publisher destination and consumer subscription.
 * @param reconnect Thresholds that classify a broker outage (Phase 11).
 * @param health    Threshold beyond which {@code MessagingHealthIndicator} flips DOWN.
 */
@Validated
@ConfigurationProperties("itemtree.solace")
public record SolaceProperties(
        @NotBlank String topic,
        @NotNull @Valid Reconnect reconnect,
        @NotNull @Valid Health health
) {

    /**
     * @param shortThreshold Outage below this duration is a no-op (scheduled delta covers it).
     * @param longThreshold  Outage at or above this duration triggers a full reload on reconnect;
     *                       outage below it (but at or above {@code shortThreshold}) triggers a delta.
     */
    public record Reconnect(
            @NotNull Duration shortThreshold,
            @NotNull Duration longThreshold
    ) {}

    /**
     * @param markDownAfter Outage at or above this duration flips the messaging health indicator DOWN.
     */
    public record Health(
            @NotNull Duration markDownAfter
    ) {}
}
```

- [ ] **Step 4: Add the new keys to `application.yml`**

In `src/main/resources/application.yml`, replace:

```yaml
  solace:
    topic: "BC/ICE/ITEMTREE"
```

with:

```yaml
  solace:
    topic: "BC/ICE/ITEMTREE"
    reconnect:
      short-threshold: PT1M
      long-threshold:  PT1H
    health:
      mark-down-after: PT4H
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests SolacePropertiesTest`
Expected: PASS.

- [ ] **Step 6: Run the full test suite**

Run: `./gradlew test`
Expected: PASS (all 451 prior tests plus the new binding test). The `application.yml` change supplies a non-null `reconnect` and `health` to `SolaceProperties` so any prior test that loads the full context still binds successfully.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/config/SolaceProperties.java \
        src/main/resources/application.yml \
        src/test/java/com/myxcomp/ice/xtree/config/SolacePropertiesTest.java
git commit -m "feat(phase11): extend SolaceProperties with reconnect + health thresholds"
```

---

### Task 2: Introduce `RecoveryListenerHook` interface and have the stub implement it

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/messaging/RecoveryListenerHook.java`
- Modify: `src/main/java/com/myxcomp/ice/xtree/messaging/dev/StubConnectionExceptionListener.java`

- [ ] **Step 1: Write the failing test**

Append the following test to `src/test/java/com/myxcomp/ice/xtree/messaging/dev/StubConnectionExceptionListenerTest.java`:

```java
@org.junit.jupiter.api.Test
void implementsRecoveryListenerHook() {
    StubConnectionExceptionListener listener = new StubConnectionExceptionListener();
    org.assertj.core.api.Assertions.assertThat(listener)
            .isInstanceOf(com.myxcomp.ice.xtree.messaging.RecoveryListenerHook.class);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests StubConnectionExceptionListenerTest`
Expected: COMPILATION FAIL — `RecoveryListenerHook` does not exist.

- [ ] **Step 3: Create the interface**

Create `src/main/java/com/myxcomp/ice/xtree/messaging/RecoveryListenerHook.java`:

```java
package com.myxcomp.ice.xtree.messaging;

/**
 * Registration site for {@link ConnectionRecoveryListener}s.
 *
 * <p>Phase A: implemented by {@code StubConnectionExceptionListener}.
 * Phase B: implemented by a thin adapter bean (in {@code @Profile("prod")} config)
 * that delegates to the company {@code com.barcap.ice.service.jms.ConnectionExceptionListener}.
 *
 * <p>Decouples {@code ConnectionStateTracker} from the concrete listener type so the
 * tracker remains profile-agnostic.
 */
public interface RecoveryListenerHook {

    void addRecoveryListener(ConnectionRecoveryListener listener);
}
```

- [ ] **Step 4: Declare the stub as implementing the interface**

Edit `src/main/java/com/myxcomp/ice/xtree/messaging/dev/StubConnectionExceptionListener.java`:

Change the class declaration line from:

```java
public class StubConnectionExceptionListener {
```

to:

```java
public class StubConnectionExceptionListener implements com.myxcomp.ice.xtree.messaging.RecoveryListenerHook {
```

(Add a static import or fully qualified reference — the FQN keeps the import block tidy and the change one-line.)

The `addRecoveryListener` method body already matches the interface; no further change needed. Add `@Override` above the existing method signature.

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests StubConnectionExceptionListenerTest`
Expected: PASS (all four tests in the class, including the new `implementsRecoveryListenerHook`).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/messaging/RecoveryListenerHook.java \
        src/main/java/com/myxcomp/ice/xtree/messaging/dev/StubConnectionExceptionListener.java \
        src/test/java/com/myxcomp/ice/xtree/messaging/dev/StubConnectionExceptionListenerTest.java
git commit -m "feat(phase11): RecoveryListenerHook interface; stub implements it"
```

---

### Task 3: `ConnectionStateTracker` — state fields, listener interface, gauges (no reconcile yet)

This task gets the tracker into the context with all four §18 lifecycle metrics, but does NOT yet hand outage durations to `ReconnectReconciler` (that's Task 5). This lets the tracker land in isolation and lets `EventConsumerService` start calling `recordEventReceived` in Task 6.

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/messaging/ConnectionStateTracker.java`
- Create: `src/test/java/com/myxcomp/ice/xtree/messaging/ConnectionStateTrackerTest.java`

- [ ] **Step 1: Write the failing tests (state + gauges + lifecycle)**

Create `src/test/java/com/myxcomp/ice/xtree/messaging/ConnectionStateTrackerTest.java`:

```java
package com.myxcomp.ice.xtree.messaging;

import com.myxcomp.ice.xtree.common.TimeMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConnectionStateTrackerTest {

    private static final Instant T0 = Instant.parse("2026-05-18T10:00:00Z");

    private TimeMapper timeMapper;
    private SimpleMeterRegistry meterRegistry;
    private RecoveryListenerHook hook;
    private ConnectionStateTracker tracker;

    @BeforeEach
    void setUp() {
        timeMapper = mock(TimeMapper.class);
        meterRegistry = new SimpleMeterRegistry();
        hook = mock(RecoveryListenerHook.class);
        when(timeMapper.now()).thenReturn(T0);
        tracker = new ConnectionStateTracker(hook, timeMapper, meterRegistry);
        tracker.registerWithHook();
    }

    @Test
    void registersItselfWithTheHook() {
        verify(hook).addRecoveryListener(tracker);
    }

    @Test
    void initialStateIsDisconnectedNoOutage() {
        assertThat(tracker.isConnected()).isFalse();
        assertThat(tracker.disconnectedAt()).isNull();
        assertThat(tracker.lastConnectedAt()).isNull();
        assertThat(tracker.lastEventReceivedAt()).isNull();
        assertThat(meterRegistry.get("itemtree.solace.connected").gauge().value()).isZero();
        assertThat(meterRegistry.get("itemtree.solace.outage_seconds").gauge().value()).isZero();
        assertThat(meterRegistry.get("itemtree.solace.last_event_age_seconds").gauge().value()).isZero();
    }

    @Nested
    class OnConnectionLost {

        @Test
        void setsDisconnectedAtAndIncrementsCounter() {
            tracker.onConnectionLost("itemtree");

            assertThat(tracker.disconnectedAt()).isEqualTo(T0);
            assertThat(tracker.isConnected()).isFalse();
            assertThat(meterRegistry.counter("itemtree.solace.connection_lost_total").count()).isOne();
        }

        @Test
        void secondLostWhileAlreadyDisconnectedLeavesDisconnectedAtUnchanged() {
            tracker.onConnectionLost("itemtree");
            Instant later = T0.plusSeconds(30);
            when(timeMapper.now()).thenReturn(later);

            tracker.onConnectionLost("itemtree");

            assertThat(tracker.disconnectedAt()).isEqualTo(T0);
            assertThat(meterRegistry.counter("itemtree.solace.connection_lost_total").count()).isEqualTo(2.0);
        }

        @Test
        void outageGaugeReflectsElapsedSeconds() {
            tracker.onConnectionLost("itemtree");
            when(timeMapper.now()).thenReturn(T0.plusSeconds(45));

            assertThat(meterRegistry.get("itemtree.solace.outage_seconds").gauge().value())
                    .isEqualTo(45.0);
        }
    }

    @Nested
    class OnConnectionRecovered {

        @Test
        void firstConnectSetsLastConnectedButLeavesOutageAtZero() {
            tracker.onConnectionRecovered("itemtree");

            assertThat(tracker.isConnected()).isTrue();
            assertThat(tracker.lastConnectedAt()).isEqualTo(T0);
            assertThat(tracker.disconnectedAt()).isNull();
            assertThat(meterRegistry.get("itemtree.solace.connected").gauge().value()).isOne();
            assertThat(meterRegistry.counter("itemtree.solace.connection_recovered_total").count()).isOne();
        }

        @Test
        void recoveryAfterPriorLossClearsDisconnectedAt() {
            tracker.onConnectionLost("itemtree");
            Instant later = T0.plusSeconds(30);
            when(timeMapper.now()).thenReturn(later);

            tracker.onConnectionRecovered("itemtree");

            assertThat(tracker.isConnected()).isTrue();
            assertThat(tracker.disconnectedAt()).isNull();
            assertThat(tracker.lastConnectedAt()).isEqualTo(later);
            assertThat(meterRegistry.get("itemtree.solace.outage_seconds").gauge().value()).isZero();
        }

        @Test
        void spuriousSecondRecoveryDoesNothingHarmful() {
            tracker.onConnectionRecovered("itemtree");
            tracker.onConnectionRecovered("itemtree");

            assertThat(tracker.isConnected()).isTrue();
            assertThat(meterRegistry.counter("itemtree.solace.connection_recovered_total").count())
                    .isEqualTo(2.0);
        }
    }

    @Nested
    class RecordEventReceived {

        @Test
        void updatesLastEventReceivedAt() {
            tracker.recordEventReceived();

            assertThat(tracker.lastEventReceivedAt()).isEqualTo(T0);
        }

        @Test
        void lastEventAgeGaugeReflectsElapsedSeconds() {
            tracker.recordEventReceived();
            when(timeMapper.now()).thenReturn(T0.plusSeconds(7));

            assertThat(meterRegistry.get("itemtree.solace.last_event_age_seconds").gauge().value())
                    .isEqualTo(7.0);
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail (and don't compile)**

Run: `./gradlew test --tests ConnectionStateTrackerTest`
Expected: COMPILATION FAIL — class does not exist.

- [ ] **Step 3: Write the minimal `ConnectionStateTracker`**

Create `src/main/java/com/myxcomp/ice/xtree/messaging/ConnectionStateTracker.java`:

```java
package com.myxcomp.ice.xtree.messaging;

import com.myxcomp.ice.xtree.common.TimeMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Tracks broker connection state for the resilience subsystem (design §6 "Reconnect reconciliation",
 * §18 "Messaging metrics").
 *
 * <p>Receives lifecycle callbacks from the messaging library (Phase A: {@code StubConnectionExceptionListener};
 * Phase B: company {@code ConnectionExceptionListener} via a {@link RecoveryListenerHook} adapter). Owns the
 * four §18 connection-lifecycle metrics. Tasks beyond this skeleton (Phase 11):
 * <ul>
 *   <li>Hand non-first-connect recoveries to {@code ReconnectReconciler} (Task 5).</li>
 *   <li>Be informed of inbound events via {@link #recordEventReceived()} so {@code last_event_age_seconds} is meaningful.</li>
 * </ul>
 *
 * <p>State fields are {@code volatile}. Listener callbacks may run on the library's exception-listener
 * thread; reads happen on the Micrometer scrape thread and any test thread. No locks needed — single-writer
 * invariant is enforced by the library (Phase B) and by single-threaded test driving (Phase A).
 */
@Component
public class ConnectionStateTracker implements ConnectionRecoveryListener {

    private static final Logger log = LoggerFactory.getLogger(ConnectionStateTracker.class);

    private final RecoveryListenerHook hook;
    private final TimeMapper timeMapper;
    private final MeterRegistry meterRegistry;

    private volatile Instant disconnectedAt;
    private volatile Instant lastConnectedAt;
    private volatile Instant lastEventReceivedAt;
    private volatile boolean connected;

    private Counter connectionLostCounter;
    private Counter connectionRecoveredCounter;

    public ConnectionStateTracker(RecoveryListenerHook hook,
                                  TimeMapper timeMapper,
                                  MeterRegistry meterRegistry) {
        this.hook = Objects.requireNonNull(hook, "hook");
        this.timeMapper = Objects.requireNonNull(timeMapper, "timeMapper");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
    }

    @PostConstruct
    public void registerWithHook() {
        Gauge.builder("itemtree.solace.connected", this, t -> t.connected ? 1.0 : 0.0)
                .register(meterRegistry);
        Gauge.builder("itemtree.solace.outage_seconds", this, ConnectionStateTracker::outageSeconds)
                .register(meterRegistry);
        Gauge.builder("itemtree.solace.last_event_age_seconds", this, ConnectionStateTracker::lastEventAgeSeconds)
                .register(meterRegistry);
        connectionLostCounter = meterRegistry.counter("itemtree.solace.connection_lost_total");
        connectionRecoveredCounter = meterRegistry.counter("itemtree.solace.connection_recovered_total");
        hook.addRecoveryListener(this);
        log.info("ConnectionStateTracker registered with RecoveryListenerHook");
    }

    @Override
    public void onConnectionLost(String serviceName) {
        if (disconnectedAt == null) {
            disconnectedAt = timeMapper.now();
        }
        connected = false;
        connectionLostCounter.increment();
        log.warn("Connection lost: service={} disconnectedAt={}", serviceName, disconnectedAt);
    }

    @Override
    public void onConnectionRecovered(String serviceName) {
        Instant now = timeMapper.now();
        lastConnectedAt = now;
        disconnectedAt = null;
        connected = true;
        connectionRecoveredCounter.increment();
        log.info("Connection recovered: service={} at={}", serviceName, now);
    }

    public void recordEventReceived() {
        lastEventReceivedAt = timeMapper.now();
    }

    public boolean isConnected()           { return connected; }
    public Instant disconnectedAt()        { return disconnectedAt; }
    public Instant lastConnectedAt()       { return lastConnectedAt; }
    public Instant lastEventReceivedAt()   { return lastEventReceivedAt; }

    public double outageSeconds() {
        Instant d = disconnectedAt;
        return d == null ? 0.0 : Duration.between(d, timeMapper.now()).toSeconds();
    }

    public double lastEventAgeSeconds() {
        Instant e = lastEventReceivedAt;
        return e == null ? 0.0 : Duration.between(e, timeMapper.now()).toSeconds();
    }
}
```

- [ ] **Step 4: Run the tracker tests to verify they pass**

Run: `./gradlew test --tests ConnectionStateTrackerTest`
Expected: PASS.

- [ ] **Step 5: Run the full suite**

Run: `./gradlew test`
Expected: PASS. The new `ConnectionStateTracker` `@Component` lands in the context — no other test should break because no other bean depends on it yet, and `StubConnectionExceptionListener` (already in the context) acts as the `RecoveryListenerHook`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/messaging/ConnectionStateTracker.java \
        src/test/java/com/myxcomp/ice/xtree/messaging/ConnectionStateTrackerTest.java
git commit -m "feat(phase11): ConnectionStateTracker — state, listener, lifecycle metrics"
```

---

### Task 4: `ReconnectReconciler` — threshold matrix + async submit

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/messaging/ReconnectReconciler.java`
- Create: `src/test/java/com/myxcomp/ice/xtree/messaging/ReconnectReconcilerTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/myxcomp/ice/xtree/messaging/ReconnectReconcilerTest.java`:

```java
package com.myxcomp.ice.xtree.messaging;

import com.myxcomp.ice.xtree.common.TimeMapper;
import com.myxcomp.ice.xtree.config.SolaceProperties;
import com.myxcomp.ice.xtree.refresh.RefreshOrchestrator;
import com.myxcomp.ice.xtree.refresh.RefreshResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReconnectReconcilerTest {

    private static final Instant NOW = Instant.parse("2026-05-18T10:00:00Z");

    private RefreshOrchestrator orchestrator;
    private TaskScheduler taskScheduler;
    private TimeMapper timeMapper;
    private SimpleMeterRegistry meterRegistry;
    private ReconnectReconciler reconciler;

    @BeforeEach
    void setUp() {
        orchestrator = mock(RefreshOrchestrator.class);
        taskScheduler = mock(TaskScheduler.class);
        timeMapper = mock(TimeMapper.class);
        meterRegistry = new SimpleMeterRegistry();
        when(timeMapper.now()).thenReturn(NOW);
        SolaceProperties props = new SolaceProperties(
                "BC/ICE/ITEMTREE",
                new SolaceProperties.Reconnect(Duration.ofMinutes(1), Duration.ofHours(1)),
                new SolaceProperties.Health(Duration.ofHours(4)));
        reconciler = new ReconnectReconciler(orchestrator, taskScheduler, timeMapper, meterRegistry, props);
    }

    @ParameterizedTest(name = "{0} → no-op")
    @MethodSource("shortOutages")
    void outagesBelowShortThresholdAreNoOps(Duration outage) {
        reconciler.reconcile(outage);

        verifyNoInteractions(taskScheduler, orchestrator);
        assertThat(meterRegistry.find("itemtree.solace.reconnect_reconcile").counters()).isEmpty();
    }

    static Stream<Arguments> shortOutages() {
        return Stream.of(
                Arguments.of(Duration.ZERO),
                Arguments.of(Duration.ofSeconds(30)),
                Arguments.of(Duration.ofSeconds(59)));
    }

    @ParameterizedTest(name = "{0} → delta reconcile")
    @MethodSource("mediumOutages")
    void outagesBetweenThresholdsTriggerDelta(Duration outage) {
        when(taskScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenAnswer(inv -> null);

        reconciler.reconcile(outage);

        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(taskCaptor.capture(), any(Instant.class));
        verify(orchestrator, never()).runDelta();
        verify(orchestrator, never()).runFullReload();

        taskCaptor.getValue().run();
        verify(orchestrator).runDelta();
        verify(orchestrator, never()).runFullReload();

        assertThat(meterRegistry.counter("itemtree.solace.reconnect_reconcile", "type", "delta").count())
                .isOne();
    }

    static Stream<Arguments> mediumOutages() {
        return Stream.of(
                Arguments.of(Duration.ofMinutes(1)),       // exactly short-threshold
                Arguments.of(Duration.ofMinutes(10)),
                Arguments.of(Duration.ofMinutes(59).plusSeconds(59)));
    }

    @ParameterizedTest(name = "{0} → full reload")
    @MethodSource("longOutages")
    void outagesAtOrAboveLongThresholdTriggerFullReload(Duration outage) {
        when(orchestrator.runFullReload())
                .thenReturn(RefreshResult.fullSuccess(0, new com.myxcomp.ice.xtree.refresh.DriftCounters()));

        reconciler.reconcile(outage);

        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(taskCaptor.capture(), any(Instant.class));
        taskCaptor.getValue().run();
        verify(orchestrator).runFullReload();
        verify(orchestrator, never()).runDelta();

        assertThat(meterRegistry.counter("itemtree.solace.reconnect_reconcile", "type", "full").count())
                .isOne();
    }

    static Stream<Arguments> longOutages() {
        return Stream.of(
                Arguments.of(Duration.ofHours(1)),         // exactly long-threshold
                Arguments.of(Duration.ofHours(2)),
                Arguments.of(Duration.ofHours(6)));
    }

    @Test
    void counterIncrementsAtSubmissionEvenIfSubmittedTaskLaterFails() {
        when(orchestrator.runDelta()).thenThrow(new RuntimeException("simulated"));

        reconciler.reconcile(Duration.ofMinutes(10));

        // Counter is already 1 before the task runs.
        assertThat(meterRegistry.counter("itemtree.solace.reconnect_reconcile", "type", "delta").count())
                .isOne();
    }

    @Test
    void nullOutageThrows() {
        org.assertj.core.api.Assertions
                .assertThatNullPointerException()
                .isThrownBy(() -> reconciler.reconcile(null))
                .withMessageContaining("outage");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests ReconnectReconcilerTest`
Expected: COMPILATION FAIL — `ReconnectReconciler` does not exist.

- [ ] **Step 3: Write the reconciler**

Create `src/main/java/com/myxcomp/ice/xtree/messaging/ReconnectReconciler.java`:

```java
package com.myxcomp.ice.xtree.messaging;

import com.myxcomp.ice.xtree.common.TimeMapper;
import com.myxcomp.ice.xtree.config.SolaceProperties;
import com.myxcomp.ice.xtree.refresh.RefreshOrchestrator;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;

/**
 * Classifies a broker outage by duration and submits the matching refresh to the shared
 * {@link TaskScheduler}, per design §6 "Reconnect reconciliation":
 * <pre>
 *   outage &lt; short-threshold (PT1M) → no-op (scheduled delta covers it)
 *   outage &lt; long-threshold  (PT1H) → delta refresh
 *   outage ≥ long-threshold           → full reload
 * </pre>
 *
 * <p>Submission is asynchronous via the auto-configured single-threaded {@code taskScheduler},
 * which queues the work behind any in-flight {@code @Scheduled} refresh. The
 * {@code itemtree.solace.reconnect_reconcile{type=…}} counter increments at submission time;
 * the submitted task's success / failure is counted independently by
 * {@link RefreshOrchestrator}'s existing failure counters.
 */
@Component
public class ReconnectReconciler {

    private static final Logger log = LoggerFactory.getLogger(ReconnectReconciler.class);

    private final RefreshOrchestrator orchestrator;
    private final TaskScheduler taskScheduler;
    private final TimeMapper timeMapper;
    private final MeterRegistry meterRegistry;
    private final SolaceProperties props;

    public ReconnectReconciler(RefreshOrchestrator orchestrator,
                               TaskScheduler taskScheduler,
                               TimeMapper timeMapper,
                               MeterRegistry meterRegistry,
                               SolaceProperties props) {
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
        this.taskScheduler = Objects.requireNonNull(taskScheduler, "taskScheduler");
        this.timeMapper = Objects.requireNonNull(timeMapper, "timeMapper");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        this.props = Objects.requireNonNull(props, "props");
    }

    public void reconcile(Duration outage) {
        Objects.requireNonNull(outage, "outage");
        Duration shortT = props.reconnect().shortThreshold();
        Duration longT = props.reconnect().longThreshold();

        if (outage.compareTo(shortT) < 0) {
            log.info("Reconnect outage={}s below short-threshold={}s — no reconcile",
                    outage.toSeconds(), shortT.toSeconds());
            return;
        }

        if (outage.compareTo(longT) < 0) {
            log.info("Reconnect outage={}s below long-threshold={}s — queueing delta",
                    outage.toSeconds(), longT.toSeconds());
            meterRegistry.counter("itemtree.solace.reconnect_reconcile", "type", "delta").increment();
            taskScheduler.schedule(orchestrator::runDelta, timeMapper.now());
            return;
        }

        log.info("Reconnect outage={}s at-or-above long-threshold={}s — queueing full reload",
                outage.toSeconds(), longT.toSeconds());
        meterRegistry.counter("itemtree.solace.reconnect_reconcile", "type", "full").increment();
        taskScheduler.schedule(orchestrator::runFullReload, timeMapper.now());
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests ReconnectReconcilerTest`
Expected: PASS.

- [ ] **Step 5: Run the full suite**

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/messaging/ReconnectReconciler.java \
        src/test/java/com/myxcomp/ice/xtree/messaging/ReconnectReconcilerTest.java
git commit -m "feat(phase11): ReconnectReconciler — threshold matrix + async submit"
```

---

### Task 5: Wire `ConnectionStateTracker` to `ReconnectReconciler`

This connects the two halves: on `onConnectionRecovered`, if a prior `disconnectedAt` exists, compute outage and call `reconciler.reconcile(outage)`. The first-connect case (no prior `disconnectedAt`) still skips reconciliation.

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/messaging/ConnectionStateTracker.java`
- Modify: `src/test/java/com/myxcomp/ice/xtree/messaging/ConnectionStateTrackerTest.java`

- [ ] **Step 1: Extend the test to cover reconcile invocation**

Add a new nested class to `ConnectionStateTrackerTest`:

```java
@Nested
class ReconcileWiring {

    private ReconnectReconciler reconciler;

    @BeforeEach
    void wireReconciler() {
        reconciler = mock(ReconnectReconciler.class);
        tracker = new ConnectionStateTracker(hook, timeMapper, meterRegistry, reconciler);
        tracker.registerWithHook();
    }

    @Test
    void firstConnectDoesNotReconcile() {
        tracker.onConnectionRecovered("itemtree");

        verify(reconciler, org.mockito.Mockito.never()).reconcile(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void recoveryAfterLossPassesOutageToReconciler() {
        tracker.onConnectionLost("itemtree");
        when(timeMapper.now()).thenReturn(T0.plusSeconds(45));

        tracker.onConnectionRecovered("itemtree");

        verify(reconciler).reconcile(Duration.ofSeconds(45));
    }

    @Test
    void spuriousSecondRecoveryDoesNotCallReconcileAgain() {
        tracker.onConnectionLost("itemtree");
        when(timeMapper.now()).thenReturn(T0.plusSeconds(45));
        tracker.onConnectionRecovered("itemtree");
        org.mockito.Mockito.reset(reconciler);

        tracker.onConnectionRecovered("itemtree");

        verify(reconciler, org.mockito.Mockito.never()).reconcile(org.mockito.ArgumentMatchers.any());
    }
}
```

Also update the existing `setUp()` constructor call to pass a no-op `ReconnectReconciler`:

```java
@BeforeEach
void setUp() {
    timeMapper = mock(TimeMapper.class);
    meterRegistry = new SimpleMeterRegistry();
    hook = mock(RecoveryListenerHook.class);
    when(timeMapper.now()).thenReturn(T0);
    tracker = new ConnectionStateTracker(hook, timeMapper, meterRegistry,
            mock(ReconnectReconciler.class));
    tracker.registerWithHook();
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests ConnectionStateTrackerTest`
Expected: COMPILATION FAIL — `ConnectionStateTracker` constructor does not accept a `ReconnectReconciler`.

- [ ] **Step 3: Extend `ConnectionStateTracker` to call the reconciler**

In `ConnectionStateTracker`:

Add field:

```java
    private final ReconnectReconciler reconciler;
```

Replace the constructor with:

```java
    public ConnectionStateTracker(RecoveryListenerHook hook,
                                  TimeMapper timeMapper,
                                  MeterRegistry meterRegistry,
                                  ReconnectReconciler reconciler) {
        this.hook = Objects.requireNonNull(hook, "hook");
        this.timeMapper = Objects.requireNonNull(timeMapper, "timeMapper");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        this.reconciler = Objects.requireNonNull(reconciler, "reconciler");
    }
```

Replace the body of `onConnectionRecovered` with:

```java
    @Override
    public void onConnectionRecovered(String serviceName) {
        Instant priorDisconnect = disconnectedAt;
        Instant now = timeMapper.now();
        lastConnectedAt = now;
        disconnectedAt = null;
        connected = true;
        connectionRecoveredCounter.increment();
        log.info("Connection recovered: service={} at={}", serviceName, now);

        if (priorDisconnect != null) {
            Duration outage = Duration.between(priorDisconnect, now);
            reconciler.reconcile(outage);
        }
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests ConnectionStateTrackerTest`
Expected: PASS.

- [ ] **Step 5: Run the full suite**

Run: `./gradlew test`
Expected: PASS. The new `ReconnectReconciler` constructor argument is now mandatory; Spring will inject the singleton bean (no other tests build a tracker by hand).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/messaging/ConnectionStateTracker.java \
        src/test/java/com/myxcomp/ice/xtree/messaging/ConnectionStateTrackerTest.java
git commit -m "feat(phase11): wire ConnectionStateTracker → ReconnectReconciler"
```

---

### Task 6: `EventConsumerService` updates `ConnectionStateTracker` on every payload

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/messaging/EventConsumerService.java`
- Modify: `src/test/java/com/myxcomp/ice/xtree/messaging/EventConsumerServiceTest.java`

- [ ] **Step 1: Extend the existing test**

In `EventConsumerServiceTest`, add a `@Mock ConnectionStateTracker tracker` (or whatever fixture style the file uses — match it precisely). Add one new test:

```java
@Test
void recordEventReceivedIsCalledAfterDeserialiseBeforeSelfEchoCheck() throws Exception {
    String localId = "local-instance";
    when(instanceIdProvider.getInstanceId()).thenReturn(localId);
    // Build a self-echo payload (same instanceId as local).
    TreeMutationEvent event = makeSelfEchoEvent(localId);  // reuse existing test helper
    String json = objectMapper.writeValueAsString(event);

    consumer.processPayload(json);

    verify(tracker).recordEventReceived();
}
```

Update the existing constructor wiring in `@BeforeEach` to pass the new `tracker` mock:

```java
tracker = mock(ConnectionStateTracker.class);
consumer = new EventConsumerService(objectMapper, dispatcher, instanceIdProvider, meterRegistry, tracker);
```

(`makeSelfEchoEvent(...)` may not exist with that name in the current test file — use the existing fixture-builder pattern. The point: the deserialised event has `instanceId == localId` so it would be dropped as self-echo, yet `recordEventReceived` must still fire.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests EventConsumerServiceTest`
Expected: COMPILATION FAIL — `EventConsumerService` constructor does not accept `ConnectionStateTracker`.

- [ ] **Step 3: Update `EventConsumerService`**

In `src/main/java/com/myxcomp/ice/xtree/messaging/EventConsumerService.java`:

Add field:

```java
    private final ConnectionStateTracker tracker;
```

Update the constructor to accept `ConnectionStateTracker tracker` and assign it.

In `processPayload`, immediately after the successful `objectMapper.readValue(...)` line and before the self-echo branch, insert:

```java
        tracker.recordEventReceived();
```

- [ ] **Step 4: Run the consumer tests**

Run: `./gradlew test --tests EventConsumerServiceTest`
Expected: PASS.

- [ ] **Step 5: Run the full suite**

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/messaging/EventConsumerService.java \
        src/test/java/com/myxcomp/ice/xtree/messaging/EventConsumerServiceTest.java
git commit -m "feat(phase11): EventConsumerService records inbound event timestamp"
```

---

### Task 7: `MessagingHealthIndicator`

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/messaging/MessagingHealthIndicator.java`
- Create: `src/test/java/com/myxcomp/ice/xtree/messaging/MessagingHealthIndicatorTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/myxcomp/ice/xtree/messaging/MessagingHealthIndicatorTest.java`:

```java
package com.myxcomp.ice.xtree.messaging;

import com.myxcomp.ice.xtree.common.TimeMapper;
import com.myxcomp.ice.xtree.config.SolaceProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MessagingHealthIndicatorTest {

    private static final Instant T0 = Instant.parse("2026-05-18T10:00:00Z");

    private ConnectionStateTracker tracker;
    private TimeMapper timeMapper;
    private SolaceProperties props;
    private MessagingHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        // Build a real tracker over mocked collaborators so we can drive state through its callbacks.
        timeMapper = mock(TimeMapper.class);
        when(timeMapper.now()).thenReturn(T0);
        RecoveryListenerHook hook = mock(RecoveryListenerHook.class);
        ReconnectReconciler reconciler = mock(ReconnectReconciler.class);
        tracker = new ConnectionStateTracker(hook, timeMapper, new SimpleMeterRegistry(), reconciler);
        tracker.registerWithHook();

        props = new SolaceProperties(
                "BC/ICE/ITEMTREE",
                new SolaceProperties.Reconnect(Duration.ofMinutes(1), Duration.ofHours(1)),
                new SolaceProperties.Health(Duration.ofHours(4)));
        indicator = new MessagingHealthIndicator(tracker, timeMapper, props);
    }

    @Test
    void neverConnectedReportsUpWithUnknownConnection() {
        Health h = indicator.health();

        assertThat(h.getStatus()).isEqualTo(Status.UP);
        assertThat(h.getDetails()).containsEntry("connected", false);
        assertThat(h.getDetails()).containsEntry("outageSeconds", 0L);
    }

    @Test
    void currentlyConnectedReportsUp() {
        tracker.onConnectionRecovered("itemtree");

        Health h = indicator.health();

        assertThat(h.getStatus()).isEqualTo(Status.UP);
        assertThat(h.getDetails()).containsEntry("connected", true);
    }

    @Test
    void outageBelowMarkDownAfterStillReportsUp() {
        tracker.onConnectionLost("itemtree");
        when(timeMapper.now()).thenReturn(T0.plus(Duration.ofHours(3).plusMinutes(59)));

        Health h = indicator.health();

        assertThat(h.getStatus()).isEqualTo(Status.UP);
        assertThat(h.getDetails()).containsEntry("connected", false);
    }

    @Test
    void outageAtOrAboveMarkDownAfterReportsDown() {
        tracker.onConnectionLost("itemtree");
        when(timeMapper.now()).thenReturn(T0.plus(Duration.ofHours(4)));

        Health h = indicator.health();

        assertThat(h.getStatus()).isEqualTo(Status.DOWN);
        assertThat(h.getDetails()).containsEntry("connected", false);
        assertThat((Long) h.getDetails().get("outageSeconds"))
                .isEqualTo(Duration.ofHours(4).toSeconds());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests MessagingHealthIndicatorTest`
Expected: COMPILATION FAIL — class does not exist.

- [ ] **Step 3: Write the indicator**

Create `src/main/java/com/myxcomp/ice/xtree/messaging/MessagingHealthIndicator.java`:

```java
package com.myxcomp.ice.xtree.messaging;

import com.myxcomp.ice.xtree.common.TimeMapper;
import com.myxcomp.ice.xtree.config.SolaceProperties;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Spring Boot {@link HealthIndicator} for the messaging subsystem (design §6 "Failure handling" row
 * "Outage > 4 h"). Exposed at {@code /actuator/health/messaging}.
 *
 * <p>UP when currently connected or never disconnected. DOWN when outage ≥
 * {@code itemtree.solace.health.mark-down-after}.
 */
@Component
public class MessagingHealthIndicator implements HealthIndicator {

    private final ConnectionStateTracker tracker;
    private final TimeMapper timeMapper;
    private final SolaceProperties props;

    public MessagingHealthIndicator(ConnectionStateTracker tracker,
                                    TimeMapper timeMapper,
                                    SolaceProperties props) {
        this.tracker = Objects.requireNonNull(tracker, "tracker");
        this.timeMapper = Objects.requireNonNull(timeMapper, "timeMapper");
        this.props = Objects.requireNonNull(props, "props");
    }

    @Override
    public Health health() {
        Instant disconnectedAt = tracker.disconnectedAt();
        long outageSeconds = disconnectedAt == null
                ? 0L
                : Duration.between(disconnectedAt, timeMapper.now()).toSeconds();
        Instant lastEvent = tracker.lastEventReceivedAt();
        long lastEventAgeSeconds = lastEvent == null
                ? 0L
                : Duration.between(lastEvent, timeMapper.now()).toSeconds();

        Health.Builder builder = outageSeconds >= props.health().markDownAfter().toSeconds()
                ? Health.down()
                : Health.up();

        return builder
                .withDetail("connected", tracker.isConnected())
                .withDetail("outageSeconds", outageSeconds)
                .withDetail("lastEventAgeSeconds", lastEventAgeSeconds)
                .build();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests MessagingHealthIndicatorTest`
Expected: PASS.

- [ ] **Step 5: Run the full suite**

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/messaging/MessagingHealthIndicator.java \
        src/test/java/com/myxcomp/ice/xtree/messaging/MessagingHealthIndicatorTest.java
git commit -m "feat(phase11): MessagingHealthIndicator — flips DOWN past markDownAfter"
```

---

### Task 8: Integration test — drive disconnect/recovery through the stub

**Files:**
- Create: `src/test/java/com/myxcomp/ice/xtree/messaging/MessagingResilienceIT.java`

- [ ] **Step 1: Write the integration test**

Create `src/test/java/com/myxcomp/ice/xtree/messaging/MessagingResilienceIT.java`:

```java
package com.myxcomp.ice.xtree.messaging;

import com.myxcomp.ice.xtree.common.TimeMapper;
import com.myxcomp.ice.xtree.messaging.dev.StubConnectionExceptionListener;
import com.myxcomp.ice.xtree.refresh.RefreshOrchestrator;
import com.myxcomp.ice.xtree.refresh.RefreshResult;
import com.myxcomp.ice.xtree.refresh.DriftCounters;
import com.myxcomp.ice.xtree.refresh.DeltaCounters;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dev")
class MessagingResilienceIT {

    private static final Instant T0 = Instant.parse("2026-05-18T10:00:00Z");

    @Autowired StubConnectionExceptionListener stub;
    @Autowired ConnectionStateTracker tracker;
    @SpyBean RefreshOrchestrator orchestrator;
    @MockBean TimeMapper timeMapper;
    @Autowired MeterRegistry meterRegistry;

    @BeforeEach
    void freezeTime() {
        when(timeMapper.now()).thenReturn(T0);
        // Stub orchestrator so the scheduled tasks complete without touching the real cache snapshot.
        org.mockito.Mockito.doReturn(RefreshResult.deltaSuccess(0, new DeltaCounters()))
                .when(orchestrator).runDelta();
        org.mockito.Mockito.doReturn(RefreshResult.fullSuccess(0, new DriftCounters()))
                .when(orchestrator).runFullReload();
    }

    @Test
    void firstConnectDoesNotTriggerRefresh() {
        stub.simulateRecovery();

        assertThat(tracker.isConnected()).isTrue();
        // No outage existed → no submission. Counter absent.
        assertThat(meterRegistry.find("itemtree.solace.reconnect_reconcile").counters()).isEmpty();
    }

    @Test
    void shortOutageDoesNotTriggerRefresh() throws Exception {
        // Establish first connect, then disconnect, then recover after 30s.
        stub.simulateRecovery();
        stub.simulateDisconnect();
        when(timeMapper.now()).thenReturn(T0.plusSeconds(30));
        stub.simulateRecovery();

        // No reconcile submitted.
        assertThat(meterRegistry.find("itemtree.solace.reconnect_reconcile").counters()).isEmpty();
    }

    @Test
    void mediumOutageTriggersDeltaRefresh() {
        stub.simulateRecovery();
        stub.simulateDisconnect();
        when(timeMapper.now()).thenReturn(T0.plus(Duration.ofMinutes(10)));
        stub.simulateRecovery();

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> verify(orchestrator).runDelta());
        assertThat(meterRegistry.counter("itemtree.solace.reconnect_reconcile", "type", "delta").count())
                .isOne();
    }

    @Test
    void longOutageTriggersFullReload() {
        stub.simulateRecovery();
        stub.simulateDisconnect();
        when(timeMapper.now()).thenReturn(T0.plus(Duration.ofHours(2)));
        stub.simulateRecovery();

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> verify(orchestrator).runFullReload());
        assertThat(meterRegistry.counter("itemtree.solace.reconnect_reconcile", "type", "full").count())
                .isOne();
    }
}
```

**Note on `Awaitility`:** Spring Boot Starter Test already brings `awaitility` transitively as of Boot 2.x+. Verify by checking `./gradlew dependencies --configuration testRuntimeClasspath | grep awaitility`. If absent, add `testImplementation("org.awaitility:awaitility")` to `build.gradle.kts`.

- [ ] **Step 2: Check the awaitility dependency**

Run: `./gradlew dependencies --configuration testRuntimeClasspath` and grep for `awaitility`. If present, skip the next step.

If not present:

Open `build.gradle.kts` and add inside the existing `dependencies { ... }` block, under the `// ── Test stack` comment:

```kotlin
    testImplementation("org.awaitility:awaitility")
```

- [ ] **Step 3: Run the IT**

Run: `./gradlew test --tests MessagingResilienceIT`
Expected: PASS.

- [ ] **Step 4: Run the full suite**

Run: `./gradlew test`
Expected: PASS. Tests with shared state across the four cases each use a fresh `@SpringBootTest` context, so `tracker` and counters reset between methods. If the meter-registry state turns out to be shared due to context caching, switch to `@DirtiesContext` per method or refactor to construct fresh `tracker`/`reconciler` in the test rather than autowire — but try the `@SpringBootTest` path first.

If the `@SpringBootTest` cache causes counter accumulation, change the test class to:

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dev")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class MessagingResilienceIT {
```

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/myxcomp/ice/xtree/messaging/MessagingResilienceIT.java
# If build.gradle.kts was changed, include it:
git add build.gradle.kts 2>/dev/null || true
git commit -m "test(phase11): MessagingResilienceIT — stub-driven threshold matrix"
```

---

### Task 9: Mark Phase 11 complete in `IMPLEMENTATION_NOTES.md`

**Files:**
- Modify: `IMPLEMENTATION_NOTES.md`

- [ ] **Step 1: Run the final full build**

Run: `./gradlew clean build`
Expected: BUILD SUCCESSFUL. Note the total test count from the gradle output; record it for the IMPLEMENTATION_NOTES update.

- [ ] **Step 2: Update IMPLEMENTATION_NOTES.md**

In `IMPLEMENTATION_NOTES.md`:

- Change the Phase 11 heading from `## Phase 11 — Resilience ⬅ NEXT — implementable in Phase A via stubs` to `## Phase 11 — Resilience ✅ COMPLETE (2026-05-18)`.
- Below the goal block, add a "Deviations from plan (reviewed and approved):" section if any deviations were made, or write "Deviations from plan: none." if none.
- Add the "Actual done state:" line citing the test count from Step 1, e.g. `**Actual done state:** XXX tests green; ./gradlew clean build → BUILD SUCCESSFUL.`.
- Move the `⬅ NEXT` marker to the Phase 12 heading: `## Phase 12 — Observability & polish ⬅ NEXT`.

- [ ] **Step 3: Commit**

```bash
git add IMPLEMENTATION_NOTES.md
git commit -m "docs(phase11): mark Resilience phase complete"
```

- [ ] **Step 4: Tag the phase**

```bash
git tag phase-11-resilience
```

(Do not push the tag — that's a user-managed step.)

---

## Final Verification

After all tasks:

- [ ] `./gradlew clean build` exits BUILD SUCCESSFUL.
- [ ] The new metrics are exposed at `/actuator/prometheus` after running the app:
  - `itemtree_solace_connected`
  - `itemtree_solace_outage_seconds`
  - `itemtree_solace_last_event_age_seconds`
  - `itemtree_solace_connection_lost_total`
  - `itemtree_solace_connection_recovered_total`
  - `itemtree_solace_reconnect_reconcile_total{type="delta"}` / `{type="full"}`
- [ ] `/actuator/health` shows a `messaging` component with `status: UP` and a `details` block containing `connected`, `outageSeconds`, `lastEventAgeSeconds`.
- [ ] No Phase A / Phase B boundary violations introduced: no class outside `messaging/dev/` references `StubConnectionExceptionListener`; no class in production code imports `com.barcap.ice.service.jms.*`.

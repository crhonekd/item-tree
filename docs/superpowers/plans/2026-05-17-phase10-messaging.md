# Phase 10 — Messaging Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire the Phase A messaging stack end-to-end. Outbound: services hand a `TreeMutationEvent` to `EventPublisher`; the dev-profile `LocalLoopbackEventPublisher` serialises it as JSON and publishes onto an in-memory bus. Inbound: a dev-profile `LocalLoopbackEventConsumerStarter` subscribes `EventConsumerService::processPayload` to the bus; the consumer deserialises, drops self-echoes by `instanceId`, tracks per-source sequence gaps, and dispatches to `TreeCache.apply*` via `EventDispatcher`. Stubs for the `ConnectionExceptionListener` recovery hook are scaffolded so Phase 11 has something to drive. Everything is production-shaped — the consumer/dispatcher logic that runs in Phase B is the same code; only the bean wiring changes.

**Architecture:**
- The only **always-present** production beans are `EventPublisher` (interface, already in `messaging/`), `SequenceGenerator` (already in `messaging/`), `EventDispatcher` (new pure-logic class, no profile), `EventConsumerService` (new pure-logic class, no profile), and `ConnectionRecoveryListener` (new interface, no profile).
- The **Phase A** beans live in `messaging/dev/` under `@Profile("dev")`: `InMemoryEventBus` (`Map<String, List<Consumer<String>>>` with synchronous dispatch), `LocalLoopbackEventPublisher` (calls `bus.publish(topic, json)`), `LocalLoopbackEventConsumerStarter` (`ApplicationRunner @Order(2)` that wires consumer to bus), and `StubConnectionExceptionListener` (Phase 11 scaffolding with `addRecoveryListener(...)`, `simulateDisconnect()`, `simulateRecovery()`).
- `EventConsumerService.processPayload(String json)` is the **single entry point** for inbound events. In Phase B this method is called from a one-line `MessageListener.onMessage(Message msg)` adapter inside the `prod`-profile config; in Phase A it is called directly from the in-memory bus subscription. Either way, the method takes a JSON string and returns void; all failures (deserialise, self-echo, dispatch) are absorbed and counted, never thrown.
- Self-echo suppression compares the envelope's `instanceId` against `InstanceIdProvider.getInstanceId()`. Match → drop + `itemtree.event.self_dropped` counter.
- Sequence-gap tracking maintains a `ConcurrentHashMap<String, Long>` (instanceId → last seen sequence). When the new event's sequence is not `previous + 1` and previous existed, increment `itemtree.event.sequence.gap`. Gaps are an observability signal only; recovery happens via periodic refresh.
- `LocalLoopbackEventPublisher` replaces the Phase 7 placeholder `NoOpEventPublisher` (Phase 10 deletes that file). Both are `@Profile("dev")`; with `LocalLoopbackEventPublisher` present in the dev profile, only one `EventPublisher` bean exists.
- JSON I/O uses the **Spring Boot default `ObjectMapper`** — `application.yml` already disables `WRITE_DATES_AS_TIMESTAMPS`, so `Instant` serialises as ISO-8601 with `Z`. We additionally set `spring.jackson.deserialization.fail-on-unknown-properties: false` so a newer producer adding envelope fields does not break older consumer instances (Phase 9 quality-review follow-up).
- The single `itemtree.solace.topic` configuration key drives both publisher and subscriber. Wrapped in a `SolaceProperties` record so Phase 11 can extend it with the reconnect / health knobs without restructuring.

**Tech Stack:** Java 21, Spring Boot (`@Component`, `@Profile`, `@ConfigurationProperties`, `ApplicationRunner`), Jackson (`ObjectMapper` injection, Spring Boot default), Micrometer counters, JUnit 5, Mockito, AssertJ. No new third-party dependencies.

---

## Self-review note for the executing engineer

**Before any task:** re-read design §6 ("Distribution (Solace)") and §18 ("Observability → Messaging"). Every metric name in this plan must match design §18 exactly. Counter tags use `op=CREATE|UPDATE|MOVE|RENAME|DELETE` (the enum literal name).

**Idempotency contract reminder (design §4):** `TreeCache.apply*` never throws on missing parent / missing id. Tests that assert `apply*` exceptions are wrong. `applyDelete(Set<Long>)` requires the caller to pass the **complete** descendant set; for the `DELETE` op we simply pass `new HashSet<>(payload.deletedIds())` since the publisher already broadcast the full set (`ItemService.deleteItem` calls `cascadeDeleteSubtree` which returns root + all descendants).

**Things that will look wrong but aren't:**
- The consumer **catches `RuntimeException`** at the outer boundary of `processPayload`. This is by design — a message that blows up must not poison the JMS session (Phase B) nor the in-memory dispatch (Phase A). All failures emit a metric and log at WARN level.
- `EventConsumerService` is **not annotated `@Component`**. It is constructed inside the wiring layer to make it clear that profile-specific subscribers (Phase A: loopback starter; Phase B: `JmsListenerService`) own its lifecycle. Wait — we DO want it auto-discovered for `@Autowired` in tests and in the starter. Add `@Component` to it; the no-profile annotation means it's present in both `dev` and (future) `prod`. The "wiring owns lifecycle" comment refers to the *subscription*, not the *bean*.
- `LocalLoopbackEventConsumerStarter` is `@Order(2)` so it runs after `TreeCacheBootstrap` (`@Order(1)`). This matches the Phase B `MessagingStarter` ordering described in design §7 T2/T3.
- `InMemoryEventBus.publish` invokes subscribers **synchronously, on the publisher thread**. This is acceptable for unit tests and the Phase A loopback. The real Solace listener (Phase B) runs subscribers on the DMLC thread. Both are single-threaded for a given topic (Phase A: implicit; Phase B: `concurrentConsumers=1`).

---

## File Structure

### New production files

| Path | Responsibility |
|---|---|
| `src/main/java/com/myxcomp/ice/xtree/messaging/ConnectionRecoveryListener.java` | Interface with `onConnectionLost(String serviceName)` / `onConnectionRecovered(String serviceName)`. Implemented in Phase 11 (`ConnectionStateTracker`); scaffolded here so `StubConnectionExceptionListener` has something to wire. |
| `src/main/java/com/myxcomp/ice/xtree/messaging/EventDispatcher.java` | `@Component` pure-logic class. Single method: `dispatch(TreeMutationEvent event)` switching on `OperationType` and invoking the matching `TreeCache.apply*`. |
| `src/main/java/com/myxcomp/ice/xtree/messaging/EventConsumerService.java` | `@Component`. `processPayload(String json)` — entry point for inbound events. Deserialises (Jackson), drops self-echoes, tracks sequence gaps, calls `EventDispatcher.dispatch`. All failures absorbed + counted. |
| `src/main/java/com/myxcomp/ice/xtree/config/SolaceProperties.java` | `@ConfigurationProperties("itemtree.solace") @Validated` record carrying `topic` (Phase 10) plus three Phase-11 placeholders (`concurrentConsumers`, `publisherMaxAttempts`, plus a nested `reconnect` / `health` left absent for Phase 11 to add). |

### New Phase-A stub files (in `messaging/dev/`, all `@Profile("dev")`)

| Path | Responsibility |
|---|---|
| `src/main/java/com/myxcomp/ice/xtree/messaging/dev/InMemoryEventBus.java` | `@Component @Profile("dev")`. `publish(String topic, String payload)` / `subscribe(String topic, Consumer<String> handler)`. Backing store: `ConcurrentHashMap<String, CopyOnWriteArrayList<Consumer<String>>>`. Subscribers invoked synchronously on the publisher thread. |
| `src/main/java/com/myxcomp/ice/xtree/messaging/dev/LocalLoopbackEventPublisher.java` | `@Component @Profile("dev")` implementing `EventPublisher`. Serialises `TreeMutationEvent` to JSON with `ObjectMapper`; publishes onto `InMemoryEventBus`; increments `itemtree.event.published{op}`. On serialisation failure: WARN + `itemtree.event.publish.serialization_failure` counter; does not throw. Replaces `NoOpEventPublisher` (which is deleted). |
| `src/main/java/com/myxcomp/ice/xtree/messaging/dev/LocalLoopbackEventConsumerStarter.java` | `@Component @Order(2) @Profile("dev")` implementing `ApplicationRunner`. Subscribes `eventConsumerService::processPayload` to the configured topic on `InMemoryEventBus`. |
| `src/main/java/com/myxcomp/ice/xtree/messaging/dev/StubConnectionExceptionListener.java` | `@Component @Profile("dev")`. Phase 11 scaffolding: `addRecoveryListener(ConnectionRecoveryListener)`, `simulateDisconnect()` and `simulateRecovery()` test helpers. Phase 10 only verifies the listener-registry contract; Phase 11 wires `ConnectionStateTracker` into it. |

### Modified production files

| Path | Change |
|---|---|
| `src/main/java/com/myxcomp/ice/xtree/messaging/dev/NoOpEventPublisher.java` | **DELETE.** Replaced by `LocalLoopbackEventPublisher`. |
| `src/main/java/com/myxcomp/ice/xtree/messaging/package-info.java` | Update Javadoc — Phase 10 implements the publish/consume contracts. |
| `src/main/java/com/myxcomp/ice/xtree/messaging/EventPublisher.java` | Update Javadoc — Phase 10 supplies `LocalLoopbackEventPublisher`; Phase B replaces it with the JMS-backed publisher. |
| `src/main/java/com/myxcomp/ice/xtree/messaging/event/TreeMutationEvent.java` | Add class-level `@JsonIgnoreProperties(ignoreUnknown = true)` (defence-in-depth alongside the global Jackson setting). |
| `src/main/resources/application.yml` | Replace `solace: {}` placeholder with `solace.topic: "BC/ICE/ITEMTREE"`. Add `spring.jackson.deserialization.fail-on-unknown-properties: false`. |
| `src/main/java/com/myxcomp/ice/xtree/ItemTreeApplication.java` | Add `@ConfigurationPropertiesScan` (if not already present — verify) so `SolaceProperties` is picked up alongside `RefreshProperties` and `DataProperties`. |
| `IMPLEMENTATION_NOTES.md` | Mark Phase 10 ✅ COMPLETE; move ⬅ NEXT marker to Phase 11; record any deviations. |

### New test files

| Path | Coverage |
|---|---|
| `src/test/java/com/myxcomp/ice/xtree/config/SolacePropertiesTest.java` | `@SpringBootTest`-style binding test — `topic` is parsed from yaml; default is `"BC/ICE/ITEMTREE"`. |
| `src/test/java/com/myxcomp/ice/xtree/messaging/EventDispatcherTest.java` | Unit — each `OperationType` invokes the corresponding `TreeCache.apply*` with the right arguments. Idempotency-tolerance is the cache's contract, not the dispatcher's, but a parameterised test verifies the dispatcher tolerates `apply*` no-ops (no exception thrown). |
| `src/test/java/com/myxcomp/ice/xtree/messaging/EventConsumerServiceTest.java` | Unit — happy path (each op): payload deserialised + dispatched + `itemtree.event.consumed{op}` incremented. Self-echo: matching `instanceId` → no dispatch + `itemtree.event.self_dropped` incremented. Deserialise failure: bad JSON + `itemtree.event.consume.deserialize.failure` incremented + no throw. Apply failure (dispatch throws `RuntimeException`): `itemtree.event.consume.apply.failure` incremented + no throw. Sequence gap: 1, 2, then 5 → `itemtree.event.sequence.gap` incremented exactly once. First event from an instance does not count as a gap. |
| `src/test/java/com/myxcomp/ice/xtree/messaging/dev/InMemoryEventBusTest.java` | Unit — subscribe + publish round-trip; multiple subscribers on one topic all called; subscribers on other topics not called; subscriber throwing does not prevent next subscriber from being called and does not throw out of `publish`. |
| `src/test/java/com/myxcomp/ice/xtree/messaging/dev/LocalLoopbackEventPublisherTest.java` | Unit — `publish(event)` serialises and calls `bus.publish(topic, json)`; `itemtree.event.published{op}` incremented; serialisation-failure path (mock `ObjectMapper` to throw) → counter + log, no exception propagated. |
| `src/test/java/com/myxcomp/ice/xtree/messaging/dev/LocalLoopbackEventConsumerStarterTest.java` | Unit — `run()` subscribes a handler that delegates to `EventConsumerService.processPayload`. A test publish on the bus reaches the consumer. |
| `src/test/java/com/myxcomp/ice/xtree/messaging/dev/StubConnectionExceptionListenerTest.java` | Unit — `addRecoveryListener` + `simulateDisconnect` → `onConnectionLost("itemtree")` fired; `simulateRecovery` → `onConnectionRecovered(...)`. Multiple listeners all fire. A listener that throws does not prevent the next listener from being called. |
| `src/test/java/com/myxcomp/ice/xtree/messaging/MessagingLoopbackIT.java` | `@SpringBootTest(webEnvironment = NONE) @ActiveProfiles("dev")`. After context start: hand-crafted JSON envelope with a *foreign* `instanceId` published onto the bus → cache reflects the change. Same envelope with the *local* `instanceId` → cache unchanged + `self_dropped` counter incremented. End-to-end via `ItemService.createItem` → self-echo path (local publisher → bus → consumer drops own event) → DB row exists, cache populated, `self_dropped` counter incremented exactly once. |

---

## Conventions used by the rest of this plan

- **Logger:** `private static final Logger log = LoggerFactory.getLogger(<owning class>.class);` — SLF4J, no Lombok `@Slf4j`. Matches existing code.
- **`@Profile("dev")`:** type-level on every class under `messaging/dev/`. Never on the production-shape interfaces or pure-logic classes.
- **Metric names:** identical to design §18 ("Messaging" section). Tags: `op=CREATE|UPDATE|MOVE|RENAME|DELETE` (the enum's `name()`).
- **`Objects.requireNonNull`** guards on every public method parameter that is not nullable per the design — matches Phase 4/5/7 pattern.
- **AssertJ semantic methods:** `isZero()`, `isOne()`, `isEmpty()`, `isTrue()`, `containsExactly(...)`. Never `isEqualTo(0)` on numbers, `isEqualTo(true)` on booleans.
- **`@ParameterizedTest` for op-by-op variants** — one parameterised test per dispatch behaviour, not five copy-pasted tests.
- **Imports:** static `org.assertj.core.api.Assertions.assertThat` / `assertThatThrownBy`; static `org.mockito.Mockito.*` / `BDDMockito.*`.
- **No `mockStatic`, no PowerMock, no `@Disabled` without a `// TODO`.**

---

## Tasks

### Task 1: Add `spring.jackson.deserialization.fail-on-unknown-properties` and `itemtree.solace.topic`

**Files:**
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: Modify `application.yml`**

Replace the `solace: {}` placeholder under `itemtree:` with:

```yaml
itemtree:
  # ... existing keys ...
  solace:
    topic: "BC/ICE/ITEMTREE"
```

Add (under `spring.jackson:`):

```yaml
spring:
  jackson:
    serialization:
      write-dates-as-timestamps: false
    deserialization:
      fail-on-unknown-properties: false
```

- [ ] **Step 2: Run the existing test suite to make sure the yaml change is parseable**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL (413 tests pass; this task changes nothing observable yet).

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/application.yml
git commit -m "config(phase10): add itemtree.solace.topic and disable Jackson FAIL_ON_UNKNOWN_PROPERTIES"
```

---

### Task 2: Add `SolaceProperties`

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/config/SolaceProperties.java`
- Create: `src/test/java/com/myxcomp/ice/xtree/config/SolacePropertiesTest.java`
- Modify: `src/main/java/com/myxcomp/ice/xtree/ItemTreeApplication.java` (only if `@ConfigurationPropertiesScan` is not already present — verify first by reading the file)

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/myxcomp/ice/xtree/config/SolacePropertiesTest.java`:

```java
package com.myxcomp.ice.xtree.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = SolacePropertiesTest.Config.class)
@TestPropertySource(properties = {
        "itemtree.solace.topic=BC/ICE/ITEMTREE"
})
class SolacePropertiesTest {

    @EnableConfigurationProperties(SolaceProperties.class)
    static class Config {}

    @Autowired
    private SolaceProperties props;

    @Test
    void topicIsBound() {
        assertThat(props.topic()).isEqualTo("BC/ICE/ITEMTREE");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests SolacePropertiesTest`
Expected: COMPILATION FAILURE (`SolaceProperties` not found).

- [ ] **Step 3: Create `SolaceProperties`**

Create `src/main/java/com/myxcomp/ice/xtree/config/SolaceProperties.java`:

```java
package com.myxcomp.ice.xtree.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Solace JMS knobs (design §17 → {@code itemtree.solace.*}).
 *
 * <p>Phase 10 uses only {@code topic}. Phase 11 will add reconnect thresholds and the
 * outage health-down duration; structuring this as a record now means those additions are
 * additive — no migration required.
 *
 * @param topic JMS topic name. Drives both publisher destination and consumer subscription.
 */
@Validated
@ConfigurationProperties("itemtree.solace")
public record SolaceProperties(
        @NotBlank String topic
) {}
```

- [ ] **Step 4: Verify `@ConfigurationPropertiesScan`**

Run: `grep -n "ConfigurationPropertiesScan\|EnableConfigurationProperties" src/main/java/com/myxcomp/ice/xtree/ItemTreeApplication.java src/main/java/com/myxcomp/ice/xtree/config/*.java`

If `ItemTreeApplication` already has `@ConfigurationPropertiesScan`, no further action. If not, add it:

```java
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ItemTreeApplication {
    public static void main(String[] args) {
        SpringApplication.run(ItemTreeApplication.class, args);
    }
}
```

If the existing pattern uses `@EnableConfigurationProperties(...)` on each properties record's owning class, follow that pattern instead (add `SolaceProperties.class` to the list). Match what's already there; do not introduce a new mechanism.

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew test --tests SolacePropertiesTest`
Expected: PASS.

- [ ] **Step 6: Run the full suite — no regressions**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/config/SolaceProperties.java \
        src/test/java/com/myxcomp/ice/xtree/config/SolacePropertiesTest.java \
        src/main/java/com/myxcomp/ice/xtree/ItemTreeApplication.java
git commit -m "feat(phase10): add SolaceProperties config record bound to itemtree.solace.*"
```

---

### Task 3: Add `ConnectionRecoveryListener` interface

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/messaging/ConnectionRecoveryListener.java`

- [ ] **Step 1: Create the interface**

Create `src/main/java/com/myxcomp/ice/xtree/messaging/ConnectionRecoveryListener.java`:

```java
package com.myxcomp.ice.xtree.messaging;

/**
 * Callback fired by the messaging library's connection-exception listener around outages.
 *
 * <p>Design §6. {@code onConnectionLost} fires at the start of the library's
 * {@code onException(JMSException)}; {@code onConnectionRecovered} fires after the
 * library's recovery loop breaks successfully. Implementations must not throw —
 * exceptions inside callbacks are swallowed by the caller.
 *
 * <p>Implemented by {@code ConnectionStateTracker} in Phase 11.
 * In Phase A the {@code StubConnectionExceptionListener} provides the callback
 * dispatch site so tests can drive disconnect/recovery transitions deterministically.
 */
public interface ConnectionRecoveryListener {

    void onConnectionLost(String serviceName);

    void onConnectionRecovered(String serviceName);
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL (interface compiles in isolation).

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/messaging/ConnectionRecoveryListener.java
git commit -m "feat(phase10): add ConnectionRecoveryListener interface (Phase 11 will implement)"
```

---

### Task 4: `EventDispatcher` — routes envelope to `TreeCache.apply*`

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/messaging/EventDispatcher.java`
- Create: `src/test/java/com/myxcomp/ice/xtree/messaging/EventDispatcherTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/myxcomp/ice/xtree/messaging/EventDispatcherTest.java`:

```java
package com.myxcomp.ice.xtree.messaging;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.TreeCache;
import com.myxcomp.ice.xtree.messaging.event.OperationType;
import com.myxcomp.ice.xtree.messaging.event.TreeMutationEvent;
import com.myxcomp.ice.xtree.messaging.event.payload.CreatePayload;
import com.myxcomp.ice.xtree.messaging.event.payload.DeletePayload;
import com.myxcomp.ice.xtree.messaging.event.payload.MovePayload;
import com.myxcomp.ice.xtree.messaging.event.payload.RenamePayload;
import com.myxcomp.ice.xtree.messaging.event.payload.UpdatePayload;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class EventDispatcherTest {

    private static final Instant T = Instant.parse("2026-05-17T10:00:00Z");

    private final TreeCache cache = mock(TreeCache.class);
    private final EventDispatcher dispatcher = new EventDispatcher(cache);

    private TreeMutationEvent envelope(OperationType op, Object payload) {
        return TreeMutationEvent.builder()
                .eventId("e").instanceId("peer").sequence(1L).occurredAt(T)
                .iceUser("u").impersonatedUser(null).operationType(op)
                .payload((com.myxcomp.ice.xtree.messaging.event.payload.EventPayload) payload)
                .build();
    }

    @Test
    void create_calls_applyCreate_with_constructed_CachedNode() {
        CreatePayload p = new CreatePayload(100L, 1L, "N", "Folder", T, "alice");
        dispatcher.dispatch(envelope(OperationType.CREATE, p));
        verify(cache).applyCreate(new CachedNode(100L, 1L, "N", "Folder", T, "alice"));
    }

    @Test
    void update_calls_applyMetadataUpdate() {
        UpdatePayload p = new UpdatePayload(100L, T, "alice");
        dispatcher.dispatch(envelope(OperationType.UPDATE, p));
        verify(cache).applyMetadataUpdate(100L, T, "alice");
    }

    @Test
    void move_calls_applyMove() {
        MovePayload p = new MovePayload(100L, 1L, 5L, T, "alice");
        dispatcher.dispatch(envelope(OperationType.MOVE, p));
        verify(cache).applyMove(100L, 5L, T, "alice");
    }

    @Test
    void rename_calls_applyRename() {
        RenamePayload p = new RenamePayload(100L, "NewName", T, "alice");
        dispatcher.dispatch(envelope(OperationType.RENAME, p));
        verify(cache).applyRename(100L, "NewName", T, "alice");
    }

    @Test
    void delete_calls_applyDelete_with_full_set() {
        DeletePayload p = new DeletePayload(List.of(100L, 101L, 102L));
        dispatcher.dispatch(envelope(OperationType.DELETE, p));
        verify(cache).applyDelete(new HashSet<>(List.of(100L, 101L, 102L)));
    }

    @Test
    void apply_exceptions_propagate_so_caller_can_count_them() {
        // The dispatcher itself does not catch — EventConsumerService handles failure absorption.
        doThrow(new RuntimeException("boom")).when(cache).applyCreate(any());
        CreatePayload p = new CreatePayload(100L, 1L, "N", "Folder", T, "alice");
        assertThatThrownBy(() -> dispatcher.dispatch(envelope(OperationType.CREATE, p)))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("boom");
    }

    @Test
    void null_event_throws_NPE() {
        assertThatThrownBy(() -> dispatcher.dispatch(null))
                .isInstanceOf(NullPointerException.class);
        verifyNoInteractions(cache);
    }

    @Test
    void wrong_payload_type_for_operation_throws_ClassCastException() {
        // Defensive — the deserializer guarantees correct pairing but the dispatcher
        // should fail fast rather than silently no-op if envelope/payload mismatch.
        UpdatePayload wrong = new UpdatePayload(100L, T, "alice");
        assertThatThrownBy(() -> dispatcher.dispatch(envelope(OperationType.CREATE, wrong)))
                .isInstanceOf(ClassCastException.class);
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests EventDispatcherTest`
Expected: COMPILATION FAILURE (`EventDispatcher` not found).

- [ ] **Step 3: Create `EventDispatcher`**

Create `src/main/java/com/myxcomp/ice/xtree/messaging/EventDispatcher.java`:

```java
package com.myxcomp.ice.xtree.messaging;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.TreeCache;
import com.myxcomp.ice.xtree.messaging.event.OperationType;
import com.myxcomp.ice.xtree.messaging.event.TreeMutationEvent;
import com.myxcomp.ice.xtree.messaging.event.payload.CreatePayload;
import com.myxcomp.ice.xtree.messaging.event.payload.DeletePayload;
import com.myxcomp.ice.xtree.messaging.event.payload.MovePayload;
import com.myxcomp.ice.xtree.messaging.event.payload.RenamePayload;
import com.myxcomp.ice.xtree.messaging.event.payload.UpdatePayload;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Objects;

/**
 * Routes a {@link TreeMutationEvent} to the matching {@link TreeCache} mutation method.
 *
 * <p>Pure logic — does not handle exceptions from {@code apply*}. The caller
 * ({@link EventConsumerService}) catches and counts apply failures. {@code apply*} is
 * tolerant by contract (design §4), so in practice exceptions only occur on bugs or
 * the cache being mid-reset.
 */
@Component
public class EventDispatcher {

    private final TreeCache cache;

    public EventDispatcher(TreeCache cache) {
        this.cache = cache;
    }

    public void dispatch(TreeMutationEvent event) {
        Objects.requireNonNull(event, "event");
        OperationType op = event.getOperationType();
        switch (op) {
            case CREATE -> {
                CreatePayload p = (CreatePayload) event.getPayload();
                cache.applyCreate(new CachedNode(
                        p.itemTreeId(), p.parentId(), p.name(), p.type(),
                        p.lastUpdate(), p.lastUpdateUser()));
            }
            case UPDATE -> {
                UpdatePayload p = (UpdatePayload) event.getPayload();
                cache.applyMetadataUpdate(p.itemTreeId(), p.lastUpdate(), p.lastUpdateUser());
            }
            case MOVE -> {
                MovePayload p = (MovePayload) event.getPayload();
                cache.applyMove(p.itemTreeId(), p.newParentId(), p.lastUpdate(), p.lastUpdateUser());
            }
            case RENAME -> {
                RenamePayload p = (RenamePayload) event.getPayload();
                cache.applyRename(p.itemTreeId(), p.newName(), p.lastUpdate(), p.lastUpdateUser());
            }
            case DELETE -> {
                DeletePayload p = (DeletePayload) event.getPayload();
                cache.applyDelete(new HashSet<>(p.deletedIds()));
            }
        }
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew test --tests EventDispatcherTest`
Expected: PASS.

- [ ] **Step 5: Run full suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/messaging/EventDispatcher.java \
        src/test/java/com/myxcomp/ice/xtree/messaging/EventDispatcherTest.java
git commit -m "feat(phase10): EventDispatcher routes TreeMutationEvent to TreeCache.apply*"
```

---

### Task 5: `EventConsumerService` — deserialise, drop self-echo, track gaps, dispatch

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/messaging/EventConsumerService.java`
- Create: `src/test/java/com/myxcomp/ice/xtree/messaging/EventConsumerServiceTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/myxcomp/ice/xtree/messaging/EventConsumerServiceTest.java`:

```java
package com.myxcomp.ice.xtree.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.myxcomp.ice.xtree.common.InstanceIdProvider;
import com.myxcomp.ice.xtree.messaging.event.OperationType;
import com.myxcomp.ice.xtree.messaging.event.TreeMutationEvent;
import com.myxcomp.ice.xtree.messaging.event.payload.CreatePayload;
import com.myxcomp.ice.xtree.messaging.event.payload.UpdatePayload;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EventConsumerServiceTest {

    private static final Instant T = Instant.parse("2026-05-17T10:00:00Z");
    private static final String LOCAL = "local-instance";
    private static final String PEER  = "peer-instance";

    private ObjectMapper mapper;
    private MeterRegistry registry;
    private EventDispatcher dispatcher;
    private InstanceIdProvider idProvider;
    private EventConsumerService consumer;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        registry = new SimpleMeterRegistry();
        dispatcher = mock(EventDispatcher.class);
        idProvider = mock(InstanceIdProvider.class);
        when(idProvider.getInstanceId()).thenReturn(LOCAL);
        consumer = new EventConsumerService(mapper, dispatcher, idProvider, registry);
    }

    private String json(TreeMutationEvent e) throws Exception {
        return mapper.writeValueAsString(e);
    }

    private TreeMutationEvent peerCreate(long seq) {
        return TreeMutationEvent.builder()
                .eventId("e-" + seq).instanceId(PEER).sequence(seq).occurredAt(T)
                .iceUser("u").operationType(OperationType.CREATE)
                .payload(new CreatePayload(100L + seq, 1L, "N", "Folder", T, "u"))
                .build();
    }

    @Test
    void happy_path_dispatches_and_increments_consumed_counter() throws Exception {
        consumer.processPayload(json(peerCreate(1L)));

        ArgumentCaptor<TreeMutationEvent> cap = ArgumentCaptor.forClass(TreeMutationEvent.class);
        verify(dispatcher).dispatch(cap.capture());
        assertThat(cap.getValue().getOperationType()).isEqualTo(OperationType.CREATE);
        assertThat(registry.counter("itemtree.event.consumed", "op", "CREATE").count()).isOne();
    }

    @Test
    void self_echo_is_dropped_and_self_dropped_counter_incremented() throws Exception {
        TreeMutationEvent self = TreeMutationEvent.builder()
                .eventId("e").instanceId(LOCAL).sequence(1L).occurredAt(T)
                .iceUser("u").operationType(OperationType.UPDATE)
                .payload(new UpdatePayload(100L, T, "u")).build();
        consumer.processPayload(json(self));

        verifyNoInteractions(dispatcher);
        assertThat(registry.counter("itemtree.event.self_dropped").count()).isOne();
        assertThat(registry.counter("itemtree.event.consumed", "op", "UPDATE").count()).isZero();
    }

    @Test
    void malformed_json_increments_deserialize_failure_and_does_not_throw() {
        consumer.processPayload("{ not valid json");

        verifyNoInteractions(dispatcher);
        assertThat(registry.counter("itemtree.event.consume.deserialize.failure").count()).isOne();
    }

    @Test
    void dispatch_failure_increments_apply_failure_and_does_not_throw() throws Exception {
        willThrow(new RuntimeException("boom")).given(dispatcher).dispatch(any());
        consumer.processPayload(json(peerCreate(1L)));

        assertThat(registry.counter("itemtree.event.consume.apply.failure").count()).isOne();
        assertThat(registry.counter("itemtree.event.consumed", "op", "CREATE").count()).isZero();
    }

    @Test
    void sequence_gap_increments_counter_once_per_gap() throws Exception {
        consumer.processPayload(json(peerCreate(1L)));
        consumer.processPayload(json(peerCreate(2L)));
        consumer.processPayload(json(peerCreate(5L))); // gap between 2 and 5

        assertThat(registry.counter("itemtree.event.sequence.gap").count()).isOne();
        assertThat(registry.counter("itemtree.event.consumed", "op", "CREATE").count()).isEqualTo(3.0);
    }

    @Test
    void first_event_from_an_instance_is_not_a_gap() throws Exception {
        consumer.processPayload(json(peerCreate(42L))); // starting at 42, not 1
        assertThat(registry.counter("itemtree.event.sequence.gap").count()).isZero();
    }

    @Test
    void out_of_order_or_duplicate_sequence_does_not_count_as_gap() throws Exception {
        // Out-of-order or duplicate (next <= last) is not a gap — gap is strictly next > last + 1.
        consumer.processPayload(json(peerCreate(5L)));
        consumer.processPayload(json(peerCreate(3L)));
        consumer.processPayload(json(peerCreate(5L)));
        assertThat(registry.counter("itemtree.event.sequence.gap").count()).isZero();
    }

    @Test
    void null_payload_throws_NPE() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> consumer.processPayload(null))
                .isInstanceOf(NullPointerException.class);
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests EventConsumerServiceTest`
Expected: COMPILATION FAILURE.

- [ ] **Step 3: Create `EventConsumerService`**

Create `src/main/java/com/myxcomp/ice/xtree/messaging/EventConsumerService.java`:

```java
package com.myxcomp.ice.xtree.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxcomp.ice.xtree.common.InstanceIdProvider;
import com.myxcomp.ice.xtree.messaging.event.TreeMutationEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Entry point for inbound {@link TreeMutationEvent}s (design §6).
 *
 * <p>Single public method {@link #processPayload(String)} accepts a JSON-encoded envelope
 * and:
 * <ol>
 *   <li>Deserialises with the injected {@link ObjectMapper}.</li>
 *   <li>Drops the event if its {@code instanceId} matches the local instance (self-echo).</li>
 *   <li>Tracks the per-source sequence number to surface gaps as a Micrometer counter.</li>
 *   <li>Delegates to {@link EventDispatcher} for the actual cache mutation.</li>
 * </ol>
 * Every failure path (parse, dispatch) emits a metric and logs at WARN. The method never
 * throws — a malformed or apply-failing message must not poison the inbound stream.
 *
 * <p>In Phase B, the {@code prod}-profile {@code MessagingStarter} wires this method as the
 * body of a {@code MessageListener.onMessage(Message)} adapter. In Phase A the loopback
 * starter subscribes the same method directly to the in-memory bus.
 */
@Component
public class EventConsumerService {

    private static final Logger log = LoggerFactory.getLogger(EventConsumerService.class);

    private final ObjectMapper objectMapper;
    private final EventDispatcher dispatcher;
    private final InstanceIdProvider instanceIdProvider;
    private final MeterRegistry meterRegistry;

    private final ConcurrentHashMap<String, Long> lastSequenceByInstance = new ConcurrentHashMap<>();

    public EventConsumerService(ObjectMapper objectMapper,
                                EventDispatcher dispatcher,
                                InstanceIdProvider instanceIdProvider,
                                MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.dispatcher = dispatcher;
        this.instanceIdProvider = instanceIdProvider;
        this.meterRegistry = meterRegistry;
    }

    public void processPayload(String payload) {
        Objects.requireNonNull(payload, "payload");

        TreeMutationEvent event;
        try {
            event = objectMapper.readValue(payload, TreeMutationEvent.class);
        } catch (Exception e) {
            meterRegistry.counter("itemtree.event.consume.deserialize.failure").increment();
            log.warn("Failed to deserialize event payload (prefix='{}'): {}",
                    payload.substring(0, Math.min(80, payload.length())), e.toString());
            return;
        }

        if (instanceIdProvider.getInstanceId().equals(event.getInstanceId())) {
            meterRegistry.counter("itemtree.event.self_dropped").increment();
            return;
        }

        trackSequenceGap(event);

        try {
            dispatcher.dispatch(event);
            meterRegistry.counter("itemtree.event.consumed", "op", event.getOperationType().name()).increment();
        } catch (RuntimeException e) {
            meterRegistry.counter("itemtree.event.consume.apply.failure").increment();
            log.warn("Apply failed for event id={} op={}: {}",
                    event.getEventId(), event.getOperationType(), e.toString());
        }
    }

    private void trackSequenceGap(TreeMutationEvent event) {
        String src = event.getInstanceId();
        if (src == null) return;
        long current = event.getSequence();
        Long previous = lastSequenceByInstance.get(src);
        // First event from this source: just record.
        if (previous == null) {
            lastSequenceByInstance.put(src, current);
            return;
        }
        // Gap only when current > previous + 1; reordered / duplicate events are not gaps.
        if (current > previous + 1) {
            meterRegistry.counter("itemtree.event.sequence.gap").increment();
        }
        // Advance only on forward motion.
        if (current > previous) {
            lastSequenceByInstance.put(src, current);
        }
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew test --tests EventConsumerServiceTest`
Expected: PASS.

- [ ] **Step 5: Run full suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/messaging/EventConsumerService.java \
        src/test/java/com/myxcomp/ice/xtree/messaging/EventConsumerServiceTest.java
git commit -m "feat(phase10): EventConsumerService — deserialize, self-echo drop, gap tracking, dispatch"
```

---

### Task 6: `TreeMutationEvent` — defence-in-depth `@JsonIgnoreProperties(ignoreUnknown = true)`

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/messaging/event/TreeMutationEvent.java`

- [ ] **Step 1: Add annotation**

Edit `src/main/java/com/myxcomp/ice/xtree/messaging/event/TreeMutationEvent.java` — add `@JsonIgnoreProperties(ignoreUnknown = true)` at the class level alongside `@Value @Builder @JsonDeserialize(...)`:

```java
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
// ... existing imports ...

@Value
@Builder
@JsonDeserialize(using = TreeMutationEventDeserializer.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public class TreeMutationEvent {
    // ... existing fields ...
}
```

The custom deserializer already drops unknown root fields by reading specific node names, but the annotation makes the intent visible and protects payload subtypes (which use record-default deserialisation) too. Note: payload records also need the annotation for full coverage — add it on each of `CreatePayload`, `UpdatePayload`, `MovePayload`, `RenamePayload`, `DeletePayload`:

```java
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public record CreatePayload(...) implements EventPayload {}
```

Repeat for the other four payload records.

- [ ] **Step 2: Write an end-to-end forward-compat test**

Add to `src/test/java/com/myxcomp/ice/xtree/messaging/event/TreeMutationEventTest.java` inside a new `@Nested class ForwardCompat`:

```java
@Nested
class ForwardCompat {

    @Test
    void unknown_envelope_fields_are_ignored() throws Exception {
        String json = """
                {"eventId":"e","instanceId":"i","sequence":1,
                 "occurredAt":"2026-05-13T14:30:00Z","iceUser":"alice",
                 "futureField":"someValue","operationType":"UPDATE",
                 "payload":{"itemTreeId":1,"lastUpdate":"2026-05-13T14:30:00Z","lastUpdateUser":"alice"}}
                """;
        TreeMutationEvent restored = mapper.readValue(json, TreeMutationEvent.class);
        assertThat(restored.getOperationType()).isEqualTo(OperationType.UPDATE);
    }

    @Test
    void unknown_payload_fields_are_ignored() throws Exception {
        String json = """
                {"eventId":"e","instanceId":"i","sequence":1,
                 "occurredAt":"2026-05-13T14:30:00Z","iceUser":"alice","operationType":"UPDATE",
                 "payload":{"itemTreeId":1,"lastUpdate":"2026-05-13T14:30:00Z",
                            "lastUpdateUser":"alice","newField":"x"}}
                """;
        TreeMutationEvent restored = mapper.readValue(json, TreeMutationEvent.class);
        assertThat(((UpdatePayload) restored.getPayload()).itemTreeId()).isEqualTo(1L);
    }
}
```

- [ ] **Step 3: Run the tests**

Run: `./gradlew test --tests TreeMutationEventTest`
Expected: PASS (annotations + existing custom deserializer combine to allow unknown fields).

- [ ] **Step 4: Run full suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/messaging/event/TreeMutationEvent.java \
        src/main/java/com/myxcomp/ice/xtree/messaging/event/payload/ \
        src/test/java/com/myxcomp/ice/xtree/messaging/event/TreeMutationEventTest.java
git commit -m "feat(phase10): @JsonIgnoreProperties(ignoreUnknown=true) on envelope + payloads"
```

---

### Task 7: `InMemoryEventBus` (Phase A dev stub)

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/messaging/dev/InMemoryEventBus.java`
- Create: `src/test/java/com/myxcomp/ice/xtree/messaging/dev/InMemoryEventBusTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/myxcomp/ice/xtree/messaging/dev/InMemoryEventBusTest.java`:

```java
package com.myxcomp.ice.xtree.messaging.dev;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryEventBusTest {

    private final InMemoryEventBus bus = new InMemoryEventBus();

    @Test
    void publish_with_no_subscribers_is_a_no_op() {
        assertThatCode(() -> bus.publish("topic", "payload")).doesNotThrowAnyException();
    }

    @Test
    void subscriber_receives_published_payload() {
        List<String> received = new ArrayList<>();
        bus.subscribe("t", received::add);
        bus.publish("t", "hello");
        assertThat(received).containsExactly("hello");
    }

    @Test
    void multiple_subscribers_on_same_topic_all_receive() {
        AtomicInteger a = new AtomicInteger();
        AtomicInteger b = new AtomicInteger();
        bus.subscribe("t", x -> a.incrementAndGet());
        bus.subscribe("t", x -> b.incrementAndGet());
        bus.publish("t", "x");
        assertThat(a.get()).isOne();
        assertThat(b.get()).isOne();
    }

    @Test
    void subscriber_on_other_topic_does_not_receive() {
        AtomicInteger a = new AtomicInteger();
        bus.subscribe("other", x -> a.incrementAndGet());
        bus.publish("t", "x");
        assertThat(a.get()).isZero();
    }

    @Test
    void throwing_subscriber_does_not_prevent_other_subscribers_or_propagate() {
        AtomicInteger reached = new AtomicInteger();
        bus.subscribe("t", x -> { throw new RuntimeException("boom"); });
        bus.subscribe("t", x -> reached.incrementAndGet());
        assertThatCode(() -> bus.publish("t", "x")).doesNotThrowAnyException();
        assertThat(reached.get()).isOne();
    }

    @Test
    void null_topic_or_payload_throws_NPE() {
        assertThatThrownBy(() -> bus.publish(null, "x")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> bus.publish("t", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> bus.subscribe(null, x -> {})).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> bus.subscribe("t", null)).isInstanceOf(NullPointerException.class);
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests InMemoryEventBusTest`
Expected: COMPILATION FAILURE.

- [ ] **Step 3: Create `InMemoryEventBus`**

Create `src/main/java/com/myxcomp/ice/xtree/messaging/dev/InMemoryEventBus.java`:

```java
package com.myxcomp.ice.xtree.messaging.dev;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Phase A in-memory replacement for the Solace topic bus. {@link #publish(String, String)}
 * fans the payload to every subscriber registered under the topic synchronously on the
 * caller's thread. Subscriber exceptions are isolated — one throwing handler does not
 * prevent later handlers from running and does not propagate out of {@code publish}.
 *
 * <p>Replaced in Phase B by the real {@code JMSPublisherService} / {@code JMSListenerService}
 * pair wired in the {@code prod} profile.
 */
@Component
@Profile("dev")
public class InMemoryEventBus {

    private static final Logger log = LoggerFactory.getLogger(InMemoryEventBus.class);

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Consumer<String>>> subs =
            new ConcurrentHashMap<>();

    public void publish(String topic, String payload) {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(payload, "payload");
        List<Consumer<String>> handlers = subs.get(topic);
        if (handlers == null || handlers.isEmpty()) return;
        for (Consumer<String> h : handlers) {
            try {
                h.accept(payload);
            } catch (RuntimeException e) {
                log.warn("InMemoryEventBus subscriber on topic '{}' threw: {}", topic, e.toString());
            }
        }
    }

    public void subscribe(String topic, Consumer<String> handler) {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(handler, "handler");
        subs.computeIfAbsent(topic, t -> new CopyOnWriteArrayList<>()).add(handler);
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew test --tests InMemoryEventBusTest`
Expected: PASS.

- [ ] **Step 5: Run full suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/messaging/dev/InMemoryEventBus.java \
        src/test/java/com/myxcomp/ice/xtree/messaging/dev/InMemoryEventBusTest.java
git commit -m "feat(phase10): InMemoryEventBus — dev-profile sync pub/sub stub for Solace"
```

---

### Task 8: `LocalLoopbackEventPublisher` (replaces `NoOpEventPublisher`)

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/messaging/dev/LocalLoopbackEventPublisher.java`
- Create: `src/test/java/com/myxcomp/ice/xtree/messaging/dev/LocalLoopbackEventPublisherTest.java`
- Delete: `src/main/java/com/myxcomp/ice/xtree/messaging/dev/NoOpEventPublisher.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/myxcomp/ice/xtree/messaging/dev/LocalLoopbackEventPublisherTest.java`:

```java
package com.myxcomp.ice.xtree.messaging.dev;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.myxcomp.ice.xtree.config.SolaceProperties;
import com.myxcomp.ice.xtree.messaging.event.OperationType;
import com.myxcomp.ice.xtree.messaging.event.TreeMutationEvent;
import com.myxcomp.ice.xtree.messaging.event.payload.UpdatePayload;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class LocalLoopbackEventPublisherTest {

    private static final String TOPIC = "BC/ICE/ITEMTREE";
    private static final Instant T = Instant.parse("2026-05-17T10:00:00Z");

    private ObjectMapper mapper;
    private InMemoryEventBus bus;
    private MeterRegistry registry;
    private LocalLoopbackEventPublisher publisher;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        bus = mock(InMemoryEventBus.class);
        registry = new SimpleMeterRegistry();
        publisher = new LocalLoopbackEventPublisher(mapper, bus, new SolaceProperties(TOPIC), registry);
    }

    private TreeMutationEvent updateEvent() {
        return TreeMutationEvent.builder()
                .eventId("e").instanceId("local").sequence(1L).occurredAt(T)
                .iceUser("u").operationType(OperationType.UPDATE)
                .payload(new UpdatePayload(100L, T, "u")).build();
    }

    @Test
    void publish_serializes_and_routes_through_bus() {
        publisher.publish(updateEvent());

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(bus).publish(eq(TOPIC), payload.capture());
        assertThat(payload.getValue()).contains("\"operationType\":\"UPDATE\"");
        assertThat(registry.counter("itemtree.event.published", "op", "UPDATE").count()).isOne();
    }

    @Test
    void publish_swallows_serialization_failure_and_increments_counter() throws Exception {
        ObjectMapper failing = mock(ObjectMapper.class);
        given(failing.writeValueAsString(org.mockito.ArgumentMatchers.any()))
                .willThrow(new JsonProcessingException("boom") {});
        LocalLoopbackEventPublisher p2 = new LocalLoopbackEventPublisher(
                failing, bus, new SolaceProperties(TOPIC), registry);

        assertThatCode(() -> p2.publish(updateEvent())).doesNotThrowAnyException();

        verifyNoInteractions(bus);
        assertThat(registry.counter("itemtree.event.publish.serialization_failure").count()).isOne();
        assertThat(registry.counter("itemtree.event.published", "op", "UPDATE").count()).isZero();
    }

    @Test
    void publish_swallows_bus_exception_and_increments_failure_counter() {
        org.mockito.BDDMockito.willThrow(new RuntimeException("bus down"))
                .given(bus).publish(anyString(), anyString());

        assertThatCode(() -> publisher.publish(updateEvent())).doesNotThrowAnyException();

        assertThat(registry.counter("itemtree.event.publish.failure").count()).isOne();
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests LocalLoopbackEventPublisherTest`
Expected: COMPILATION FAILURE.

- [ ] **Step 3: Create `LocalLoopbackEventPublisher`**

Create `src/main/java/com/myxcomp/ice/xtree/messaging/dev/LocalLoopbackEventPublisher.java`:

```java
package com.myxcomp.ice.xtree.messaging.dev;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxcomp.ice.xtree.config.SolaceProperties;
import com.myxcomp.ice.xtree.messaging.EventPublisher;
import com.myxcomp.ice.xtree.messaging.event.TreeMutationEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Phase A {@link EventPublisher}: serialises {@link TreeMutationEvent} to JSON and routes
 * through {@link InMemoryEventBus} on the topic configured by {@link SolaceProperties}.
 *
 * <p>Serialisation and bus dispatch are both best-effort: both failure modes emit a counter
 * and log at WARN, but never propagate to the caller — the DB commit and the local cache
 * update have already succeeded, peer instances reconcile via periodic refresh.
 */
@Component
@Profile("dev")
public class LocalLoopbackEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LocalLoopbackEventPublisher.class);

    private final ObjectMapper objectMapper;
    private final InMemoryEventBus bus;
    private final SolaceProperties solaceProperties;
    private final MeterRegistry meterRegistry;

    public LocalLoopbackEventPublisher(ObjectMapper objectMapper,
                                       InMemoryEventBus bus,
                                       SolaceProperties solaceProperties,
                                       MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.bus = bus;
        this.solaceProperties = solaceProperties;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void publish(TreeMutationEvent event) {
        Objects.requireNonNull(event, "event");

        String json;
        try {
            json = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            meterRegistry.counter("itemtree.event.publish.serialization_failure").increment();
            log.warn("Failed to serialize event id={} op={}: {}",
                    event.getEventId(), event.getOperationType(), e.toString());
            return;
        }

        try {
            bus.publish(solaceProperties.topic(), json);
            meterRegistry.counter("itemtree.event.published", "op", event.getOperationType().name()).increment();
        } catch (RuntimeException e) {
            meterRegistry.counter("itemtree.event.publish.failure").increment();
            log.warn("InMemoryEventBus.publish failed for event id={} op={}: {}",
                    event.getEventId(), event.getOperationType(), e.toString());
        }
    }
}
```

- [ ] **Step 4: Delete `NoOpEventPublisher`**

```bash
rm src/main/java/com/myxcomp/ice/xtree/messaging/dev/NoOpEventPublisher.java
```

(There must be no other `@Profile("dev") EventPublisher` bean — `LocalLoopbackEventPublisher` is now unique.)

- [ ] **Step 5: Run the test**

Run: `./gradlew test --tests LocalLoopbackEventPublisherTest`
Expected: PASS.

- [ ] **Step 6: Run full suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL. (If any existing test references `NoOpEventPublisher`, change it to `LocalLoopbackEventPublisher` or remove the reference — there are none in the current codebase but verify.)

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/messaging/dev/LocalLoopbackEventPublisher.java \
        src/test/java/com/myxcomp/ice/xtree/messaging/dev/LocalLoopbackEventPublisherTest.java
git rm src/main/java/com/myxcomp/ice/xtree/messaging/dev/NoOpEventPublisher.java
git commit -m "feat(phase10): LocalLoopbackEventPublisher replaces NoOpEventPublisher"
```

---

### Task 9: `LocalLoopbackEventConsumerStarter` — wires consumer to bus

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/messaging/dev/LocalLoopbackEventConsumerStarter.java`
- Create: `src/test/java/com/myxcomp/ice/xtree/messaging/dev/LocalLoopbackEventConsumerStarterTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/myxcomp/ice/xtree/messaging/dev/LocalLoopbackEventConsumerStarterTest.java`:

```java
package com.myxcomp.ice.xtree.messaging.dev;

import com.myxcomp.ice.xtree.config.SolaceProperties;
import com.myxcomp.ice.xtree.messaging.EventConsumerService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class LocalLoopbackEventConsumerStarterTest {

    private static final String TOPIC = "BC/ICE/ITEMTREE";

    @Test
    @SuppressWarnings("unchecked")
    void run_subscribes_consumer_processPayload_to_topic() throws Exception {
        InMemoryEventBus bus = mock(InMemoryEventBus.class);
        EventConsumerService consumer = mock(EventConsumerService.class);
        LocalLoopbackEventConsumerStarter starter = new LocalLoopbackEventConsumerStarter(
                bus, consumer, new SolaceProperties(TOPIC));

        starter.run(null);

        ArgumentCaptor<Consumer<String>> handlerCap = ArgumentCaptor.forClass(Consumer.class);
        verify(bus).subscribe(eq(TOPIC), handlerCap.capture());

        // The handler must delegate to processPayload — drive it and verify.
        handlerCap.getValue().accept("PAYLOAD");
        verify(consumer).processPayload("PAYLOAD");
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests LocalLoopbackEventConsumerStarterTest`
Expected: COMPILATION FAILURE.

- [ ] **Step 3: Create `LocalLoopbackEventConsumerStarter`**

Create `src/main/java/com/myxcomp/ice/xtree/messaging/dev/LocalLoopbackEventConsumerStarter.java`:

```java
package com.myxcomp.ice.xtree.messaging.dev;

import com.myxcomp.ice.xtree.config.SolaceProperties;
import com.myxcomp.ice.xtree.messaging.EventConsumerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Phase A counterpart to the Phase B {@code MessagingStarter}. Subscribes
 * {@link EventConsumerService#processPayload(String)} to the in-memory bus on the configured
 * topic. {@code @Order(2)} so it runs after {@code TreeCacheBootstrap @Order(1)} — the cache
 * is populated before any (loopback) events can flow.
 */
@Component
@Order(2)
@Profile("dev")
public class LocalLoopbackEventConsumerStarter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalLoopbackEventConsumerStarter.class);

    private final InMemoryEventBus bus;
    private final EventConsumerService consumer;
    private final SolaceProperties solaceProperties;

    public LocalLoopbackEventConsumerStarter(InMemoryEventBus bus,
                                             EventConsumerService consumer,
                                             SolaceProperties solaceProperties) {
        this.bus = bus;
        this.consumer = consumer;
        this.solaceProperties = solaceProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        String topic = solaceProperties.topic();
        bus.subscribe(topic, consumer::processPayload);
        log.info("LocalLoopbackEventConsumerStarter subscribed to '{}'", topic);
    }
}
```

- [ ] **Step 4: Run the test**

Run: `./gradlew test --tests LocalLoopbackEventConsumerStarterTest`
Expected: PASS.

- [ ] **Step 5: Run full suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/messaging/dev/LocalLoopbackEventConsumerStarter.java \
        src/test/java/com/myxcomp/ice/xtree/messaging/dev/LocalLoopbackEventConsumerStarterTest.java
git commit -m "feat(phase10): LocalLoopbackEventConsumerStarter — Order(2) ApplicationRunner wires consumer to bus"
```

---

### Task 10: `StubConnectionExceptionListener` — Phase 11 scaffolding

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/messaging/dev/StubConnectionExceptionListener.java`
- Create: `src/test/java/com/myxcomp/ice/xtree/messaging/dev/StubConnectionExceptionListenerTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/myxcomp/ice/xtree/messaging/dev/StubConnectionExceptionListenerTest.java`:

```java
package com.myxcomp.ice.xtree.messaging.dev;

import com.myxcomp.ice.xtree.messaging.ConnectionRecoveryListener;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class StubConnectionExceptionListenerTest {

    @Test
    void simulateDisconnect_invokes_onConnectionLost_on_every_listener() {
        ConnectionRecoveryListener a = mock(ConnectionRecoveryListener.class);
        ConnectionRecoveryListener b = mock(ConnectionRecoveryListener.class);
        StubConnectionExceptionListener listener = new StubConnectionExceptionListener();
        listener.addRecoveryListener(a);
        listener.addRecoveryListener(b);

        listener.simulateDisconnect();

        verify(a).onConnectionLost("itemtree");
        verify(b).onConnectionLost("itemtree");
    }

    @Test
    void simulateRecovery_invokes_onConnectionRecovered_on_every_listener() {
        ConnectionRecoveryListener a = mock(ConnectionRecoveryListener.class);
        StubConnectionExceptionListener listener = new StubConnectionExceptionListener();
        listener.addRecoveryListener(a);

        listener.simulateRecovery();

        verify(a).onConnectionRecovered("itemtree");
    }

    @Test
    void throwing_listener_does_not_prevent_other_listeners_from_firing() {
        List<String> calls = new ArrayList<>();
        StubConnectionExceptionListener listener = new StubConnectionExceptionListener();
        listener.addRecoveryListener(new ConnectionRecoveryListener() {
            @Override public void onConnectionLost(String s) { throw new RuntimeException("boom"); }
            @Override public void onConnectionRecovered(String s) { throw new RuntimeException("boom"); }
        });
        listener.addRecoveryListener(new ConnectionRecoveryListener() {
            @Override public void onConnectionLost(String s) { calls.add("lost"); }
            @Override public void onConnectionRecovered(String s) { calls.add("recovered"); }
        });

        assertThatCode(listener::simulateDisconnect).doesNotThrowAnyException();
        assertThatCode(listener::simulateRecovery).doesNotThrowAnyException();
        assertThat(calls).containsExactly("lost", "recovered");
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests StubConnectionExceptionListenerTest`
Expected: COMPILATION FAILURE.

- [ ] **Step 3: Create `StubConnectionExceptionListener`**

Create `src/main/java/com/myxcomp/ice/xtree/messaging/dev/StubConnectionExceptionListener.java`:

```java
package com.myxcomp.ice.xtree.messaging.dev;

import com.myxcomp.ice.xtree.messaging.ConnectionRecoveryListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Phase A scaffolding for the company {@code ConnectionExceptionListener} hook
 * (design §6). Tests drive {@link #simulateDisconnect()} and {@link #simulateRecovery()}
 * directly; Phase 11's {@code ConnectionStateTracker} will register itself via
 * {@link #addRecoveryListener(ConnectionRecoveryListener)}.
 *
 * <p>The serviceName argument is fixed to {@code "itemtree"} — the company listener uses
 * the wrapper's {@code getServiceName()}; here we replicate that identifier verbatim.
 */
@Component
@Profile("dev")
public class StubConnectionExceptionListener {

    private static final Logger log = LoggerFactory.getLogger(StubConnectionExceptionListener.class);
    private static final String SERVICE_NAME = "itemtree";

    private final CopyOnWriteArrayList<ConnectionRecoveryListener> listeners = new CopyOnWriteArrayList<>();

    public void addRecoveryListener(ConnectionRecoveryListener listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
    }

    /** Test helper: emits {@code onConnectionLost(serviceName)} to every registered listener. */
    public void simulateDisconnect() {
        for (ConnectionRecoveryListener l : listeners) {
            try {
                l.onConnectionLost(SERVICE_NAME);
            } catch (RuntimeException e) {
                log.warn("ConnectionRecoveryListener.onConnectionLost threw: {}", e.toString());
            }
        }
    }

    /** Test helper: emits {@code onConnectionRecovered(serviceName)} to every registered listener. */
    public void simulateRecovery() {
        for (ConnectionRecoveryListener l : listeners) {
            try {
                l.onConnectionRecovered(SERVICE_NAME);
            } catch (RuntimeException e) {
                log.warn("ConnectionRecoveryListener.onConnectionRecovered threw: {}", e.toString());
            }
        }
    }
}
```

- [ ] **Step 4: Run the test**

Run: `./gradlew test --tests StubConnectionExceptionListenerTest`
Expected: PASS.

- [ ] **Step 5: Run full suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/messaging/dev/StubConnectionExceptionListener.java \
        src/test/java/com/myxcomp/ice/xtree/messaging/dev/StubConnectionExceptionListenerTest.java
git commit -m "feat(phase10): StubConnectionExceptionListener — Phase 11 scaffolding for ConnectionRecoveryListener"
```

---

### Task 11: End-to-end loopback integration test

**Files:**
- Create: `src/test/java/com/myxcomp/ice/xtree/messaging/MessagingLoopbackIT.java`

- [ ] **Step 1: Write the test**

Create `src/test/java/com/myxcomp/ice/xtree/messaging/MessagingLoopbackIT.java`:

```java
package com.myxcomp.ice.xtree.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dev")
class MessagingLoopbackIT {

    @Autowired private InMemoryEventBus bus;
    @Autowired private SolaceProperties solaceProperties;
    @Autowired private TreeCache cache;
    @Autowired private InstanceIdProvider instanceIdProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private ItemService itemService;

    @Test
    void peer_event_is_applied_to_local_cache() throws Exception {
        long newId = 88_888L;
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

        assertThat(meterRegistry.counter("itemtree.event.published", "op", "CREATE").count())
                .isEqualTo(publishedBefore + 1.0);
        assertThat(meterRegistry.counter("itemtree.event.self_dropped").count())
                .isEqualTo(droppedBefore + 1.0);
    }
}
```

- [ ] **Step 2: Run the test**

Run: `./gradlew test --tests MessagingLoopbackIT`
Expected: PASS. If a stable seeded id like `1L` (root) is not a folder in the test seed, swap to a different folder id — but root is `Folder` per `data.sql`, so this should work.

- [ ] **Step 3: Run full suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/myxcomp/ice/xtree/messaging/MessagingLoopbackIT.java
git commit -m "test(phase10): end-to-end loopback IT — peer event, self-echo drop, ItemService round-trip"
```

---

### Task 12: Update `package-info.java` and `EventPublisher` Javadoc

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/messaging/package-info.java`
- Modify: `src/main/java/com/myxcomp/ice/xtree/messaging/EventPublisher.java`

- [ ] **Step 1: Update messaging package-info**

Edit `src/main/java/com/myxcomp/ice/xtree/messaging/package-info.java`:

```java
/**
 * Publish / consume contracts and dispatch logic for tree-mutation events (design §6).
 *
 * <p>Production-shape (no profile): {@link com.myxcomp.ice.xtree.messaging.EventPublisher},
 * {@link com.myxcomp.ice.xtree.messaging.EventConsumerService},
 * {@link com.myxcomp.ice.xtree.messaging.EventDispatcher},
 * {@link com.myxcomp.ice.xtree.messaging.SequenceGenerator},
 * {@link com.myxcomp.ice.xtree.messaging.ConnectionRecoveryListener}.
 *
 * <p>Phase A stubs live in {@code messaging/dev/} under {@code @Profile("dev")}; Phase B
 * substitutes JMS-backed implementations in a {@code prod}-profile config.
 */
package com.myxcomp.ice.xtree.messaging;
```

- [ ] **Step 2: Update `EventPublisher` Javadoc**

Edit `src/main/java/com/myxcomp/ice/xtree/messaging/EventPublisher.java` — replace the existing class-level Javadoc with:

```java
/**
 * Outbound side of the broadcast contract (design §6).
 *
 * <p>Phase A: {@code LocalLoopbackEventPublisher} (dev profile) serialises the event to JSON
 * and publishes onto {@code InMemoryEventBus}. Phase B: a {@code prod}-profile bean backed by
 * {@code JMSPublisherService.reliablePublish(String)}.
 *
 * <p>Implementations are best-effort: failure is logged and counted but never propagates
 * to the caller. The DB commit and the local cache update have already happened — peer
 * instances reconcile via the next refresh if the broadcast was lost.
 */
```

- [ ] **Step 3: Run full suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/messaging/package-info.java \
        src/main/java/com/myxcomp/ice/xtree/messaging/EventPublisher.java
git commit -m "docs(phase10): update messaging package-info and EventPublisher Javadoc"
```

---

### Task 13: Update `IMPLEMENTATION_NOTES.md`

**Files:**
- Modify: `IMPLEMENTATION_NOTES.md`

- [ ] **Step 1: Mark Phase 10 complete and move ⬅ NEXT marker**

Edit `IMPLEMENTATION_NOTES.md`:

1. Replace the `## Phase 10 — Messaging ⬅ NEXT` heading with `## Phase 10 — Messaging ✅ COMPLETE (2026-05-17)`.
2. Add at the top of that section, after the heading:

```markdown
**Goal achieved:** events flow through the in-memory bus end-to-end; self-echoes are dropped via instanceId match; per-instance sequence gaps are counted; all production-shape components (`EventDispatcher`, `EventConsumerService`, `ConnectionRecoveryListener`) are in place ready for the Phase B `prod`-profile bean wiring.

**Deviations from plan (reviewed and approved):**
- *(record any deviations you took during implementation here)*

**Actual done state:** <N> tests green; `./gradlew clean build` → BUILD SUCCESSFUL.
```

3. On the next phase header `## Phase 11 — Resilience`, add `⬅ NEXT` between the dash and the trailing words: `## Phase 11 — Resilience ⬅ NEXT — implementable in Phase A via stubs`.

- [ ] **Step 2: Verify the markdown still renders cleanly**

Run: `head -50 IMPLEMENTATION_NOTES.md | head -10` and visually scan the phase headers.
Expected: each phase heading uses one of `✅ COMPLETE (date)`, `⬅ NEXT`, or neither — no stale `⬅ NEXT` marker remains on Phase 10.

- [ ] **Step 3: Commit**

```bash
git add IMPLEMENTATION_NOTES.md
git commit -m "docs(phase10): mark Phase 10 complete; move ⬅ NEXT marker to Phase 11"
```

---

### Task 14: Final verification — full suite + manual smoke

**Files:**
- (no edits)

- [ ] **Step 1: Run the full test suite**

Run: `./gradlew clean build`
Expected: BUILD SUCCESSFUL. Total test count = previous (413) + roughly 30 new tests from this phase ≈ 440+. Record the exact number for the deviation-log entry in Task 13.

- [ ] **Step 2: Smoke-test the application**

Run: `./gradlew bootRun &` (or run in another shell), wait ~5 s, then in another terminal:

```bash
curl -s http://localhost:8080/actuator/health
curl -s -H 'X-Ice-User: alice' http://localhost:8080/api/v1/itemtree/tree | head -100
curl -s -X POST -H 'Content-Type: application/json' -H 'X-Ice-User: alice' \
    -d '{"name":"PhaseTenSmoke","type":"Folder","parentId":1}' \
    http://localhost:8080/api/v1/itemtree/items
curl -s 'http://localhost:8080/actuator/prometheus' | grep -E '^itemtree_event_(published|consumed|self_dropped)'
```

Expected:
- health = `{"status":"UP"}`
- `/tree` returns a populated tree
- create succeeds with a JSON body that includes the new id
- prometheus exposition shows `itemtree_event_published_total{op="CREATE",...} 1.0`, `itemtree_event_self_dropped_total ≥ 1.0`, and `itemtree_event_consumed_total{op="CREATE",...}` either at 0 (if the self-echo was dropped before the consumed counter) — which is the expected behaviour for a single-instance loopback.

Kill the bootRun process when done: `kill %1` or equivalent.

- [ ] **Step 3: If everything green, this phase is done**

No additional commit required — Task 13 already moved the ⬅ NEXT marker.

---

## Self-Review Checklist (run by the engineer after completing all tasks)

1. **Spec coverage** — every bullet under "## Phase 10 — Messaging" in `IMPLEMENTATION_NOTES.md` and every component named under design §6 has a matching task or note above. Cross off:
   - [x] `EventPublisher` interface (already in place)
   - [x] `EventConsumerService` with `processPayload(String)`
   - [x] `EventDispatcher` (op → `TreeCache.apply*`)
   - [x] `SequenceGenerator` (already in place)
   - [x] `ConnectionRecoveryListener` interface
   - [x] ObjectMapper config (Spring Boot default + yaml settings + `@JsonIgnoreProperties`)
   - [x] `InMemoryEventBus`
   - [x] `LocalLoopbackEventPublisher` (replaces `NoOpEventPublisher`)
   - [x] `LocalLoopbackEventConsumerStarter` (`ApplicationRunner @Order(2)`)
   - [x] `StubConnectionExceptionListener` with `addRecoveryListener` + sim helpers
   - [x] Round-trip test, self-echo drop test, deserialize-failure test, apply-failure test, isolation of `processPayload`
   - [x] Phase 9 follow-up: `@JsonIgnoreProperties(ignoreUnknown=true)` on envelope + payloads

2. **Placeholder scan** — no `TBD`, no `implement later`, no `similar to Task N`, no narrative steps without code.

3. **Type consistency** — `SolaceProperties.topic()`, `EventPublisher.publish(TreeMutationEvent)`, `EventConsumerService.processPayload(String)`, `EventDispatcher.dispatch(TreeMutationEvent)`, `ConnectionRecoveryListener.onConnectionLost(String)` / `onConnectionRecovered(String)` — these names are used consistently across every task above.

4. **Profile coverage** — every class under `messaging/dev/` has `@Profile("dev")`; no production-shape class has any profile annotation. Verified.

5. **Metric names** — each name used in tests (`itemtree.event.published`, `itemtree.event.consumed`, `itemtree.event.self_dropped`, `itemtree.event.publish.failure`, `itemtree.event.publish.serialization_failure`, `itemtree.event.consume.deserialize.failure`, `itemtree.event.consume.apply.failure`, `itemtree.event.sequence.gap`) matches design §18 exactly.

---

## Out of scope for this phase (Phase 11 and later)

- `ConnectionStateTracker` (the production `ConnectionRecoveryListener` impl) — Phase 11.
- `ReconnectReconciler` (outage duration → delta/full-reload dispatch) — Phase 11.
- `MessagingHealthIndicator` / `itemtree.solace.*` metrics around connection state — Phase 11.
- Real `JMSListenerService` / `JMSPublisherService` wiring — Phase B (Phase 14).
- Multi-instance end-to-end across two `ApplicationContext`s sharing one `InMemoryEventBus` — Phase 13 (e2e).

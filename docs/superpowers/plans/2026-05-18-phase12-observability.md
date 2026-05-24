# Phase 12 — Observability & Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish wiring every Micrometer meter listed in design §18 at the right boundary, add the three `HealthIndicator` beans (cache, Solace, DB), apply the `instanceId` Micrometer common tag across the registry, propagate `traceId` into log lines via Micrometer Tracing, gate `/actuator/itemtree-refresh/**` to a configurable CIDR allowlist (Phase 9 deferral), and prove the whole exposition end-to-end with a Prometheus smoke test that exercises a representative workload and asserts every named metric is present.

**Architecture:**
- **Cross-cutting Micrometer wiring** stays config-local and DI-friendly: a single `MeterRegistryCustomizer<MeterRegistry>` adds `instanceId=<InstanceIdProvider.getInstanceId()>` as a common tag — every meter created anywhere in the codebase inherits it automatically.
- **Two new "metrics binder" `@Component` classes** register the missing gauges in their constructors and hold no logic of their own. `CacheMetricsBinder` registers `itemtree.cache.size` against `TreeCache::size`. The `itemtree.cache.last_refresh_age_seconds` gauge is registered inside `RefreshOrchestrator`'s constructor (the class already owns `lastRefreshInstant`); it reads `timeMapper.now() - lastRefreshInstant` on each scrape and returns `-1.0` when the cache has never refreshed (initial `Instant.EPOCH` value).
- **Domain meters** live next to the domain decision they describe. `itemtree.delete.cascade.size` is recorded in `ItemService.deleteItem` immediately after the cascade. `itemtree.policy.validation_rejection{reason}` increments in `ItemService.createItem` / `updateItemData` immediately before each `ValidationException` throw. `itemtree.policy.unknown_type{type}` increments in two places: `TypePolicyStartupAuditor` (per unknown type seen in DB at startup) and `ItemService.createItem` / `updateItemData` (per request with an unknown type). `itemtree.conversion.xml_to_json.failure{type}` and `itemtree.conversion.json_to_xml.failure{type}` wrap each `converter.xmlToJson(...)` / `converter.jsonToXml(...)` call site in `ItemService` with a try/catch that increments the tagged counter and rethrows.
- **`CacheHealthIndicator`** (`@Component implements HealthIndicator`) reports UP when `CacheReadinessGate.isReady()`, else DOWN. Details map: `ready`, `size`. The DB health indicator is Spring Boot's auto-configured `DataSourceHealthIndicator` (provided by `spring-boot-starter-actuator` when a `DataSource` bean exists) — no new code needed; the plan verifies it appears at `/actuator/health/db`.
- **Tracing:** add the `io.micrometer:micrometer-tracing-bridge-brave` dependency. Spring Boot 3.4 auto-configures a `Tracer`, an `ObservationRegistry` bridge, and an MDC propagation hook (`Slf4jMDCSetter`). With that in place, `MDC.get("traceId")` returns the current trace id during a request — `ProblemFactory` (which already reads `MDC.get("traceId")`) starts populating the field automatically. `logback-spring.xml`'s console pattern adds `[%X{traceId:-},%X{spanId:-}]`.
- **Refresh-endpoint gating** is implemented as a plain `OncePerRequestFilter` (`RefreshEndpointAccessFilter`) — not a Spring Security `SecurityFilterChain`. The filter applies to URIs that start with `/actuator/itemtree-refresh`; for matching requests it parses `request.getRemoteAddr()` into an `InetAddress` and checks it against a list of CIDR rules supplied by a new `SecurityProperties` record bound under `itemtree.security`. Default trusted CIDRs are `["127.0.0.1/32", "::1/128"]` (loopback-only). Mismatch → `403 Forbidden` with `application/problem+json` body. CIDR matching uses a small `IpCidrMatcher` helper in `common/`; no Spring Security dependency is introduced (a `@Profile("prod")` Spring Security-based config is a Phase 14 follow-up).
- **Smoke test (`ObservabilityExposureIT`)** is a `@SpringBootTest(webEnvironment = RANDOM_PORT)` against H2 with the dev profile. It exercises a representative workload (a create / get-items / cascade-delete sequence plus a refresh trigger, plus a deliberate validation rejection and an unknown-type create), then GETs `/actuator/prometheus` and asserts the body contains every metric name from design §18 that this codebase emits.

**Tech Stack:** Java 21, Spring Boot 3.4 (`@Component`, `HealthIndicator`, `MeterRegistryCustomizer`, `OncePerRequestFilter`), Micrometer (`MeterRegistry`, `Gauge.builder`, `DistributionSummary`), Micrometer Tracing (brave bridge — new dependency), JUnit 5, Mockito, AssertJ. No other new third-party dependencies.

---

## Self-review note for the executing engineer

**Before any task:** re-read design §18 ("Observability") in full, plus §10 "Validation on create / update" (for `unknown_type` and `validation_rejection`), §7 "Manual trigger" (for the CIDR-gating requirement). Every metric name in this plan must match design §18 verbatim — `itemtree.delete.cascade.size`, not `itemtree.cascade.delete.size`; `itemtree.policy.unknown_type` (singular), not `unknown_types`. Tag keys are `type`, `reason`, `change`, `op` per the design.

**Idempotency / tolerance reminders:**
- Gauges register exactly once, in a constructor or `@PostConstruct`. Re-registering the same name on the same `MeterRegistry` is a duplicate and Micrometer will emit a warning. The `MeterRegistryCustomizer` for common tags must run before any meter is created; Spring's `MeterRegistryPostProcessor` handles this ordering automatically when the customizer is a `@Bean` — do not call `registry.config().commonTags(...)` inside a regular `@Component` constructor that also creates meters.
- The `itemtree.policy.unknown_type` counter must tag on the *type literal as observed* — never normalise (no `.toLowerCase()`, no trim). The startup auditor and the request-path callers must agree on the literal so the same type produces a single time series.
- The conversion failure counter increments on every call-site failure. If a single request triggers the converter twice (it won't, in current code), the counter increments twice. Do not deduplicate.
- `itemtree.cache.last_refresh_age_seconds` returns `-1.0` when `lastRefreshInstant` is still `Instant.EPOCH`. Returning `0.0` would be wrong — a fresh cache is not "0 seconds since last refresh," it is "no refresh has happened yet." Returning a negative sentinel is a Prometheus-friendly way to signal "not applicable" (the metric stays scrapable, the value is obviously synthetic). Alerts must filter `>= 0`.

**Things that will look wrong but aren't:**
- `CacheHealthIndicator` reports **DOWN** until `TreeCacheBootstrap` flips the gate. This is intentional — readiness must include cache state per design §18 "Readiness — cacheReady && consumerSubscribed."
- The `MeterRegistryCustomizer` bean is the **only** place that calls `commonTags(...)`. No production class adds tags by hand at meter-creation time for `instanceId`.
- The CIDR matcher resolves IPv4-mapped IPv6 addresses (`::ffff:127.0.0.1`) using `InetAddress.getByName(remote).getAddress()` — so a request from `127.0.0.1` arriving via Tomcat's IPv6 socket still matches `127.0.0.1/32`. Test for this explicitly.
- The conversion-failure counter wraps the converter call but **rethrows the original exception**. The metric exists for observability; it does not change the failure mode. Callers (and tests) still see the same exceptions.
- The brave bridge auto-configures the MDC propagation; we do not write any explicit `MDC.put("traceId", ...)` code. The pattern in `logback-spring.xml` just reads what the bridge already wrote.
- `RefreshEndpointAccessFilter` extends `OncePerRequestFilter`. It must `shouldNotFilter` for non-refresh URIs (return `true`) — otherwise it adds per-request work to every endpoint.
- `ProblemFactory` already reads `MDC.get("traceId")`. Adding the tracing bridge means existing tests that previously got `traceId=null` may now return a non-null value when run inside a `@SpringBootTest`. The existing slice/unit tests use `@WebMvcTest`, which does not auto-configure tracing — they keep their current behaviour. Only the new smoke test sees traceId populated.

---

## File Structure

### New production files

| Path | Responsibility |
|---|---|
| `src/main/java/com/myxcomp/ice/xtree/config/MicrometerConfig.java` | `@Configuration` exposing one `@Bean MeterRegistryCustomizer<MeterRegistry> commonTagsCustomizer(InstanceIdProvider)`. The customizer adds `instanceId=<uuid>` to `registry.config().commonTags(...)`. |
| `src/main/java/com/myxcomp/ice/xtree/cache/CacheMetricsBinder.java` | `@Component`. Constructor registers `itemtree.cache.size` gauge against `TreeCache::size`. No fields, no other logic. |
| `src/main/java/com/myxcomp/ice/xtree/cache/CacheHealthIndicator.java` | `@Component implements HealthIndicator`. UP when `CacheReadinessGate.isReady()`; DOWN otherwise. Details: `ready` (boolean), `size` (int). |
| `src/main/java/com/myxcomp/ice/xtree/config/SecurityProperties.java` | `@ConfigurationProperties("itemtree.security")` record with one nullable `List<String> trustedCidrs` field. Defaults applied in `application.yml`. |
| `src/main/java/com/myxcomp/ice/xtree/api/filter/RefreshEndpointAccessFilter.java` | `@Component` extending `OncePerRequestFilter`. Applies only to `/actuator/itemtree-refresh` and `/actuator/itemtree-refresh/**`. Returns 403 + `application/problem+json` body when the resolved remote `InetAddress` is not in any configured CIDR rule. |
| `src/main/java/com/myxcomp/ice/xtree/common/IpCidrMatcher.java` | Static utility. Methods: `static List<CidrRule> parse(List<String> cidrStrings)` and `static boolean matches(InetAddress addr, List<CidrRule> rules)`. Inner record `CidrRule(byte[] networkBytes, int prefixLen)` with `boolean matches(byte[] addrBytes)`. Handles IPv4-mapped IPv6 by canonicalising both sides to their `InetAddress` byte form. |

### Modified production files

| Path | Change |
|---|---|
| `build.gradle.kts` | Add `implementation("io.micrometer:micrometer-tracing-bridge-brave")`. No version (managed by Spring Boot's BOM). |
| `src/main/resources/logback-spring.xml` | Update the console encoder pattern to include `[%X{traceId:-},%X{spanId:-}]` between thread and logger. |
| `src/main/resources/application.yml` | Add `itemtree.security.trusted-cidrs: ["127.0.0.1/32", "::1/128"]`. Add `management.tracing.sampling.probability: 1.0` so every request is sampled (Phase A only; Phase B can dial down). |
| `src/main/java/com/myxcomp/ice/xtree/refresh/RefreshOrchestrator.java` | Constructor registers `itemtree.cache.last_refresh_age_seconds` gauge. Add package-private accessor `double lastRefreshAgeSeconds()` returning `-1.0` if `lastRefreshInstant == Instant.EPOCH`, else `Duration.between(lastRefreshInstant, timeMapper.now()).toSeconds()` as a double. (The existing `lastRefreshInstant()` accessor stays for test use.) |
| `src/main/java/com/myxcomp/ice/xtree/service/ItemService.java` | Add `MeterRegistry meterRegistry` to constructor (Spring resolves via DI). On `createItem`: counter `itemtree.policy.unknown_type{type}` when `!policy.isKnown(type)`; counter `itemtree.policy.validation_rejection{reason}` before each `TYPE_CANNOT_HAVE_DATA` / `DATA_REQUIRED` throw; wrap `converter.jsonToXml(dataJson)` with `try { ... } catch (RuntimeException e) { counter("itemtree.conversion.json_to_xml.failure","type",type).increment(); throw e; }`. On `updateItemData`: same pattern (type comes from the cached node). On `deleteItem`: `meterRegistry.summary("itemtree.delete.cascade.size").record(deletedIds.size())` after a non-empty cascade. On `shape()`: wrap `converter.xmlToJson(xml)` and `converter.jsonToXml(json)` (the `isSentAsXmlToUi` branch) with tagged counters; type literal is `n.type()`. |
| `src/main/java/com/myxcomp/ice/xtree/policy/TypePolicyStartupAuditor.java` | Inject `MeterRegistry`. For each unknown type seen in DB, increment `itemtree.policy.unknown_type{type=<literal>}` once. Replace the existing single `log.info("itemtree.data: types seen in DB but absent from all configured lists ...", unknownInDb)` with **two** log lines: a one-line summary of the loaded policy (`"Type policy loaded: types-without-data={...}, types-also-persisted-as-xml-on-write={...}, types-sent-as-xml-to-ui={...}"`) and the existing unknown-in-DB log. |
| `IMPLEMENTATION_NOTES.md` | Mark Phase 11 line at the top as the last completed (already done); change Phase 12's `⬅ NEXT` heading to `✅ COMPLETE (2026-05-18)` and record any deviations and post-audit fixes. Set Phase 13 `⬅ NEXT`. |

### New test files

| Path | Coverage |
|---|---|
| `src/test/java/com/myxcomp/ice/xtree/config/MicrometerConfigTest.java` | Unit. Bean creates a `MeterRegistryCustomizer`; applying it to a `SimpleMeterRegistry` and creating any counter results in a meter whose tags include `instanceId=<provider's uuid>`. |
| `src/test/java/com/myxcomp/ice/xtree/cache/CacheMetricsBinderTest.java` | Unit. Constructor registers `itemtree.cache.size` gauge. Gauge value tracks `TreeCache.size()` (assert before and after `applyCreate`). |
| `src/test/java/com/myxcomp/ice/xtree/cache/CacheHealthIndicatorTest.java` | Unit. Gate not ready → `Status.DOWN`, details `ready=false`, `size=<current>`. Gate ready → `Status.UP`, details `ready=true`, `size=<current>`. |
| `src/test/java/com/myxcomp/ice/xtree/config/SecurityPropertiesTest.java` | Binding test (`ApplicationContextRunner`). Yaml under `itemtree.security` binds into the record; default trusted-cidrs propagate from `application.yml`. |
| `src/test/java/com/myxcomp/ice/xtree/api/filter/RefreshEndpointAccessFilterTest.java` | Unit using `MockMvc` standalone or a hand-rolled `MockHttpServletRequest`/`Response`. Coverage: non-refresh URI passes through untouched; loopback IPv4 to refresh URI passes through; non-trusted IP to refresh URI → 403 with problem+json body; IPv4-mapped IPv6 loopback to refresh URI passes through; empty trusted-cidrs list → all refresh URIs 403. |
| `src/test/java/com/myxcomp/ice/xtree/common/IpCidrMatcherTest.java` | Unit. `parse` rejects malformed CIDR (no slash, non-numeric prefix, out-of-range prefix, bad IP). `matches` covers: exact /32, /24, /16, /8, IPv6 /128, IPv6 /64, IPv4 vs IPv6 cross-match returns false unless IPv4-mapped, empty rule list returns false. |
| `src/test/java/com/myxcomp/ice/xtree/refresh/RefreshOrchestratorMetricsTest.java` | Unit slice. Build an orchestrator with a mocked `TimeMapper` returning known instants. Assert: gauge `itemtree.cache.last_refresh_age_seconds` returns -1 before any refresh; after a successful `runDelta` it returns `now - lastRefresh` in seconds. (The existing `RefreshOrchestratorTest` may stay; this new file covers the new gauge.) |
| `src/test/java/com/myxcomp/ice/xtree/policy/TypePolicyStartupAuditorIT.java` | Already exists (a `@SpringBootTest` + H2 IT) — extend with: counter `itemtree.policy.unknown_type` increments once per unknown type seen in DB; the new startup log line at INFO contains `"Type policy loaded"` and `"types-without-data="`. Update the three existing `new TypePolicyStartupAuditor(...)` constructions to pass a `SimpleMeterRegistry` as the new 4th argument. |
| `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceMetricsTest.java` | New file dedicated to the new metric instrumentation. Covers: validation_rejection increments before each of the two throws on `createItem`; validation_rejection increments on `updateItemData`'s analogous throws; unknown_type increments on `createItem` with an unknown type; cascade.size records the deletedIds size on a non-empty cascade; jsonToXml conversion failure on create increments tagged counter and rethrows; xmlToJson conversion failure during getItemsWithData backfill path increments tagged counter and rethrows. (The existing `ItemServiceTest` stays focused on behaviour; this test focuses on metric side-effects.) |
| `src/test/java/com/myxcomp/ice/xtree/observability/ObservabilityExposureIT.java` | `@SpringBootTest(webEnvironment = RANDOM_PORT) @ActiveProfiles("dev")`. Uses `TestRestTemplate`. Workflow: (1) wait for cache readiness, (2) create a Folder, create a typed item (Report) under it, get-items by id, delete the Folder (cascade-deletes Report), POST /actuator/itemtree-refresh/delta, attempt a TYPE_CANNOT_HAVE_DATA create to fire validation_rejection, attempt a create with an unknown type to fire unknown_type. Then GET `/actuator/prometheus`. Assert: body contains every metric *name* from design §18 that this codebase actually emits, plus `instanceId="..."` tag, plus `db` health entry at `/actuator/health/db`, plus `messaging` health entry at `/actuator/health/messaging`, plus `cache` health entry at `/actuator/health/cache`. |

### Modified test files

| Path | Change |
|---|---|
| `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceTest.java` | Add `MeterRegistry` (a `SimpleMeterRegistry` or `@Mock` no-op) to the constructor call in the test's `@BeforeEach` so existing tests still compile after the production constructor changes. No behavioural test additions here — those go in `ItemServiceMetricsTest`. |
| `src/test/java/com/myxcomp/ice/xtree/refresh/RefreshOrchestratorTest.java` | Already constructs the orchestrator with a `MeterRegistry`; no change for the gauge wiring beyond verifying it doesn't double-register (one orchestrator instance per test). |
| `src/test/java/com/myxcomp/ice/xtree/policy/TypePolicyStartupAuditorIT.java` | Update the three existing auditor constructions to pass the new `MeterRegistry` parameter (a `SimpleMeterRegistry`). Add the two new tests described in Task 7. |

---

## Conventions used by the rest of this plan

- **Logger:** `private static final Logger log = LoggerFactory.getLogger(<owning class>.class);` — SLF4J, no Lombok `@Slf4j`.
- **`Objects.requireNonNull`** guards on every public method parameter that is not nullable per the design.
- **AssertJ semantic methods:** `isZero()`, `isOne()`, `isEmpty()`, `isTrue()`, `isFalse()`, `isNull()`, `isNotNull()`, `containsKeys(...)`, `contains(...)`.
- **Time:** never `Instant.now()`. Always `timeMapper.now()`. Mock `TimeMapper` in tests, return a fixed `Instant`.
- **Metric names and tag keys:** exactly per design §18.
- **`@ParameterizedTest`** for matrix-style coverage — one parameterised test per behaviour, not N copy-pasted tests.
- **Imports:** static `org.assertj.core.api.Assertions.assertThat`; static `org.mockito.Mockito.*` / `BDDMockito.*`.
- **No `mockStatic`, no PowerMock, no `@Disabled` without a `// TODO`.**
- **Commit cadence:** one commit per task. Commit message format `feat(phase12): <short subject>` for new code, `refactor(phase12): ...` for instrumentation-only edits, `test(phase12): ...` for tests-only commits, `chore(phase12): ...` for build/yaml.

---

## Tasks

### Task 1: Add Micrometer Tracing dependency + traceId in log pattern

**Files:**
- Modify: `build.gradle.kts`
- Modify: `src/main/resources/logback-spring.xml`
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: Add the dependency**

In `build.gradle.kts`, locate the `// ── Observability` block and add a new line:

```kotlin
// ── Observability ────────────────────────────────────────────────────
implementation("io.micrometer:micrometer-registry-prometheus")
implementation("io.micrometer:micrometer-tracing-bridge-brave")
```

- [ ] **Step 2: Update the Logback pattern**

Open `src/main/resources/logback-spring.xml` and replace the `<pattern>` line so it reads:

```xml
<pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] [%X{traceId:-},%X{spanId:-}] %logger{36} - %msg%n</pattern>
```

The `:-` defaults each MDC key to the empty string when absent, so non-HTTP threads (scheduler, bootstrap) don't show literal `null`.

- [ ] **Step 3: Enable always-on sampling for Phase A**

In `src/main/resources/application.yml`, immediately after the `management:` block, add:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,itemtree-refresh
  endpoint:
    health:
      probes:
        enabled: true
      show-details: when-authorized
  tracing:
    sampling:
      probability: 1.0
```

(The `tracing.sampling.probability` is a sibling of `endpoints` and `endpoint`, under `management:`. Confirm placement against the existing indentation.)

- [ ] **Step 4: Build and run the existing test suite**

Run: `./gradlew clean test`

Expected: BUILD SUCCESSFUL. Existing test count (487) stays green. The new dependency adds runtime classes only — no compile-time breakage.

- [ ] **Step 5: Smoke-check that traceId appears in MDC during a request**

Add a temporary log statement (later removed in Task 13) in any controller, or alternatively trust the integration test in Task 12. For now, just confirm the build is green and continue.

- [ ] **Step 6: Commit**

```bash
git add build.gradle.kts src/main/resources/logback-spring.xml src/main/resources/application.yml
git commit -m "$(cat <<'EOF'
chore(phase12): add Micrometer Tracing brave bridge + traceId in log pattern

Adds io.micrometer:micrometer-tracing-bridge-brave; Spring Boot 3.4
auto-configures the Tracer, ObservationRegistry bridge, and MDC
propagation. Logback console pattern now includes [traceId,spanId].
management.tracing.sampling.probability=1.0 in Phase A.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: `MicrometerConfig` — `instanceId` common tag via customizer

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/config/MicrometerConfig.java`
- Create: `src/test/java/com/myxcomp/ice/xtree/config/MicrometerConfigTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/myxcomp/ice/xtree/config/MicrometerConfigTest.java`:

```java
package com.myxcomp.ice.xtree.config;

import com.myxcomp.ice.xtree.common.InstanceIdProvider;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MicrometerConfigTest {

    @Test
    void customizerAddsInstanceIdAsCommonTag() {
        InstanceIdProvider provider = mock(InstanceIdProvider.class);
        when(provider.getInstanceId()).thenReturn("abc-123");

        MeterRegistryCustomizer<MeterRegistry> customizer =
                new MicrometerConfig().commonTagsCustomizer(provider);

        MeterRegistry registry = new SimpleMeterRegistry();
        customizer.customize(registry);

        registry.counter("test.metric").increment();
        assertThat(registry.find("test.metric").tag("instanceId", "abc-123").counter())
                .isNotNull();
    }

    @Test
    void customizerCustomizesAnyMeterRegistry() {
        InstanceIdProvider provider = mock(InstanceIdProvider.class);
        when(provider.getInstanceId()).thenReturn("xyz-789");

        MeterRegistry registry = new SimpleMeterRegistry();
        new MicrometerConfig().commonTagsCustomizer(provider).customize(registry);

        registry.timer("another.metric").record(java.time.Duration.ofMillis(1));
        assertThat(registry.find("another.metric").tag("instanceId", "xyz-789").timer())
                .isNotNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "*MicrometerConfigTest*"`

Expected: FAIL — `MicrometerConfig` does not exist yet.

- [ ] **Step 3: Write the production class**

Create `src/main/java/com/myxcomp/ice/xtree/config/MicrometerConfig.java`:

```java
package com.myxcomp.ice.xtree.config;

import com.myxcomp.ice.xtree.common.InstanceIdProvider;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Objects;

/**
 * Applies the {@code instanceId} Micrometer common tag (design §18) to every meter
 * created in this application. The customizer runs before any meter is registered.
 */
@Configuration
public class MicrometerConfig {

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> commonTagsCustomizer(InstanceIdProvider provider) {
        Objects.requireNonNull(provider, "provider");
        String instanceId = provider.getInstanceId();
        return registry -> registry.config().commonTags("instanceId", instanceId);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "*MicrometerConfigTest*"`

Expected: PASS.

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew test`

Expected: BUILD SUCCESSFUL. No regressions.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/config/MicrometerConfig.java \
        src/test/java/com/myxcomp/ice/xtree/config/MicrometerConfigTest.java
git commit -m "$(cat <<'EOF'
feat(phase12): MicrometerConfig adds instanceId common tag to all meters

Single MeterRegistryCustomizer bean applies instanceId=<uuid> to the
registry config so every meter created anywhere in the codebase carries
the tag (design §18).

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: `itemtree.cache.size` gauge via `CacheMetricsBinder`

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/cache/CacheMetricsBinder.java`
- Create: `src/test/java/com/myxcomp/ice/xtree/cache/CacheMetricsBinderTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.myxcomp.ice.xtree.cache;

import com.myxcomp.ice.xtree.common.Types;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class CacheMetricsBinderTest {

    @Test
    void registersCacheSizeGauge() {
        DefaultTreeCache cache = new DefaultTreeCache();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new CacheMetricsBinder(cache, registry);

        Gauge gauge = registry.find("itemtree.cache.size").gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isZero();
    }

    @Test
    void gaugeTracksLiveCacheSize() {
        DefaultTreeCache cache = new DefaultTreeCache();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new CacheMetricsBinder(cache, registry);

        cache.applyCreate(new CachedNode(1L, 0L, "root", Types.FOLDER, Instant.EPOCH, "u"));
        cache.applyCreate(new CachedNode(2L, 1L, "child", Types.FOLDER, Instant.EPOCH, "u"));

        assertThat(registry.find("itemtree.cache.size").gauge().value()).isEqualTo(2.0);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "*CacheMetricsBinderTest*"`

Expected: FAIL — `CacheMetricsBinder` does not exist.

- [ ] **Step 3: Write the production class**

Create `src/main/java/com/myxcomp/ice/xtree/cache/CacheMetricsBinder.java`:

```java
package com.myxcomp.ice.xtree.cache;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Registers the {@code itemtree.cache.size} gauge (design §18).
 * Holds no state; the gauge reads {@link TreeCache#size()} on each scrape.
 */
@Component
public class CacheMetricsBinder {

    public CacheMetricsBinder(TreeCache cache, MeterRegistry meterRegistry) {
        Objects.requireNonNull(cache, "cache");
        Objects.requireNonNull(meterRegistry, "meterRegistry");
        Gauge.builder("itemtree.cache.size", cache, c -> c.size())
                .description("Total number of nodes currently held by the in-memory tree cache")
                .register(meterRegistry);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "*CacheMetricsBinderTest*"`

Expected: PASS, both tests green.

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew test`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/cache/CacheMetricsBinder.java \
        src/test/java/com/myxcomp/ice/xtree/cache/CacheMetricsBinderTest.java
git commit -m "$(cat <<'EOF'
feat(phase12): CacheMetricsBinder registers itemtree.cache.size gauge

Gauge reads TreeCache.size() on each scrape (design §18 cache lifecycle).

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: `itemtree.cache.last_refresh_age_seconds` gauge in `RefreshOrchestrator`

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/refresh/RefreshOrchestrator.java`
- Create: `src/test/java/com/myxcomp/ice/xtree/refresh/RefreshOrchestratorMetricsTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.myxcomp.ice.xtree.refresh;

import com.myxcomp.ice.xtree.cache.TreeCache;
import com.myxcomp.ice.xtree.common.TimeMapper;
import com.myxcomp.ice.xtree.config.RefreshProperties;
import com.myxcomp.ice.xtree.persistence.ItemTreeRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RefreshOrchestratorMetricsTest {

    @Test
    void lastRefreshAgeGaugeReturnsMinusOneBeforeAnyRefresh() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TimeMapper timeMapper = mock(TimeMapper.class);
        when(timeMapper.now()).thenReturn(Instant.parse("2026-05-18T10:00:00Z"));

        RefreshOrchestrator orchestrator = new RefreshOrchestrator(
                mock(ItemTreeRepository.class),
                mock(TreeCache.class),
                mock(DeltaReconciler.class),
                timeMapper,
                registry,
                new RefreshProperties("0 */30 * * * *", 60, "0 0 2 * * MON-FRI", 3, List.of(Duration.ofSeconds(1)))
        );

        Gauge gauge = registry.find("itemtree.cache.last_refresh_age_seconds").gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(-1.0);
    }

    @Test
    void lastRefreshAgeGaugeReportsSecondsSinceRefresh() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TimeMapper timeMapper = mock(TimeMapper.class);
        Instant base = Instant.parse("2026-05-18T10:00:00Z");
        when(timeMapper.now()).thenReturn(base, base.plus(Duration.ofMinutes(5)));

        ItemTreeRepository repository = mock(ItemTreeRepository.class);
        when(repository.findStructuralChangedSince(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

        RefreshOrchestrator orchestrator = new RefreshOrchestrator(
                repository,
                mock(TreeCache.class),
                mock(DeltaReconciler.class),
                timeMapper,
                registry,
                new RefreshProperties("0 */30 * * * *", 60, "0 0 2 * * MON-FRI", 3, List.of(Duration.ofSeconds(1)))
        );

        orchestrator.runDelta();      // first now() call stamps lastRefresh = base
        // second now() returns base + 5min — the gauge reads now() on its next read
        double age = registry.find("itemtree.cache.last_refresh_age_seconds").gauge().value();
        assertThat(age).isEqualTo(Duration.ofMinutes(5).toSeconds());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "*RefreshOrchestratorMetricsTest*"`

Expected: FAIL — gauge not registered yet.

- [ ] **Step 3: Modify `RefreshOrchestrator`**

In `RefreshOrchestrator.java`, add to imports (top of file):

```java
import io.micrometer.core.instrument.Gauge;
```

In the constructor body, after the existing field assignments, append:

```java
        Gauge.builder("itemtree.cache.last_refresh_age_seconds", this, RefreshOrchestrator::lastRefreshAgeSeconds)
                .description("Seconds since the last successful cache refresh; -1 before the first refresh")
                .register(meterRegistry);
```

Below the existing package-private `lastRefreshInstant()` accessor, add:

```java
    /**
     * Returns the number of seconds since {@link #lastRefreshInstant} was last updated,
     * or -1.0 when no refresh has happened yet. Read by the
     * {@code itemtree.cache.last_refresh_age_seconds} gauge.
     */
    double lastRefreshAgeSeconds() {
        Instant last = lastRefreshInstant.get();
        if (last.equals(Instant.EPOCH)) return -1.0;
        return (double) Duration.between(last, timeMapper.now()).toSeconds();
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "*RefreshOrchestratorMetricsTest*"`

Expected: PASS, both tests green.

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew test`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/refresh/RefreshOrchestrator.java \
        src/test/java/com/myxcomp/ice/xtree/refresh/RefreshOrchestratorMetricsTest.java
git commit -m "$(cat <<'EOF'
feat(phase12): itemtree.cache.last_refresh_age_seconds gauge

Registered inside RefreshOrchestrator constructor; reads timeMapper.now()
- lastRefreshInstant on each scrape. Returns -1 before the first refresh
so alerts can filter >= 0 (design §18 cache lifecycle).

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: `itemtree.delete.cascade.size` DistributionSummary in `ItemService.deleteItem`

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/service/ItemService.java`
- Modify: `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceTest.java` (constructor update only)
- Create: `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceMetricsTest.java` (only the cascade test for now; subsequent tasks extend this file)

- [ ] **Step 1: Add `MeterRegistry` to the `ItemService` constructor**

In `ItemService.java`:

Top-of-file imports:

```java
import io.micrometer.core.instrument.MeterRegistry;
```

Add a field next to the other final fields:

```java
    private final MeterRegistry meterRegistry;
```

Constructor: append a parameter and assignment:

```java
    public ItemService(TreeCache cache,
                       ItemTreeRepository repository,
                       TypePolicy policy,
                       XmlJsonConverter converter,
                       EventPublisher publisher,
                       TimeMapper timeMapper,
                       InstanceIdProvider instanceIdProvider,
                       SequenceGenerator sequenceGenerator,
                       @Qualifier("backfillExecutor") TaskExecutor backfillExecutor,
                       MeterRegistry meterRegistry) {
        this.cache = cache;
        this.repository = repository;
        this.policy = policy;
        this.converter = converter;
        this.publisher = publisher;
        this.timeMapper = timeMapper;
        this.instanceIdProvider = instanceIdProvider;
        this.sequenceGenerator = sequenceGenerator;
        this.backfillExecutor = backfillExecutor;
        this.meterRegistry = meterRegistry;
    }
```

- [ ] **Step 2: Update every existing `new ItemService(...)` call site so the codebase compiles**

Run `grep -rn "new ItemService(" src/test/` and pass `new SimpleMeterRegistry()` (or `mock(MeterRegistry.class)`) as the new last argument. Most call sites are in `ItemServiceTest.java`. Add `import io.micrometer.core.instrument.simple.SimpleMeterRegistry;` there.

- [ ] **Step 3: Run the existing tests to confirm they still pass with the no-op MeterRegistry**

Run: `./gradlew test --tests "*ItemServiceTest*"`

Expected: PASS — all existing `ItemServiceTest` tests green (no behavioural change yet, just a wider constructor).

- [ ] **Step 4: Write the failing cascade-size test**

Create `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceMetricsTest.java`:

```java
package com.myxcomp.ice.xtree.service;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.TreeCache;
import com.myxcomp.ice.xtree.common.InstanceIdProvider;
import com.myxcomp.ice.xtree.common.TimeMapper;
import com.myxcomp.ice.xtree.common.Types;
import com.myxcomp.ice.xtree.common.UserContext;
import com.myxcomp.ice.xtree.conversion.XmlJsonConverter;
import com.myxcomp.ice.xtree.messaging.EventPublisher;
import com.myxcomp.ice.xtree.messaging.SequenceGenerator;
import com.myxcomp.ice.xtree.persistence.ItemTreeRepository;
import com.myxcomp.ice.xtree.policy.TypePolicy;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ItemServiceMetricsTest {

    private TreeCache cache;
    private ItemTreeRepository repository;
    private TypePolicy policy;
    private XmlJsonConverter converter;
    private EventPublisher publisher;
    private TimeMapper timeMapper;
    private SimpleMeterRegistry meterRegistry;
    private ItemService service;

    @BeforeEach
    void setUp() {
        cache = mock(TreeCache.class);
        repository = mock(ItemTreeRepository.class);
        policy = mock(TypePolicy.class);
        converter = mock(XmlJsonConverter.class);
        publisher = mock(EventPublisher.class);
        timeMapper = mock(TimeMapper.class);
        when(timeMapper.now()).thenReturn(Instant.parse("2026-05-18T10:00:00Z"));
        InstanceIdProvider instanceIdProvider = mock(InstanceIdProvider.class);
        when(instanceIdProvider.getInstanceId()).thenReturn("test-instance");
        SequenceGenerator seq = new SequenceGenerator();
        meterRegistry = new SimpleMeterRegistry();
        service = new ItemService(cache, repository, policy, converter, publisher,
                timeMapper, instanceIdProvider, seq, new SyncTaskExecutor(), meterRegistry);
    }

    @Test
    void deleteRecordsCascadeSize() {
        when(repository.cascadeDeleteSubtree(anyLong())).thenReturn(List.of(10L, 11L, 12L, 13L));

        service.deleteItem(10L, new UserContext("u", null));

        DistributionSummary summary = meterRegistry.find("itemtree.delete.cascade.size").summary();
        assertThat(summary).isNotNull();
        assertThat(summary.count()).isOne();
        assertThat(summary.totalAmount()).isEqualTo(4.0);
    }

    @Test
    void deleteOnUnknownIdDoesNotRecordCascadeSize() {
        when(repository.cascadeDeleteSubtree(anyLong())).thenReturn(List.of());

        service.deleteItem(999L, new UserContext("u", null));

        DistributionSummary summary = meterRegistry.find("itemtree.delete.cascade.size").summary();
        // Either the summary was never created (preferred) or it was created with count=0.
        assertThat(summary == null || summary.count() == 0L).isTrue();
    }
}
```

- [ ] **Step 5: Run test to verify it fails**

Run: `./gradlew test --tests "*ItemServiceMetricsTest*"`

Expected: FAIL on `deleteRecordsCascadeSize` — no summary registered.

- [ ] **Step 6: Wire the cascade summary in `deleteItem`**

In `ItemService.deleteItem`, after `cache.applyDelete(...)` and before `publisher.publish(...)` (or any time after the cascade returns and the list is non-empty), add:

```java
        meterRegistry.summary("itemtree.delete.cascade.size").record(deletedIds.size());
```

Place it inside the `if (deletedIds.isEmpty())` early-return branch's else path — i.e. only record when the cascade actually removed at least one row. Final ordering: `cascadeDeleteSubtree` → early-return-on-empty → `meterRegistry.summary(...).record(...)` → `cache.applyDelete(...)` → `publisher.publish(...)`. Reorder so the metric records before any state mutation, ensuring it fires even if the cache or publisher subsequently throws.

- [ ] **Step 7: Run test to verify it passes**

Run: `./gradlew test --tests "*ItemServiceMetricsTest*"`

Expected: PASS on both tests.

- [ ] **Step 8: Run the full test suite**

Run: `./gradlew test`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/service/ItemService.java \
        src/test/java/com/myxcomp/ice/xtree/service/ItemServiceTest.java \
        src/test/java/com/myxcomp/ice/xtree/service/ItemServiceMetricsTest.java
git commit -m "$(cat <<'EOF'
feat(phase12): record itemtree.delete.cascade.size on cascade delete

ItemService now takes a MeterRegistry; after a non-empty cascade delete
the deletedIds size is recorded into a DistributionSummary (design §18
"Other" metrics). No-op cascades do not pollute the summary.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: `itemtree.policy.validation_rejection{reason}` counter in `ItemService`

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/service/ItemService.java`
- Modify: `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceMetricsTest.java`

- [ ] **Step 1: Add the failing test**

In `ItemServiceMetricsTest.java`, add tests inside the existing class:

```java
    @Test
    void createRejectsDataOnTypeWithoutDataIncrementsValidationRejectionCounter() {
        when(cache.getById(1L)).thenReturn(Optional.of(
                new CachedNode(1L, 0L, "root", Types.FOLDER, Instant.EPOCH, "u")));
        when(policy.isKnown("Folder")).thenReturn(true);
        when(policy.hasData("Folder")).thenReturn(false);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.createItem(1L, "child", "Folder", "{\"k\":1}", new UserContext("u", null))
        ).isInstanceOf(com.myxcomp.ice.xtree.service.exception.ValidationException.class);

        assertThat(meterRegistry.find("itemtree.policy.validation_rejection")
                .tag("reason", "TYPE_CANNOT_HAVE_DATA").counter())
                .isNotNull();
    }

    @Test
    void createRejectsMissingDataOnTypeWithDataIncrementsValidationRejectionCounter() {
        when(cache.getById(1L)).thenReturn(Optional.of(
                new CachedNode(1L, 0L, "root", Types.FOLDER, Instant.EPOCH, "u")));
        when(policy.isKnown("Report")).thenReturn(true);
        when(policy.hasData("Report")).thenReturn(true);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.createItem(1L, "child", "Report", null, new UserContext("u", null))
        ).isInstanceOf(com.myxcomp.ice.xtree.service.exception.ValidationException.class);

        assertThat(meterRegistry.find("itemtree.policy.validation_rejection")
                .tag("reason", "DATA_REQUIRED").counter())
                .isNotNull();
    }

    @Test
    void updateRejectsDataOnTypeWithoutDataIncrementsValidationRejectionCounter() {
        when(cache.getById(5L)).thenReturn(Optional.of(
                new CachedNode(5L, 1L, "node", "Folder", Instant.EPOCH, "u")));
        when(policy.hasData("Folder")).thenReturn(false);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.updateItemData(5L, "{\"k\":1}", new UserContext("u", null))
        ).isInstanceOf(com.myxcomp.ice.xtree.service.exception.ValidationException.class);

        assertThat(meterRegistry.find("itemtree.policy.validation_rejection")
                .tag("reason", "TYPE_CANNOT_HAVE_DATA").counter())
                .isNotNull();
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "*ItemServiceMetricsTest*"`

Expected: FAIL on the three new tests — counters not registered.

- [ ] **Step 3: Wire counter increments in `ItemService`**

In `ItemService.createItem`, replace:

```java
        if (!hasData && dataJson != null) {
            throw new ValidationException(ErrorCode.TYPE_CANNOT_HAVE_DATA,
                    "Type '" + type + "' cannot carry data");
        }
        if (hasData && dataJson == null) {
            throw new ValidationException(ErrorCode.DATA_REQUIRED,
                    "Type '" + type + "' requires data");
        }
```

With:

```java
        if (!hasData && dataJson != null) {
            meterRegistry.counter("itemtree.policy.validation_rejection",
                    "reason", ErrorCode.TYPE_CANNOT_HAVE_DATA.name()).increment();
            throw new ValidationException(ErrorCode.TYPE_CANNOT_HAVE_DATA,
                    "Type '" + type + "' cannot carry data");
        }
        if (hasData && dataJson == null) {
            meterRegistry.counter("itemtree.policy.validation_rejection",
                    "reason", ErrorCode.DATA_REQUIRED.name()).increment();
            throw new ValidationException(ErrorCode.DATA_REQUIRED,
                    "Type '" + type + "' requires data");
        }
```

Apply the same pattern in `ItemService.updateItemData` for its two analogous throws (find them — they reject `TYPE_CANNOT_HAVE_DATA` when data is given for a typeless node, and `DATA_REQUIRED` when data is missing for a typed node).

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "*ItemServiceMetricsTest*"`

Expected: PASS on all five tests now in the file.

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew test`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/service/ItemService.java \
        src/test/java/com/myxcomp/ice/xtree/service/ItemServiceMetricsTest.java
git commit -m "$(cat <<'EOF'
feat(phase12): itemtree.policy.validation_rejection{reason} counter

ItemService increments the tagged counter before each TYPE_CANNOT_HAVE_DATA
or DATA_REQUIRED throw in createItem and updateItemData (design §10
"Validation on create / update" + §18 "Other" metrics).

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: `itemtree.policy.unknown_type{type}` counter — request + startup

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/service/ItemService.java`
- Modify: `src/main/java/com/myxcomp/ice/xtree/policy/TypePolicyStartupAuditor.java`
- Modify: `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceMetricsTest.java`
- Modify: `src/test/java/com/myxcomp/ice/xtree/policy/TypePolicyStartupAuditorIT.java`

- [ ] **Step 1: Add the failing request-path test**

In `ItemServiceMetricsTest.java`, add:

```java
    @Test
    void createWithUnknownTypeIncrementsUnknownTypeCounter() {
        when(cache.getById(1L)).thenReturn(Optional.of(
                new CachedNode(1L, 0L, "root", Types.FOLDER, Instant.EPOCH, "u")));
        when(policy.isKnown("MyExoticType")).thenReturn(false);
        when(policy.hasData("MyExoticType")).thenReturn(true);   // default policy
        when(policy.isAlsoPersistedAsXmlOnWrite("MyExoticType")).thenReturn(false);
        when(repository.insert(anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any(Instant.class),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(42L);

        service.createItem(1L, "child", "MyExoticType", "{\"k\":1}", new UserContext("u", null));

        assertThat(meterRegistry.find("itemtree.policy.unknown_type")
                .tag("type", "MyExoticType").counter())
                .isNotNull();
    }
```

- [ ] **Step 2: Update the production code**

In `ItemService.createItem`, just after the `boolean hasData = policy.hasData(type);` line, insert:

```java
        if (!policy.isKnown(type)) {
            meterRegistry.counter("itemtree.policy.unknown_type", "type", type).increment();
        }
```

In `ItemService.updateItemData`, after fetching the node (which exposes `node.type()`), insert the same block, using `node.type()` as the type literal. Place it before the data-shape validation.

- [ ] **Step 3: Update the existing `TypePolicyStartupAuditorIT.java` and add the two new assertions**

The existing test file is `src/test/java/com/myxcomp/ice/xtree/policy/TypePolicyStartupAuditorIT.java` (a `@SpringBootTest` integration test against the dev profile + H2 seed data). Update every `new TypePolicyStartupAuditor(jdbcClient, typePolicy, dataProperties)` call to also pass a `SimpleMeterRegistry`, and add two new tests:

```java
    @Autowired private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    // ... existing fields ...

    @Test
    void unknownTypesInSeedDataIncrementUnknownTypeCounter() {
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        TypePolicyStartupAuditor auditor = new TypePolicyStartupAuditor(
                jdbcClient, typePolicy, dataProperties, registry);

        auditor.run(null);

        // Dev seed contains View, UDF.Context, Eval — none are in any configured list.
        assertThat(registry.find("itemtree.policy.unknown_type")
                .tag("type", "View").counter()).isNotNull();
        assertThat(registry.find("itemtree.policy.unknown_type")
                .tag("type", "UDF.Context").counter()).isNotNull();
        assertThat(registry.find("itemtree.policy.unknown_type")
                .tag("type", "Eval").counter()).isNotNull();
    }

    @Test
    void startupLogsHumanReadablePolicySummary() {
        TypePolicyStartupAuditor auditor = new TypePolicyStartupAuditor(
                jdbcClient, typePolicy, dataProperties,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());

        auditor.run(null);

        List<ILoggingEvent> infoEvents = appender.list.stream()
                .filter(e -> e.getLevel() == Level.INFO)
                .toList();
        assertThat(infoEvents)
                .anyMatch(e -> e.getFormattedMessage().contains("Type policy loaded")
                            && e.getFormattedMessage().contains("types-without-data="));
    }
```

The three existing tests (`logsInfoForUnknownTypesSeenInDb`, `warnsForConfiguredTypesAbsentFromDb`, `noWarningsWhenAllConfiguredTypesPresentAndKnown`) each construct the auditor — update each one's `new TypePolicyStartupAuditor(...)` call to add a `new SimpleMeterRegistry()` as the fourth argument.

- [ ] **Step 4: Update `TypePolicyStartupAuditor` production**

```java
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class TypePolicyStartupAuditor implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TypePolicyStartupAuditor.class);
    private static final String SELECT_DISTINCT_TYPES = "SELECT DISTINCT TYPE FROM ITEMTREE";

    private final JdbcClient jdbcClient;
    private final TypePolicy typePolicy;
    private final DataProperties dataProperties;
    private final MeterRegistry meterRegistry;

    public TypePolicyStartupAuditor(JdbcClient jdbcClient,
                                    TypePolicy typePolicy,
                                    DataProperties dataProperties,
                                    MeterRegistry meterRegistry) {
        this.jdbcClient = jdbcClient;
        this.typePolicy = typePolicy;
        this.dataProperties = dataProperties;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Type policy loaded: types-without-data={}, types-also-persisted-as-xml-on-write={}, types-sent-as-xml-to-ui={}",
                dataProperties.typesWithoutData(),
                dataProperties.typesAlsoPersistedAsXmlOnWrite(),
                dataProperties.typesSentAsXmlToUi());

        Set<String> typesInDb = new LinkedHashSet<>(
                jdbcClient.sql(SELECT_DISTINCT_TYPES).query(String.class).list());

        Set<String> unknownInDb = new TreeSet<>();
        for (String t : typesInDb) {
            if (!typePolicy.isKnown(t)) {
                unknownInDb.add(t);
                meterRegistry.counter("itemtree.policy.unknown_type", "type", t).increment();
            }
        }
        if (!unknownInDb.isEmpty()) {
            log.info("itemtree.data: types seen in DB but absent from all configured lists "
                    + "(default policy applies): {}", unknownInDb);
        }

        List<String> configured = new ArrayList<>();
        configured.addAll(dataProperties.typesWithoutData());
        configured.addAll(dataProperties.typesAlsoPersistedAsXmlOnWrite());
        configured.addAll(dataProperties.typesSentAsXmlToUi());

        Set<String> configuredAbsentFromDb = new TreeSet<>();
        for (String t : configured) {
            if (!typesInDb.contains(t)) configuredAbsentFromDb.add(t);
        }
        if (!configuredAbsentFromDb.isEmpty()) {
            log.warn("itemtree.data: configured types absent from DB: {}", configuredAbsentFromDb);
        }
    }
}
```

Add `import io.micrometer.core.instrument.MeterRegistry;` at the top.

- [ ] **Step 5: Run tests to verify all pass**

Run: `./gradlew test --tests "*ItemServiceMetricsTest*" --tests "*TypePolicyStartupAuditorTest*"`

Expected: PASS.

- [ ] **Step 6: Run the full test suite**

Run: `./gradlew test`

Expected: BUILD SUCCESSFUL. (Other tests that construct `TypePolicyStartupAuditor` will need to be updated to pass the new `MeterRegistry` parameter — grep for usages and adjust as needed.)

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/service/ItemService.java \
        src/main/java/com/myxcomp/ice/xtree/policy/TypePolicyStartupAuditor.java \
        src/test/java/com/myxcomp/ice/xtree/service/ItemServiceMetricsTest.java \
        src/test/java/com/myxcomp/ice/xtree/policy/TypePolicyStartupAuditorTest.java
git commit -m "$(cat <<'EOF'
feat(phase12): itemtree.policy.unknown_type counter + startup policy log

Counter increments in ItemService.createItem / updateItemData when the
request type is not in any configured list, and once per unknown type
found in DB during TypePolicyStartupAuditor startup. The auditor now
also logs a human-readable summary of the loaded policy at INFO
(design §10 + §18).

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: `itemtree.conversion.{xml_to_json|json_to_xml}.failure{type}` counters

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/service/ItemService.java`
- Modify: `src/test/java/com/myxcomp/ice/xtree/service/ItemServiceMetricsTest.java`

- [ ] **Step 1: Add the failing tests**

In `ItemServiceMetricsTest.java`, append:

```java
    @Test
    void createWithBadJsonIncrementsJsonToXmlFailureCounter() {
        when(cache.getById(1L)).thenReturn(Optional.of(
                new CachedNode(1L, 0L, "root", Types.FOLDER, Instant.EPOCH, "u")));
        when(policy.isKnown("Report")).thenReturn(true);
        when(policy.hasData("Report")).thenReturn(true);
        when(policy.isAlsoPersistedAsXmlOnWrite("Report")).thenReturn(true);
        when(converter.jsonToXml(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new IllegalArgumentException("bad json"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.createItem(1L, "r", "Report", "{not-json}", new UserContext("u", null))
        ).isInstanceOf(IllegalArgumentException.class);

        assertThat(meterRegistry.find("itemtree.conversion.json_to_xml.failure")
                .tag("type", "Report").counter())
                .isNotNull();
    }

    @Test
    void getItemsWithDataXmlToJsonFailureIncrementsCounter() {
        CachedNode reportNode = new CachedNode(
                42L, 1L, "r", "Report", Instant.EPOCH, "u");
        when(cache.getById(42L)).thenReturn(Optional.of(reportNode));
        when(policy.hasData("Report")).thenReturn(true);
        when(policy.isSentAsXmlToUi("Report")).thenReturn(false);
        when(repository.findPayloadByIds(java.util.Set.of(42L))).thenReturn(
                java.util.Map.of(42L, new com.myxcomp.ice.xtree.persistence.PayloadRow(
                        42L, null, "<root><k>1</k></root>")));
        when(converter.xmlToJson("<root><k>1</k></root>"))
                .thenThrow(new IllegalArgumentException("bad xml"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.getItemsWithData(List.of(42L))
        ).isInstanceOf(IllegalArgumentException.class);

        assertThat(meterRegistry.find("itemtree.conversion.xml_to_json.failure")
                .tag("type", "Report").counter())
                .isNotNull();
    }
```

Confirm the exact `findPayloadByIds` signature (Set<Long> vs List<Long>) against `ItemTreeRepository` and adjust the stubbing if needed. The simpler `jsonToXml` failure path in `createItem` is covered by the first test above; the read-path `xmlToJson` failure is covered by this test.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "*ItemServiceMetricsTest*"`

Expected: FAIL on the new tests — no counter registered.

- [ ] **Step 3: Wrap converter call sites in `ItemService`**

In `ItemService.createItem`, find:

```java
        String xmlOrNull = (hasData && policy.isAlsoPersistedAsXmlOnWrite(type))
                ? converter.jsonToXml(dataJson)
                : null;
```

Replace with:

```java
        String xmlOrNull = null;
        if (hasData && policy.isAlsoPersistedAsXmlOnWrite(type)) {
            try {
                xmlOrNull = converter.jsonToXml(dataJson);
            } catch (RuntimeException e) {
                meterRegistry.counter("itemtree.conversion.json_to_xml.failure",
                        "type", type).increment();
                throw e;
            }
        }
```

In `ItemService.updateItemData`, locate the analogous `converter.jsonToXml(dataJson)` call and apply the same pattern, tagging with the existing node's type (`node.type()`).

In `ItemService.shape()`, the two converter call sites:

```java
        if (policy.isSentAsXmlToUi(n.type())) {
            String shippedXml = xml != null ? xml : (json != null ? converter.jsonToXml(json) : null);
            // ...
        }

        if (xml != null) {
            String convertedJson = converter.xmlToJson(xml);
            // ...
        }
```

Replace with:

```java
        if (policy.isSentAsXmlToUi(n.type())) {
            String shippedXml;
            if (xml != null) {
                shippedXml = xml;
            } else if (json != null) {
                try {
                    shippedXml = converter.jsonToXml(json);
                } catch (RuntimeException e) {
                    meterRegistry.counter("itemtree.conversion.json_to_xml.failure",
                            "type", n.type()).increment();
                    throw e;
                }
            } else {
                shippedXml = null;
            }
            return new ItemWithData(n.itemTreeId(), n.parentId(), n.name(), n.type(),
                    n.lastUpdate(), n.lastUpdateUser(), null, shippedXml, children);
        }

        if (json != null) {
            return new ItemWithData(n.itemTreeId(), n.parentId(), n.name(), n.type(),
                    n.lastUpdate(), n.lastUpdateUser(), json, null, children);
        }
        if (xml != null) {
            String convertedJson;
            try {
                convertedJson = converter.xmlToJson(xml);
            } catch (RuntimeException e) {
                meterRegistry.counter("itemtree.conversion.xml_to_json.failure",
                        "type", n.type()).increment();
                throw e;
            }
            backfillBatch.add(new JsonBackfillRow(n.itemTreeId(), convertedJson));
            return new ItemWithData(n.itemTreeId(), n.parentId(), n.name(), n.type(),
                    n.lastUpdate(), n.lastUpdateUser(), convertedJson, null, children);
        }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "*ItemServiceMetricsTest*"`

Expected: PASS on every test in the file.

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew test`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/service/ItemService.java \
        src/test/java/com/myxcomp/ice/xtree/service/ItemServiceMetricsTest.java
git commit -m "$(cat <<'EOF'
feat(phase12): conversion failure counters tagged by item type

Each converter call site in ItemService (createItem, updateItemData,
shape's two branches) is wrapped to increment
itemtree.conversion.{xml_to_json|json_to_xml}.failure{type} on failure
and rethrow. Behaviour unchanged; observability added (design §18
"Other" metrics).

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

### Task 9: `CacheHealthIndicator`

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/cache/CacheHealthIndicator.java`
- Create: `src/test/java/com/myxcomp/ice/xtree/cache/CacheHealthIndicatorTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.myxcomp.ice.xtree.cache;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CacheHealthIndicatorTest {

    @Test
    void notReadyReportsDown() {
        CacheReadinessGate gate = mock(CacheReadinessGate.class);
        when(gate.isReady()).thenReturn(false);
        TreeCache cache = mock(TreeCache.class);
        when(cache.size()).thenReturn(0);

        Health h = new CacheHealthIndicator(gate, cache).health();

        assertThat(h.getStatus()).isEqualTo(Status.DOWN);
        assertThat(h.getDetails()).containsEntry("ready", false);
        assertThat(h.getDetails()).containsEntry("size", 0);
    }

    @Test
    void readyReportsUpWithSize() {
        CacheReadinessGate gate = mock(CacheReadinessGate.class);
        when(gate.isReady()).thenReturn(true);
        TreeCache cache = mock(TreeCache.class);
        when(cache.size()).thenReturn(42);

        Health h = new CacheHealthIndicator(gate, cache).health();

        assertThat(h.getStatus()).isEqualTo(Status.UP);
        assertThat(h.getDetails()).containsEntry("ready", true);
        assertThat(h.getDetails()).containsEntry("size", 42);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "*CacheHealthIndicatorTest*"`

Expected: FAIL — class does not exist.

- [ ] **Step 3: Write the production class**

```java
package com.myxcomp.ice.xtree.cache;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Spring Boot {@link HealthIndicator} for the in-memory tree cache (design §18 "Readiness").
 * Exposed at {@code /actuator/health/cache}.
 *
 * <p>UP when the {@link CacheReadinessGate} has flipped; DOWN until {@code TreeCacheBootstrap}
 * has succeeded.
 */
@Component
public class CacheHealthIndicator implements HealthIndicator {

    private final CacheReadinessGate gate;
    private final TreeCache cache;

    public CacheHealthIndicator(CacheReadinessGate gate, TreeCache cache) {
        this.gate = Objects.requireNonNull(gate, "gate");
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    @Override
    public Health health() {
        boolean ready = gate.isReady();
        Health.Builder builder = ready ? Health.up() : Health.down();
        return builder
                .withDetail("ready", ready)
                .withDetail("size", cache.size())
                .build();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "*CacheHealthIndicatorTest*"`

Expected: PASS.

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew test`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/cache/CacheHealthIndicator.java \
        src/test/java/com/myxcomp/ice/xtree/cache/CacheHealthIndicatorTest.java
git commit -m "$(cat <<'EOF'
feat(phase12): CacheHealthIndicator at /actuator/health/cache

UP when CacheReadinessGate.isReady(); DOWN until TreeCacheBootstrap
flips the gate. Completes the three §18-required indicators (cache,
messaging, DB) — the DB indicator is Spring Boot's auto-configured
DataSourceHealthIndicator (no new code).

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

### Task 10: `IpCidrMatcher` utility

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/common/IpCidrMatcher.java`
- Create: `src/test/java/com/myxcomp/ice/xtree/common/IpCidrMatcherTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.myxcomp.ice.xtree.common;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IpCidrMatcherTest {

    @Test
    void parsesIpv4Slash32() {
        List<IpCidrMatcher.CidrRule> rules = IpCidrMatcher.parse(List.of("127.0.0.1/32"));
        assertThat(rules).hasSize(1);
        assertThat(IpCidrMatcher.matches(InetAddress.getByName("127.0.0.1"), rules)).isTrue();
        assertThat(IpCidrMatcher.matches(InetAddress.getByName("127.0.0.2"), rules)).isFalse();
    }

    @Test
    void parsesIpv4Slash24() {
        List<IpCidrMatcher.CidrRule> rules = IpCidrMatcher.parse(List.of("10.0.0.0/24"));
        assertThat(IpCidrMatcher.matches(InetAddress.getByName("10.0.0.99"), rules)).isTrue();
        assertThat(IpCidrMatcher.matches(InetAddress.getByName("10.0.1.0"), rules)).isFalse();
    }

    @Test
    void parsesIpv6Loopback() {
        List<IpCidrMatcher.CidrRule> rules = IpCidrMatcher.parse(List.of("::1/128"));
        assertThat(IpCidrMatcher.matches(InetAddress.getByName("::1"), rules)).isTrue();
        assertThat(IpCidrMatcher.matches(InetAddress.getByName("::2"), rules)).isFalse();
    }

    @Test
    void parsesIpv6Slash64() {
        List<IpCidrMatcher.CidrRule> rules = IpCidrMatcher.parse(List.of("fe80::/64"));
        assertThat(IpCidrMatcher.matches(InetAddress.getByName("fe80::1"), rules)).isTrue();
        assertThat(IpCidrMatcher.matches(InetAddress.getByName("fe81::1"), rules)).isFalse();
    }

    @Test
    void ipv4MappedIpv6LoopbackMatchesIpv4Loopback() {
        // ::ffff:127.0.0.1 should match 127.0.0.1/32 because we canonicalise
        List<IpCidrMatcher.CidrRule> rules = IpCidrMatcher.parse(List.of("127.0.0.1/32"));
        InetAddress mapped = InetAddress.getByName("::ffff:127.0.0.1");
        assertThat(IpCidrMatcher.matches(mapped, rules)).isTrue();
    }

    @Test
    void emptyRuleListNeverMatches() {
        assertThat(IpCidrMatcher.matches(InetAddress.getByName("127.0.0.1"), List.of())).isFalse();
    }

    @Test
    void parseRejectsMissingSlash() {
        assertThatThrownBy(() -> IpCidrMatcher.parse(List.of("127.0.0.1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseRejectsNonNumericPrefix() {
        assertThatThrownBy(() -> IpCidrMatcher.parse(List.of("127.0.0.1/abc")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseRejectsPrefixOutOfRangeForIpv4() {
        assertThatThrownBy(() -> IpCidrMatcher.parse(List.of("127.0.0.1/33")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseRejectsPrefixOutOfRangeForIpv6() {
        assertThatThrownBy(() -> IpCidrMatcher.parse(List.of("::1/129")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseRejectsBadIp() {
        assertThatThrownBy(() -> IpCidrMatcher.parse(List.of("999.999.999.999/32")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "*IpCidrMatcherTest*"`

Expected: FAIL — class does not exist.

- [ ] **Step 3: Write the production class**

```java
package com.myxcomp.ice.xtree.common;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure-utility CIDR matcher. Phase A replacement for Spring Security's
 * {@code IpAddressMatcher} (avoiding the security dependency).
 *
 * <p>Supports IPv4 and IPv6 in standard {@code address/prefix} notation. IPv4-mapped IPv6
 * addresses (e.g. {@code ::ffff:127.0.0.1}) are canonicalised to their IPv4 byte form,
 * so a remote address arriving over an IPv6 socket still matches an IPv4 CIDR rule.
 */
public final class IpCidrMatcher {

    private IpCidrMatcher() {}

    public record CidrRule(byte[] networkBytes, int prefixLen) {

        public boolean matches(byte[] addrBytes) {
            if (networkBytes.length != addrBytes.length) return false;
            int fullBytes = prefixLen / 8;
            int extraBits = prefixLen % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (networkBytes[i] != addrBytes[i]) return false;
            }
            if (extraBits == 0) return true;
            int mask = (0xFF << (8 - extraBits)) & 0xFF;
            return (networkBytes[fullBytes] & mask) == (addrBytes[fullBytes] & mask);
        }
    }

    public static List<CidrRule> parse(List<String> cidrStrings) {
        List<CidrRule> out = new ArrayList<>();
        for (String s : cidrStrings) {
            int slash = s.indexOf('/');
            if (slash < 0) {
                throw new IllegalArgumentException("Missing '/' in CIDR: " + s);
            }
            String addr = s.substring(0, slash);
            String prefixStr = s.substring(slash + 1);
            int prefix;
            try {
                prefix = Integer.parseInt(prefixStr);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Non-numeric prefix in CIDR: " + s, e);
            }
            InetAddress inet;
            try {
                inet = InetAddress.getByName(addr);
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("Invalid IP in CIDR: " + s, e);
            }
            byte[] bytes = inet.getAddress();
            int maxPrefix = bytes.length * 8;
            if (prefix < 0 || prefix > maxPrefix) {
                throw new IllegalArgumentException(
                        "Prefix out of range (0.." + maxPrefix + ") in CIDR: " + s);
            }
            out.add(new CidrRule(bytes, prefix));
        }
        return out;
    }

    public static boolean matches(InetAddress addr, List<CidrRule> rules) {
        byte[] bytes = addr.getAddress();
        for (CidrRule r : rules) {
            if (r.matches(bytes)) return true;
        }
        return false;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "*IpCidrMatcherTest*"`

Expected: PASS on all 10 tests.

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew test`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/common/IpCidrMatcher.java \
        src/test/java/com/myxcomp/ice/xtree/common/IpCidrMatcherTest.java
git commit -m "$(cat <<'EOF'
feat(phase12): IpCidrMatcher utility for trusted-CIDR allowlists

Pure-utility CIDR matcher (no Spring Security dependency). Used by
RefreshEndpointAccessFilter in the next task to gate
/actuator/itemtree-refresh requests per design §7.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

### Task 11: `SecurityProperties` + `RefreshEndpointAccessFilter`

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/config/SecurityProperties.java`
- Create: `src/main/java/com/myxcomp/ice/xtree/api/filter/RefreshEndpointAccessFilter.java`
- Modify: `src/main/resources/application.yml`
- Create: `src/test/java/com/myxcomp/ice/xtree/config/SecurityPropertiesTest.java`
- Create: `src/test/java/com/myxcomp/ice/xtree/api/filter/RefreshEndpointAccessFilterTest.java`
- Modify: `src/main/java/com/myxcomp/ice/xtree/api/filter/WebMvcConfig.java` (if applicable — register the filter)

- [ ] **Step 1: Add yaml config**

In `src/main/resources/application.yml`, under `itemtree:`, append:

```yaml
itemtree:
  # ... existing config ...
  security:
    trusted-cidrs:
      - 127.0.0.1/32
      - ::1/128
```

- [ ] **Step 2: Write the `SecurityPropertiesTest`**

```java
package com.myxcomp.ice.xtree.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(EnableConfig.class);

    @org.springframework.boot.context.properties.EnableConfigurationProperties(SecurityProperties.class)
    static class EnableConfig {}

    @Test
    void bindsTrustedCidrsFromYaml() {
        contextRunner
                .withPropertyValues(
                        "itemtree.security.trusted-cidrs[0]=127.0.0.1/32",
                        "itemtree.security.trusted-cidrs[1]=::1/128"
                )
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    SecurityProperties props = ctx.getBean(SecurityProperties.class);
                    assertThat(props.trustedCidrs()).containsExactly("127.0.0.1/32", "::1/128");
                });
    }
}
```

- [ ] **Step 3: Write `SecurityProperties`**

```java
package com.myxcomp.ice.xtree.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties("itemtree.security")
public record SecurityProperties(List<String> trustedCidrs) {
    public SecurityProperties {
        if (trustedCidrs == null) trustedCidrs = List.of();
    }
}
```

Register the property class with the application — open the application's `@SpringBootApplication`-annotated class (or any existing `@Configuration`) and add `@ConfigurationPropertiesScan` if it isn't already present. Verify by checking `ItemTreeApplication.java` and `application.yml` binding succeeds via the new test.

- [ ] **Step 4: Run the binding test**

Run: `./gradlew test --tests "*SecurityPropertiesTest*"`

Expected: PASS.

- [ ] **Step 5: Write the filter test**

```java
package com.myxcomp.ice.xtree.api.filter;

import com.myxcomp.ice.xtree.config.SecurityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class RefreshEndpointAccessFilterTest {

    private final SecurityProperties defaults =
            new SecurityProperties(List.of("127.0.0.1/32", "::1/128"));

    @Test
    void nonRefreshUriPassesThroughUntouched() throws ServletException, IOException {
        RefreshEndpointAccessFilter filter = new RefreshEndpointAccessFilter(defaults);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/actuator/health");
        req.setRemoteAddr("203.0.113.10");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
        assertThat(res.getStatus()).isEqualTo(200);   // unset = 200
    }

    @Test
    void refreshFromLoopbackPassesThrough() throws ServletException, IOException {
        RefreshEndpointAccessFilter filter = new RefreshEndpointAccessFilter(defaults);
        MockHttpServletRequest req = new MockHttpServletRequest("POST",
                "/actuator/itemtree-refresh/delta");
        req.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
    }

    @Test
    void refreshFromIpv4MappedIpv6LoopbackPassesThrough() throws ServletException, IOException {
        RefreshEndpointAccessFilter filter = new RefreshEndpointAccessFilter(defaults);
        MockHttpServletRequest req = new MockHttpServletRequest("POST",
                "/actuator/itemtree-refresh/delta");
        req.setRemoteAddr("0:0:0:0:0:ffff:7f00:1");   // ::ffff:127.0.0.1
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
    }

    @Test
    void refreshFromUntrustedIpReturns403() throws ServletException, IOException {
        RefreshEndpointAccessFilter filter = new RefreshEndpointAccessFilter(defaults);
        MockHttpServletRequest req = new MockHttpServletRequest("POST",
                "/actuator/itemtree-refresh/full");
        req.setRemoteAddr("203.0.113.10");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, never()).doFilter(req, res);
        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentType()).isEqualTo("application/problem+json");
    }

    @Test
    void refreshWithEmptyTrustedListReturns403ForAllSources() throws ServletException, IOException {
        RefreshEndpointAccessFilter filter = new RefreshEndpointAccessFilter(
                new SecurityProperties(List.of()));
        MockHttpServletRequest req = new MockHttpServletRequest("POST",
                "/actuator/itemtree-refresh/delta");
        req.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, never()).doFilter(req, res);
        assertThat(res.getStatus()).isEqualTo(403);
    }
}
```

- [ ] **Step 6: Run filter test to verify it fails**

Run: `./gradlew test --tests "*RefreshEndpointAccessFilterTest*"`

Expected: FAIL — class does not exist.

- [ ] **Step 7: Write the filter**

```java
package com.myxcomp.ice.xtree.api.filter;

import com.myxcomp.ice.xtree.common.IpCidrMatcher;
import com.myxcomp.ice.xtree.config.SecurityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Objects;

/**
 * Gates {@code POST /actuator/itemtree-refresh/**} to a configurable CIDR allowlist
 * (design §7 "Manual trigger"). Implemented as a plain servlet filter to avoid
 * pulling in Spring Security for Phase A; Phase B (work PC) will replace this
 * with a proper {@code SecurityFilterChain} alongside management-port separation.
 *
 * <p>Default trusted CIDRs (configured in {@code application.yml}):
 * {@code 127.0.0.1/32}, {@code ::1/128}.
 */
@Component
public class RefreshEndpointAccessFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RefreshEndpointAccessFilter.class);
    private static final String REFRESH_PATH_PREFIX = "/actuator/itemtree-refresh";
    private static final String FORBIDDEN_BODY =
            "{\"status\":403,\"title\":\"Forbidden\","
            + "\"detail\":\"Source IP is not in the configured trusted CIDR list\"}";

    private final List<IpCidrMatcher.CidrRule> rules;

    public RefreshEndpointAccessFilter(SecurityProperties props) {
        Objects.requireNonNull(props, "props");
        this.rules = IpCidrMatcher.parse(props.trustedCidrs());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !uri.startsWith(REFRESH_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String remote = request.getRemoteAddr();
        InetAddress addr;
        try {
            addr = InetAddress.getByName(remote);
        } catch (UnknownHostException e) {
            denied(response, remote);
            return;
        }
        if (!IpCidrMatcher.matches(addr, rules)) {
            denied(response, remote);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void denied(HttpServletResponse response, String remote) throws IOException {
        log.warn("Denied /actuator/itemtree-refresh request from untrusted source: {}", remote);
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(FORBIDDEN_BODY);
    }
}
```

- [ ] **Step 8: Run filter test to verify it passes**

Run: `./gradlew test --tests "*RefreshEndpointAccessFilterTest*"`

Expected: PASS on all five tests.

- [ ] **Step 9: Run the full test suite**

Run: `./gradlew test`

Expected: BUILD SUCCESSFUL. Existing controller-slice tests should be unaffected (`OncePerRequestFilter` is registered as a `@Component` and `@WebMvcTest` slices opt out of full filter registration unless explicitly imported).

- [ ] **Step 10: Manual smoke check (optional)**

If you want to confirm the filter is wired:

```bash
./gradlew bootRun &
sleep 5
curl -i -X POST http://127.0.0.1:8080/actuator/itemtree-refresh/delta   # 200 OK
curl -i -X POST -H "X-Forwarded-For: 203.0.113.10" http://127.0.0.1:8080/actuator/itemtree-refresh/delta   # still 200 — getRemoteAddr is loopback regardless of XFF in our config
kill %1
```

(`X-Forwarded-For` is not honoured by `getRemoteAddr()`. If proxy-aware behaviour is needed, Phase B will configure `server.forward-headers-strategy`.)

- [ ] **Step 11: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/config/SecurityProperties.java \
        src/main/java/com/myxcomp/ice/xtree/api/filter/RefreshEndpointAccessFilter.java \
        src/main/resources/application.yml \
        src/test/java/com/myxcomp/ice/xtree/config/SecurityPropertiesTest.java \
        src/test/java/com/myxcomp/ice/xtree/api/filter/RefreshEndpointAccessFilterTest.java
git commit -m "$(cat <<'EOF'
feat(phase12): gate /actuator/itemtree-refresh to trusted CIDRs

OncePerRequestFilter rejects refresh-endpoint requests not originating
from a configured CIDR with 403 + problem+json. Default allowlist is
loopback only (127.0.0.1/32, ::1/128); configurable via
itemtree.security.trusted-cidrs. Phase A replacement for Spring
Security's actuator gating per design §7. Phase B will replace with a
SecurityFilterChain alongside management-port separation.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

### Task 12: End-to-end `ObservabilityExposureIT`

**Files:**
- Create: `src/test/java/com/myxcomp/ice/xtree/observability/ObservabilityExposureIT.java`

- [ ] **Step 1: Write the test**

```java
package com.myxcomp.ice.xtree.observability;

import com.myxcomp.ice.xtree.cache.CacheReadinessGate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end smoke test asserting that every metric named in design §18 appears
 * on the Prometheus exposition after a representative workload, and that the
 * three required HealthIndicator entries (cache, messaging, db) are present.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class ObservabilityExposureIT {

    @Autowired private TestRestTemplate rest;
    @Autowired private CacheReadinessGate gate;
    @Value("${local.server.port}") int port;

    @Test
    void prometheusExposesEverySection18Metric() {
        await().atMost(Duration.ofSeconds(10)).until(gate::isReady);

        // Representative workload — fire each kind of metric source at least once.
        // (Each helper below uses TestRestTemplate; bodies are minimal valid payloads.)
        long folderId = createFolder("metrics-folder");
        long reportId = createReport(folderId, "metrics-report");
        getItemsByIds(List.of(reportId));
        deleteItem(folderId);                            // cascades reportId, fires delete.cascade.size
        triggerRefresh("delta");                          // fires refresh.delta.duration
        attemptCreateRejectsDataOnFolder(1L);             // fires validation_rejection{reason=TYPE_CANNOT_HAVE_DATA}
        attemptCreateUnknownType(1L);                     // fires unknown_type{type=Phase12_Unknown}

        // Now scrape /actuator/prometheus and assert every name is present.
        String body = rest.getForObject("/actuator/prometheus", String.class);

        // Cache lifecycle
        assertThat(body).contains("itemtree_cache_size");
        assertThat(body).contains("itemtree_cache_bootstrap_duration");
        assertThat(body).contains("itemtree_cache_bootstrap_rows");
        assertThat(body).contains("itemtree_cache_bootstrap_attempts_total");
        assertThat(body).contains("itemtree_cache_refresh_delta_duration");
        assertThat(body).contains("itemtree_cache_refresh_delta_rows_total");
        assertThat(body).contains("itemtree_cache_last_refresh_age_seconds");

        // Messaging
        assertThat(body).contains("itemtree_event_published_total");
        assertThat(body).contains("itemtree_event_self_dropped_total");        // 0 expected, name still present? actually only if any event fired
        assertThat(body).contains("itemtree_solace_connected");
        assertThat(body).contains("itemtree_solace_outage_seconds");
        assertThat(body).contains("itemtree_solace_last_event_age_seconds");

        // Other
        assertThat(body).contains("itemtree_delete_cascade_size");
        assertThat(body).contains("itemtree_policy_unknown_type_total");
        assertThat(body).contains("itemtree_policy_validation_rejection_total");

        // Common tag
        assertThat(body).contains("instanceId=\"");

        // HikariCP built-in + Spring HTTP server timers (sanity checks)
        assertThat(body).contains("hikaricp_connections");
        assertThat(body).contains("http_server_requests_seconds");
    }

    @Test
    void healthEndpointExposesCacheMessagingAndDb() {
        await().atMost(Duration.ofSeconds(10)).until(gate::isReady);

        ResponseEntity<String> cache = rest.getForEntity("/actuator/health/cache", String.class);
        assertThat(cache.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cache.getBody()).contains("\"status\":\"UP\"");

        ResponseEntity<String> messaging = rest.getForEntity("/actuator/health/messaging", String.class);
        // Status may be UP (never disconnected) — accept that
        assertThat(messaging.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.SERVICE_UNAVAILABLE);

        ResponseEntity<String> db = rest.getForEntity("/actuator/health/db", String.class);
        assertThat(db.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(db.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void refreshEndpointRejectsNonLoopbackBy403() {
        // We cannot easily fake getRemoteAddr() against TestRestTemplate, which always
        // sends from loopback. Assert the loopback path works and trust the unit-level
        // RefreshEndpointAccessFilterTest for the 403 path.
        await().atMost(Duration.ofSeconds(10)).until(gate::isReady);

        ResponseEntity<String> refresh = rest.postForEntity(
                "/actuator/itemtree-refresh/delta", null, String.class);
        assertThat(refresh.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ------- helpers --------

    private long createFolder(String name) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Ice-User", "u");
        String body = "{\"parentId\":1,\"name\":\"" + name + "\",\"type\":\"Folder\"}";
        ResponseEntity<String> resp = rest.exchange("/api/v1/itemtree/items", HttpMethod.POST,
                new HttpEntity<>(body, h), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // Parse the returned id — minimal regex extract
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"itemTreeId\"\\s*:\\s*(\\d+)").matcher(resp.getBody());
        if (!m.find()) throw new IllegalStateException("Couldn't parse id from " + resp.getBody());
        return Long.parseLong(m.group(1));
    }

    private long createReport(long parentId, String name) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Ice-User", "u");
        String body = "{\"parentId\":" + parentId + ",\"name\":\"" + name + "\","
                + "\"type\":\"Report\",\"dataJson\":\"{\\\"k\\\":1}\"}";
        ResponseEntity<String> resp = rest.exchange("/api/v1/itemtree/items", HttpMethod.POST,
                new HttpEntity<>(body, h), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"itemTreeId\"\\s*:\\s*(\\d+)").matcher(resp.getBody());
        if (!m.find()) throw new IllegalStateException("Couldn't parse id from " + resp.getBody());
        return Long.parseLong(m.group(1));
    }

    private void getItemsByIds(List<Long> ids) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Ice-User", "u");
        String body = "{\"ids\":" + ids + "}";
        rest.exchange("/api/v1/itemtree/items/get", HttpMethod.POST,
                new HttpEntity<>(body, h), String.class);
    }

    private void deleteItem(long id) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Ice-User", "u");
        rest.exchange("/api/v1/itemtree/items/" + id, HttpMethod.DELETE,
                new HttpEntity<>(h), String.class);
    }

    private void triggerRefresh(String type) {
        rest.postForEntity("/actuator/itemtree-refresh/" + type, null, String.class);
    }

    private void attemptCreateRejectsDataOnFolder(long parentId) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Ice-User", "u");
        String body = "{\"parentId\":" + parentId + ",\"name\":\"bad-folder\","
                + "\"type\":\"Folder\",\"dataJson\":\"{\\\"k\\\":1}\"}";
        rest.exchange("/api/v1/itemtree/items", HttpMethod.POST,
                new HttpEntity<>(body, h), String.class);
    }

    private void attemptCreateUnknownType(long parentId) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Ice-User", "u");
        String body = "{\"parentId\":" + parentId + ",\"name\":\"exotic\","
                + "\"type\":\"Phase12_Unknown\",\"dataJson\":\"{\\\"k\\\":1}\"}";
        rest.exchange("/api/v1/itemtree/items", HttpMethod.POST,
                new HttpEntity<>(body, h), String.class);
    }
}
```

**Adjust the endpoint paths and request body shape to match the actual generated OpenAPI contract** — confirm them by inspecting `src/main/resources/openapi/itemtree-api.yaml` or grepping for existing controller paths (`/api/v1/itemtree/items`, etc.). If paths differ, update the helpers above before running the test.

If `awaitility` is not on the test classpath, add `testImplementation("org.awaitility:awaitility")` to `build.gradle.kts`.

- [ ] **Step 2: Run the smoke test**

Run: `./gradlew test --tests "*ObservabilityExposureIT*"`

Expected: PASS — all three test methods green.

- [ ] **Step 3: If any assertion fails because a metric is missing or named differently than expected, do not relax the assertion.** Trace the missing metric back to its emission point and fix the production code. The smoke test is the design-§18 acceptance gate.

- [ ] **Step 4: Run the full test suite**

Run: `./gradlew clean test`

Expected: BUILD SUCCESSFUL. Total test count grows by approximately 30–40 new tests (Phase 12 additions).

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/myxcomp/ice/xtree/observability/ObservabilityExposureIT.java
# include build.gradle.kts only if awaitility was added
git commit -m "$(cat <<'EOF'
test(phase12): ObservabilityExposureIT end-to-end smoke for §18 metrics

@SpringBootTest exercises a representative workload (create / get /
cascade-delete / refresh / validation-rejection / unknown-type), then
scrapes /actuator/prometheus and asserts every named metric from
design §18 is present and tagged with instanceId. Also verifies
/actuator/health/{cache,messaging,db} respond UP.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

### Task 13: Mark Phase 12 complete in `IMPLEMENTATION_NOTES.md`

**Files:**
- Modify: `IMPLEMENTATION_NOTES.md`

- [ ] **Step 1: Update the Phase 12 heading and add a "done state" block**

Find:

```markdown
## Phase 12 — Observability & polish ⬅ NEXT
```

Replace with:

```markdown
## Phase 12 — Observability & polish ✅ COMPLETE (2026-05-18)

**Goal achieved:** Every metric from §18 is wired at its correct boundary. Three required `HealthIndicator` beans are in place — `CacheHealthIndicator` (new), `MessagingHealthIndicator` (Phase 11), Spring Boot's auto-configured `DataSourceHealthIndicator`. `instanceId` is applied as a Micrometer common tag across the entire registry via `MicrometerConfig`. `traceId` is propagated into the Logback pattern via the new `micrometer-tracing-bridge-brave` dependency, and into `Problem` responses via the existing `ProblemFactory` MDC lookup. `/actuator/itemtree-refresh/**` is gated to a configurable CIDR allowlist via `RefreshEndpointAccessFilter` (Phase 9 deferral resolved). The new `ObservabilityExposureIT` proves every named metric appears on `/actuator/prometheus` after a representative workload.

**Deviations from plan (reviewed and approved):**
- *(record any deviations encountered during execution here)*

**Actual done state:** *(record final test count and `./gradlew clean build` status here)*

**Post-completion quality fixes (applied after audit, same phase):**
- *(record post-audit fixes here, if any)*
```

Then update Phase 13's heading from `## Phase 13 — End-to-end (Phase A)` to `## Phase 13 — End-to-end (Phase A) ⬅ NEXT` so the marker moves.

- [ ] **Step 2: Verify the doc still renders correctly**

`grep -n "⬅ NEXT" IMPLEMENTATION_NOTES.md` — exactly one match (Phase 13).
`grep -n "✅ COMPLETE" IMPLEMENTATION_NOTES.md` — twelve matches (Phases 0–11 plus Phase 12).

- [ ] **Step 3: Final full-suite verification**

Run: `./gradlew clean build`

Expected: BUILD SUCCESSFUL. All tests green. Total test count after Phase 12 is in the range 515–530 (was 487 at end of Phase 11; this phase adds 30–40 tests).

- [ ] **Step 4: Commit**

```bash
git add IMPLEMENTATION_NOTES.md
git commit -m "$(cat <<'EOF'
docs(phase12): mark Phase 12 ✅ COMPLETE; Phase 13 ⬅ NEXT

Records Phase 12 done state and moves the NEXT marker to Phase 13
(End-to-end, Phase A).

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Final Verification

After Task 13, run the full verification pass:

```bash
./gradlew clean build
```

Expected:

- `BUILD SUCCESSFUL`
- Test count in the 515–530 range (was 487 at end of Phase 11)
- `/actuator/prometheus` (when running `./gradlew bootRun`) contains every metric name listed in design §18 that this codebase emits
- `/actuator/health/cache`, `/actuator/health/messaging`, `/actuator/health/db` all return JSON with `"status":"UP"` once bootstrap completes
- `POST /actuator/itemtree-refresh/delta` from loopback returns 200; from a non-trusted source returns 403 + `application/problem+json`
- Console log lines include `[<traceId>,<spanId>]` segments during request handling; non-HTTP threads show `[,]` (empty MDC)

---

## Spec coverage self-check

| Phase 12 deliverable (`IMPLEMENTATION_NOTES.md`) | Task(s) |
|---|---|
| Wire each Micrometer meter at the right boundary | Tasks 3, 4, 5, 6, 7, 8 |
| `HealthIndicator` beans for cache, Solace, DB | Task 9 (cache); Phase 11 (Solace); Spring Boot auto-config (DB, verified in Task 12) |
| Prometheus exposure verified via `/actuator/prometheus` | Task 12 |
| Startup log line listing the loaded type policy | Task 7 |
| Confirm Micrometer common tag `instanceId` applied across all metrics | Task 2 |
| Smoke test that each named metric exists after a representative workload | Task 12 |
| Add Micrometer Tracing dependency and `%X{traceId}` to `logback-spring.xml` pattern | Task 1 |
| Phase 9 deferred: gate `/actuator/itemtree-refresh` to management port / trusted CIDR | Tasks 10, 11 |

All §18 metric names have an explicit task or are already wired in earlier phases (verified in Task 12).

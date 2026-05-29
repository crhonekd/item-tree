# Phase 22 — Cucumber Acceptance Suite Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a standalone `:acceptance` Gradle subproject of generic Cucumber scenarios that exercise every ITEMTREE REST endpoint over HTTP against a running instance (hostname configurable), sandboxed in a self-cleaning test folder under user `crhonekd`'s home folder.

**Architecture:** A separate Gradle subproject with no compile dependency on the app. Cucumber-JVM on the JUnit Platform drives feature files; RestAssured makes the HTTP calls; AssertJ asserts. A static `Sandbox` holder runs once per suite (`@BeforeAll`/`@AfterAll`): it waits for readiness, resolves the home folder, creates a unique `acceptance-<millis>` folder, and deletes it (cascade) at the end. A pico-container-injected `World` carries per-scenario state. Steps are generic (`I create a {word} named {string}`) so one step backs many item types via Scenario Outline `Examples`.

**Tech Stack:** Java 21, Gradle (plain `java` plugin), Cucumber-JVM 7.20.1 (`cucumber-java`, `cucumber-junit-platform-engine`, `cucumber-picocontainer`), `junit-platform-suite` 1.11.4, RestAssured 5.5.0, AssertJ 3.27.3, Jackson 2.18.2.

---

## File Structure

| File | Responsibility |
|---|---|
| `settings.gradle.kts` *(modify)* | `include("acceptance")` |
| `src/main/resources/db/data.sql` *(modify)* | Add `crhonekd` home folder (id 13) for local runs |
| `acceptance/build.gradle.kts` *(create)* | Subproject deps + `cucumber` task |
| `acceptance/src/test/java/.../acceptance/RunCucumberTest.java` | JUnit Platform suite runner (glue + feature discovery) |
| `acceptance/src/test/java/.../acceptance/support/TestConfig.java` | Resolve baseUrl/user/timeout from sysprops/env |
| `acceptance/src/test/java/.../acceptance/support/ApiClient.java` | RestAssured wrapper (base URI + `X-Ice-User`) |
| `acceptance/src/test/java/.../acceptance/support/Sandbox.java` | Suite-level readiness + home-folder + sandbox folder lifecycle |
| `acceptance/src/test/java/.../acceptance/support/World.java` | Per-scenario shared state (DI) |
| `acceptance/src/test/java/.../acceptance/steps/Hooks.java` | `@BeforeAll`/`@AfterAll`/`@After` lifecycle |
| `acceptance/src/test/java/.../acceptance/steps/ReadSteps.java` | Read endpoints + all assertion steps |
| `acceptance/src/test/java/.../acceptance/steps/ItemSteps.java` | Mutation steps |
| `acceptance/src/test/java/.../acceptance/steps/ErrorSteps.java` | Status + RFC-7807 `errorCode` assertions |
| `acceptance/src/test/resources/features/*.feature` | smoke, item-lifecycle, tree-reads, search, errors |
| `acceptance/src/test/resources/junit-platform.properties` | Cucumber engine config (optional plugins) |

Package root: `com.myxcomp.ice.xtree.acceptance`.

**Prerequisite for every "run" step:** a dev instance must be running locally — `./gradlew bootRun` in a separate terminal (serves `http://localhost:8080`). The acceptance task does **not** start the app.

---

## Task 1: Subproject skeleton, suite runner, seed row (red harness)

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `src/main/resources/db/data.sql`
- Create: `acceptance/build.gradle.kts`
- Create: `acceptance/src/test/java/com/myxcomp/ice/xtree/acceptance/RunCucumberTest.java`
- Create: `acceptance/src/test/resources/features/smoke.feature`
- Create: `acceptance/src/test/resources/junit-platform.properties`

- [ ] **Step 1: Register the subproject**

Edit `settings.gradle.kts` — append after the existing `rootProject.name` line:

```kotlin
include("acceptance")
```

- [ ] **Step 2: Add the `crhonekd` home folder to the H2 seed (local runs only)**

In `src/main/resources/db/data.sql`, inside the section "── 3. Home folders under Users ──", change the `deepuser` row to add a fourth row. Replace:

```sql
  (10, 2, 'testuser1', 'Folder', NULL, 'system', TIMESTAMP '2026-05-01 10:00:00', NULL),
  (11, 2, 'testuser2', 'Folder', NULL, 'system', TIMESTAMP '2026-05-01 10:00:00', NULL),
  (12, 2, 'deepuser',  'Folder', NULL, 'system', TIMESTAMP '2026-05-01 10:00:00', NULL);
```

with:

```sql
  (10, 2, 'testuser1', 'Folder', NULL, 'system', TIMESTAMP '2026-05-01 10:00:00', NULL),
  (11, 2, 'testuser2', 'Folder', NULL, 'system', TIMESTAMP '2026-05-01 10:00:00', NULL),
  (12, 2, 'deepuser',  'Folder', NULL, 'system', TIMESTAMP '2026-05-01 10:00:00', NULL),
  (13, 2, 'crhonekd',  'Folder', NULL, 'system', TIMESTAMP '2026-05-01 10:00:00', NULL);
```

- [ ] **Step 3: Write the acceptance build file**

Create `acceptance/build.gradle.kts`:

```kotlin
plugins {
    java
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

val cucumberVersion = "7.20.1"

dependencies {
    testImplementation("io.cucumber:cucumber-java:$cucumberVersion")
    testImplementation("io.cucumber:cucumber-junit-platform-engine:$cucumberVersion")
    testImplementation("io.cucumber:cucumber-picocontainer:$cucumberVersion")
    testImplementation("org.junit.platform:junit-platform-suite:1.11.4")
    testImplementation("io.rest-assured:rest-assured:5.5.0")
    testImplementation("org.assertj:assertj-core:3.27.3")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
}

// No default unit tests in this module.
tasks.named<Test>("test") {
    enabled = false
}

// Black-box suite: run explicitly against a running instance.
//   ./gradlew :acceptance:cucumber -Ditemtree.baseUrl=http://host:8080
tasks.register<Test>("cucumber") {
    description = "Runs the Cucumber acceptance suite against a running ITEMTREE instance."
    group = "verification"
    useJUnitPlatform()
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    // Forward -Ditemtree.* properties to the test JVM.
    System.getProperties().forEach { key, value ->
        val k = key.toString()
        if (k.startsWith("itemtree.")) systemProperty(k, value.toString())
    }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
    outputs.upToDateWhen { false }
}
```

- [ ] **Step 4: Write the JUnit Platform suite runner**

Create `acceptance/src/test/java/com/myxcomp/ice/xtree/acceptance/RunCucumberTest.java`:

```java
package com.myxcomp.ice.xtree.acceptance;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME;

@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "com.myxcomp.ice.xtree.acceptance")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME, value = "pretty")
public class RunCucumberTest {
}
```

- [ ] **Step 5: Engine config**

Create `acceptance/src/test/resources/junit-platform.properties`:

```properties
cucumber.execution.parallel.enabled=false
cucumber.publish.quiet=true
```

- [ ] **Step 6: Write the smoke feature (intentionally has no glue yet)**

Create `acceptance/src/test/resources/features/smoke.feature`:

```gherkin
Feature: Harness smoke

  Scenario: the target instance is reachable and ready
    When I get the tree
    Then the response status is 200
```

- [ ] **Step 7: Run the suite — expect RED (undefined steps)**

Start the app in another terminal first: `./gradlew bootRun`

Run: `./gradlew :acceptance:cucumber`
Expected: FAIL — Cucumber reports the scenario as undefined/failing with snippet suggestions for `I get the tree` and `the response status is {int}`. This proves the harness compiles and the engine runs.

- [ ] **Step 8: Commit**

```bash
git add settings.gradle.kts src/main/resources/db/data.sql acceptance
git commit -m "test(phase22): acceptance subproject skeleton + suite runner + crhonekd seed"
```

---

## Task 2: Support infrastructure + read/assertion steps (smoke green)

**Files:**
- Create: `acceptance/src/test/java/com/myxcomp/ice/xtree/acceptance/support/TestConfig.java`
- Create: `acceptance/src/test/java/com/myxcomp/ice/xtree/acceptance/support/ApiClient.java`
- Create: `acceptance/src/test/java/com/myxcomp/ice/xtree/acceptance/support/Sandbox.java`
- Create: `acceptance/src/test/java/com/myxcomp/ice/xtree/acceptance/support/World.java`
- Create: `acceptance/src/test/java/com/myxcomp/ice/xtree/acceptance/steps/Hooks.java`
- Create: `acceptance/src/test/java/com/myxcomp/ice/xtree/acceptance/steps/ReadSteps.java`
- Create: `acceptance/src/test/java/com/myxcomp/ice/xtree/acceptance/steps/ErrorSteps.java`

- [ ] **Step 1: TestConfig**

Create `support/TestConfig.java`:

```java
package com.myxcomp.ice.xtree.acceptance.support;

/** Resolves runtime settings from -D system properties, then env vars, then defaults. */
public final class TestConfig {

    private TestConfig() {
    }

    public static String baseUrl() {
        return resolve("itemtree.baseUrl", "ITEMTREE_BASE_URL", "http://localhost:8080");
    }

    public static String user() {
        return resolve("itemtree.user", "ITEMTREE_USER", "crhonekd");
    }

    public static int readinessTimeoutSeconds() {
        return Integer.parseInt(
                resolve("itemtree.readinessTimeoutSeconds", "ITEMTREE_READINESS_TIMEOUT_SECONDS", "60"));
    }

    private static String resolve(String prop, String env, String def) {
        String v = System.getProperty(prop);
        if (v == null || v.isBlank()) {
            v = System.getenv(env);
        }
        return (v == null || v.isBlank()) ? def : v;
    }
}
```

- [ ] **Step 2: ApiClient**

Create `support/ApiClient.java`:

```java
package com.myxcomp.ice.xtree.acceptance.support;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

import java.util.Map;

/** Thin RestAssured wrapper: injects base URI, API base path, and the X-Ice-User header. */
public class ApiClient {

    private static final String API_BASE = "/api/v1/itemtree";

    private final String baseUrl;
    private final String user;

    public ApiClient(String baseUrl, String user) {
        this.baseUrl = baseUrl;
        this.user = user;
    }

    private RequestSpecification req() {
        return RestAssured.given()
                .baseUri(baseUrl)
                .basePath(API_BASE)
                .header("X-Ice-User", user)
                .contentType(ContentType.JSON)
                .accept("application/json, application/problem+json");
    }

    public Response post(String path, Object body) {
        return req().body(body).post(path);
    }

    public Response get(String path) {
        return req().get(path);
    }

    public Response getQuery(String path, Map<String, ?> queryParams) {
        return req().queryParams(queryParams).get(path);
    }

    public Response delete(String path) {
        return req().delete(path);
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String user() {
        return user;
    }
}
```

- [ ] **Step 3: Sandbox (suite-level lifecycle)**

Create `support/Sandbox.java`:

```java
package com.myxcomp.ice.xtree.acceptance.support;

import io.restassured.RestAssured;
import io.restassured.response.Response;

import java.util.Map;

/**
 * One-shot, suite-scoped resources shared across all scenarios:
 * readiness gate, resolved home folder, and the self-cleaning test folder.
 */
public final class Sandbox {

    private static ApiClient api;
    private static long rootId;
    private static boolean ready;

    private Sandbox() {
    }

    public static synchronized void init() {
        if (ready) {
            return;
        }
        api = new ApiClient(TestConfig.baseUrl(), TestConfig.user());
        awaitReadiness();
        long homeId = resolveHomeFolder();
        rootId = createSandboxFolder(homeId);
        ready = true;
    }

    public static synchronized void teardown() {
        if (api != null && ready) {
            api.delete("/items/" + rootId); // cascades to all descendants; tolerate 404
        }
        ready = false;
    }

    public static ApiClient api() {
        return api;
    }

    public static long rootId() {
        return rootId;
    }

    private static void awaitReadiness() {
        String path = "/actuator/health/readiness";
        long deadline = System.currentTimeMillis() + TestConfig.readinessTimeoutSeconds() * 1000L;
        Exception last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                Response r = RestAssured.given().baseUri(TestConfig.baseUrl()).get(path);
                if (r.statusCode() == 200 && "UP".equals(r.jsonPath().getString("status"))) {
                    return;
                }
            } catch (Exception e) {
                last = e;
            }
            sleep();
        }
        throw new IllegalStateException(
                "Instance not READY at " + TestConfig.baseUrl() + path, last);
    }

    private static long resolveHomeFolder() {
        Response r = api.get("/users/" + TestConfig.user() + "/home-folder");
        if (r.statusCode() != 200) {
            throw new IllegalStateException(
                    "Could not resolve home folder for '" + TestConfig.user()
                            + "' (status " + r.statusCode() + "): " + r.getBody().asString());
        }
        return r.jsonPath().getLong("itemTreeId");
    }

    private static long createSandboxFolder(long homeId) {
        String name = "acceptance-" + System.currentTimeMillis();
        Response r = api.post("/items",
                Map.of("parentId", homeId, "name", name, "type", "Folder"));
        if (r.statusCode() != 201) {
            throw new IllegalStateException(
                    "Could not create sandbox folder (status " + r.statusCode() + "): "
                            + r.getBody().asString());
        }
        return r.jsonPath().getLong("itemTreeId");
    }

    private static void sleep() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for readiness", e);
        }
    }
}
```

- [ ] **Step 4: World (per-scenario state)**

Create `support/World.java`:

```java
package com.myxcomp.ice.xtree.acceptance.support;

import io.restassured.response.Response;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Pico-container-injected, scenario-scoped state shared by step classes. */
public class World {

    public final ApiClient api = Sandbox.api();
    public final long sandboxRootId = Sandbox.rootId();

    public Response lastResponse;
    public Long currentItemId;
    public Long currentParentId;

    /** Ids created in this scenario, for failure-safe per-scenario cleanup. */
    public final List<Long> created = new ArrayList<>();
    /** Logical name -> id, so steps can refer back to earlier items/folders. */
    public final Map<String, Long> named = new HashMap<>();
}
```

- [ ] **Step 5: Hooks**

Create `steps/Hooks.java`:

```java
package com.myxcomp.ice.xtree.acceptance.steps;

import com.myxcomp.ice.xtree.acceptance.support.Sandbox;
import com.myxcomp.ice.xtree.acceptance.support.World;
import io.cucumber.java.After;
import io.cucumber.java.AfterAll;
import io.cucumber.java.BeforeAll;

public class Hooks {

    private final World world;

    public Hooks(World world) {
        this.world = world;
    }

    @BeforeAll
    public static void suiteSetup() {
        Sandbox.init();
    }

    @AfterAll
    public static void suiteTeardown() {
        Sandbox.teardown();
    }

    @After
    public void cleanupScenario() {
        // Best-effort: delete anything this scenario created; ignore already-gone (404).
        for (Long id : world.created) {
            world.api.delete("/items/" + id);
        }
    }
}
```

- [ ] **Step 6: ErrorSteps**

Create `steps/ErrorSteps.java`:

```java
package com.myxcomp.ice.xtree.acceptance.steps;

import com.myxcomp.ice.xtree.acceptance.support.World;
import io.cucumber.java.en.Then;

import static org.assertj.core.api.Assertions.assertThat;

public class ErrorSteps {

    private final World world;

    public ErrorSteps(World world) {
        this.world = world;
    }

    @Then("the response status is {int}")
    public void theResponseStatusIs(int expected) {
        assertThat(world.lastResponse.statusCode())
                .as("response body: %s", world.lastResponse.getBody().asString())
                .isEqualTo(expected);
    }

    @Then("the error code is {string}")
    public void theErrorCodeIs(String expected) {
        assertThat(world.lastResponse.jsonPath().getString("errorCode")).isEqualTo(expected);
    }
}
```

- [ ] **Step 7: ReadSteps (read endpoints + all assertion steps)**

Create `steps/ReadSteps.java`:

```java
package com.myxcomp.ice.xtree.acceptance.steps;

import com.myxcomp.ice.xtree.acceptance.support.World;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class ReadSteps {

    private final World world;

    public ReadSteps(World world) {
        this.world = world;
    }

    // ── Read endpoints ────────────────────────────────────────────────

    @When("I get the tree")
    public void iGetTheTree() {
        world.lastResponse = world.api.get("/tree");
    }

    @When("I get the subtree of the sandbox")
    public void iGetTheSubtreeOfTheSandbox() {
        world.lastResponse = world.api.get("/tree/" + world.sandboxRootId + "/subtree");
    }

    @When("I get the full subtree of the sandbox")
    public void iGetTheFullSubtreeOfTheSandbox() {
        world.lastResponse = world.api.get("/tree/" + world.sandboxRootId + "/subtree-full");
    }

    @When("I fetch it")
    public void iFetchIt() {
        world.lastResponse = world.api.post("/items/get", Map.of("ids", List.of(world.currentItemId)));
    }

    @When("I resolve the home folder for {string}")
    public void iResolveTheHomeFolderFor(String userName) {
        world.lastResponse = world.api.get("/users/" + userName + "/home-folder");
    }

    @When("I search for {string}")
    public void iSearchFor(String q) {
        world.lastResponse = world.api.getQuery("/search", Map.of("q", q));
    }

    @When("I search for it by id")
    public void iSearchForItById() {
        world.lastResponse = world.api.getQuery("/search", Map.of("q", String.valueOf(world.currentItemId)));
    }

    // ── Single-node assertions (works on object body or array of nodes) ─

    @Then("the item's name is {string}")
    public void theItemsNameIs(String expected) {
        assertThat(node().get("name")).isEqualTo(expected);
    }

    @Then("the item's type is {string}")
    public void theItemsTypeIs(String expected) {
        assertThat(node().get("type")).isEqualTo(expected);
    }

    @Then("the item's parent is {string}")
    public void theItemsParentIs(String folderName) {
        long expected = world.named.get(folderName);
        assertThat(((Number) node().get("parentId")).longValue()).isEqualTo(expected);
    }

    @Then("the item's path starts with {string}")
    public void theItemsPathStartsWith(String prefix) {
        assertThat((String) node().get("path")).startsWith(prefix);
    }

    // ── Collection assertions (array body) ──────────────────────────────

    @Then("the subtree includes an item named {string}")
    public void theSubtreeIncludesAnItemNamed(String name) {
        assertThat(world.lastResponse.jsonPath().getList("name", String.class)).contains(name);
    }

    @Then("the results include {string}")
    public void theResultsInclude(String name) {
        assertThat(world.lastResponse.jsonPath().getList("name", String.class)).contains(name);
    }

    @Then("every item path starts with {string}")
    public void everyItemPathStartsWith(String prefix) {
        List<String> paths = world.lastResponse.jsonPath().getList("path", String.class);
        assertThat(paths).isNotEmpty().allMatch(p -> p != null && p.startsWith(prefix));
    }

    @Then("the hit {string} has a path")
    public void theHitHasAPath(String name) {
        assertThat((String) hitByName(name).get("path")).startsWith("/");
    }

    @Then("the hit {string} has a non-empty ancestor chain")
    public void theHitHasANonEmptyAncestorChain(String name) {
        assertThat((List<?>) hitByName(name).get("ancestors")).isNotEmpty();
    }

    // ── Delete verification ─────────────────────────────────────────────

    @Then("it no longer exists")
    public void itNoLongerExists() {
        var got = world.api.post("/items/get", Map.of("ids", List.of(world.currentItemId)));
        assertThat(got.jsonPath().getList("$"))
                .as("getItems should omit deleted id %s", world.currentItemId)
                .isEmpty();

        var sub = world.api.get("/tree/" + world.sandboxRootId + "/subtree-full");
        assertThat(sub.jsonPath().getList("itemTreeId", Long.class)).doesNotContain(world.currentItemId);

        world.created.remove(world.currentItemId); // already gone; skip in cleanup
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /** Returns the node under test: the object body, or the matching element of an array body. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> node() {
        Object root = world.lastResponse.jsonPath().get("$");
        if (root instanceof List<?> list) {
            return list.stream()
                    .map(o -> (Map<String, Object>) o)
                    .filter(n -> ((Number) n.get("itemTreeId")).longValue() == world.currentItemId)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("current item not in response: " + world.currentItemId));
        }
        return world.lastResponse.jsonPath().getMap("$");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> hitByName(String name) {
        List<Map<String, Object>> hits = world.lastResponse.jsonPath().getList("$");
        return hits.stream()
                .filter(h -> name.equals(h.get("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no search hit named: " + name));
    }
}
```

- [ ] **Step 8: Run the smoke feature — expect GREEN**

Ensure the app is running (`./gradlew bootRun`).
Run: `./gradlew :acceptance:cucumber`
Expected: PASS — `smoke.feature` is green (`I get the tree` → 200). Sandbox folder is created and deleted (no `acceptance-*` residue under `crhonekd`).

- [ ] **Step 9: Commit**

```bash
git add acceptance/src/test/java
git commit -m "test(phase22): support infra, hooks, read + error steps; smoke green"
```

---

## Task 3: Mutation steps + lifecycle feature

**Files:**
- Create: `acceptance/src/test/java/com/myxcomp/ice/xtree/acceptance/steps/ItemSteps.java`
- Create: `acceptance/src/test/resources/features/item-lifecycle.feature`

- [ ] **Step 1: ItemSteps (all mutation steps + capture helpers)**

Create `steps/ItemSteps.java`:

```java
package com.myxcomp.ice.xtree.acceptance.steps;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxcomp.ice.xtree.acceptance.support.World;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.When;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class ItemSteps {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final World world;

    public ItemSteps(World world) {
        this.world = world;
    }

    @Given("a folder named {string}")
    public void aFolderNamed(String name) {
        var r = world.api.post("/items",
                Map.of("parentId", world.sandboxRootId, "name", name, "type", "Folder"));
        assertThat(r.statusCode()).as("creating folder %s", name).isEqualTo(201);
        long id = r.jsonPath().getLong("itemTreeId");
        world.named.put(name, id);
        world.created.add(id);
    }

    @When("I create a {word} named {string}")
    public void iCreateNamed(String type, String name) {
        world.lastResponse = world.api.post("/items",
                Map.of("parentId", world.sandboxRootId, "name", name, "type", type));
        captureIfCreated(name);
    }

    @When("I create a {word} named {string} with data:")
    public void iCreateNamedWithData(String type, String name, String json) {
        Map<String, Object> body = new HashMap<>();
        body.put("parentId", world.sandboxRootId);
        body.put("name", name);
        body.put("type", type);
        body.put("data", parse(json));
        world.lastResponse = world.api.post("/items", body);
        captureIfCreated(name);
    }

    @When("I create a {word} named {string} in folder {int}")
    public void iCreateInFolder(String type, String name, int parentId) {
        world.lastResponse = world.api.post("/items",
                Map.of("parentId", (long) parentId, "name", name, "type", type));
        captureIfCreated(name);
    }

    @When("I rename it to {string}")
    public void iRenameItTo(String newName) {
        world.lastResponse = world.api.post("/items/" + world.currentItemId + "/rename",
                Map.of("newName", newName));
    }

    @When("I rename item {long} to {string}")
    public void iRenameItemTo(long id, String newName) {
        world.lastResponse = world.api.post("/items/" + id + "/rename", Map.of("newName", newName));
    }

    @When("I move it into {string}")
    public void iMoveItInto(String folderName) {
        long dest = world.named.get(folderName);
        world.lastResponse = world.api.post("/items/" + world.currentItemId + "/move",
                Map.of("newParentId", dest));
        if (world.lastResponse.statusCode() == 200) {
            world.currentParentId = dest;
        }
    }

    @When("I copy it into {string}")
    public void iCopyItInto(String folderName) {
        long dest = world.named.get(folderName);
        world.lastResponse = world.api.post("/items/" + world.currentItemId + "/copy",
                Map.of("destinationFolderId", dest));
        if (world.lastResponse.statusCode() == 201) {
            world.created.add(world.lastResponse.jsonPath().getLong("itemTreeId"));
        }
    }

    @When("I replace its data with:")
    public void iReplaceItsDataWith(String json) {
        world.lastResponse = world.api.post("/items/" + world.currentItemId + "/data",
                Map.of("data", parse(json)));
    }

    @When("I delete it")
    public void iDeleteIt() {
        world.lastResponse = world.api.delete("/items/" + world.currentItemId);
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private void captureIfCreated(String name) {
        if (world.lastResponse.statusCode() == 201) {
            long id = world.lastResponse.jsonPath().getLong("itemTreeId");
            world.currentItemId = id;
            world.currentParentId = world.lastResponse.jsonPath().getLong("parentId");
            world.created.add(id);
            world.named.put(name, id);
        }
    }

    private Map<String, Object> parse(String json) {
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid JSON in step: " + json, e);
        }
    }
}
```

- [ ] **Step 2: Lifecycle feature**

Create `acceptance/src/test/resources/features/item-lifecycle.feature`:

```gherkin
Feature: Item mutation lifecycle

  Background:
    Given a folder named "dest"
    And a folder named "copies"

  Scenario Outline: full lifecycle of a <type>
    When I create a <type> named "<name>"
    Then the response status is 201
    When I fetch it
    Then the item's type is "<type>"
    When I rename it to "<name>-renamed"
    And I fetch it
    Then the item's name is "<name>-renamed"
    When I move it into "dest"
    Then the response status is 200
    When I fetch it
    Then the item's parent is "dest"
    When I copy it into "copies"
    Then the response status is 201
    When I delete it
    Then the response status is 204
    And it no longer exists

    Examples:
      | type     | name         |
      | Report   | acc-report   |
      | Filter   | acc-filter   |
      | View     | acc-view     |
      | Shortcut | acc-shortcut |

  Scenario: update data of a data-bearing item
    When I create a Report named "acc-data"
    Then the response status is 201
    When I replace its data with:
      """
      { "name": "updated", "n": 99 }
      """
    Then the response status is 200
    When I delete it
    Then the response status is 204
    And it no longer exists
```

- [ ] **Step 3: Run — expect GREEN**

Ensure the app is running. Run: `./gradlew :acceptance:cucumber`
Expected: PASS — `smoke.feature` and `item-lifecycle.feature` green (5 scenarios from the outline + 1 data scenario + smoke).

- [ ] **Step 4: Commit**

```bash
git add acceptance/src/test
git commit -m "test(phase22): mutation steps + item lifecycle feature"
```

---

## Task 4: Read & search features

**Files:**
- Create: `acceptance/src/test/resources/features/tree-reads.feature`
- Create: `acceptance/src/test/resources/features/search.feature`

(All required steps already exist in `ReadSteps`/`ItemSteps`/`ErrorSteps`.)

- [ ] **Step 1: Tree-reads feature**

Create `acceptance/src/test/resources/features/tree-reads.feature`:

```gherkin
Feature: Read endpoints

  Scenario: created item appears in the sandbox subtree with root-anchored paths
    When I create a Report named "acc-read-1"
    Then the response status is 201
    When I get the full subtree of the sandbox
    Then the response status is 200
    And the subtree includes an item named "acc-read-1"
    And every item path starts with "/"
    When I delete it
    Then the response status is 204

  Scenario: getItems returns the created item with a root-anchored path
    When I create a View named "acc-read-2"
    Then the response status is 201
    When I fetch it
    Then the response status is 200
    And the item's path starts with "/"
    When I delete it
    Then the response status is 204

  Scenario: the immediate subtree of the sandbox is reachable
    When I get the subtree of the sandbox
    Then the response status is 200

  Scenario: the home folder resolves for the configured user
    When I resolve the home folder for "crhonekd"
    Then the response status is 200
    And the item's type is "Folder"

  Scenario: the tree skeleton is reachable
    When I get the tree
    Then the response status is 200
```

- [ ] **Step 2: Search feature**

Create `acceptance/src/test/resources/features/search.feature`:

```gherkin
Feature: Search

  Scenario: find a created item by name with its ancestor chain
    When I create a Report named "acc-unique-search-name"
    Then the response status is 201
    When I search for "acc-unique-search-name"
    Then the response status is 200
    And the results include "acc-unique-search-name"
    And the hit "acc-unique-search-name" has a path
    And the hit "acc-unique-search-name" has a non-empty ancestor chain
    When I delete it
    Then the response status is 204

  Scenario: find a created item by numeric id
    When I create a Filter named "acc-search-by-id"
    Then the response status is 201
    When I search for it by id
    Then the response status is 200
    And the results include "acc-search-by-id"
    When I delete it
    Then the response status is 204
```

- [ ] **Step 3: Run — expect GREEN**

Ensure the app is running. Run: `./gradlew :acceptance:cucumber`
Expected: PASS — all features so far green.

- [ ] **Step 4: Commit**

```bash
git add acceptance/src/test/resources/features
git commit -m "test(phase22): tree-reads and search features"
```

---

## Task 5: Error scenarios

**Files:**
- Create: `acceptance/src/test/resources/features/errors.feature`

(All required steps already exist.)

- [ ] **Step 1: Errors feature**

Create `acceptance/src/test/resources/features/errors.feature`:

```gherkin
Feature: Error handling

  Scenario: renaming a non-existent item returns 404
    When I rename item 999999999 to "nope"
    Then the response status is 404
    And the error code is "ITEM_NOT_FOUND"

  Scenario: creating outside the user's home folder is forbidden
    When I create a Report named "acc-illegal" in folder 1
    Then the response status is 403
    And the error code is "NOT_IN_USER_FOLDER"

  Scenario: a type that cannot hold data rejects data
    When I create a Shortcut named "acc-bad-data" with data:
      """
      { "x": 1 }
      """
    Then the response status is 400
    And the error code is "TYPE_CANNOT_HAVE_DATA"

  Scenario: an empty name is rejected
    When I create a Report named ""
    Then the response status is 400
```

- [ ] **Step 2: Run — expect GREEN**

Ensure the app is running. Run: `./gradlew :acceptance:cucumber`
Expected: PASS — all five feature files green. No `acceptance-*` folder residue under `crhonekd` after the run.

- [ ] **Step 3: Commit**

```bash
git add acceptance/src/test/resources/features/errors.feature
git commit -m "test(phase22): error-handling scenarios (404/403/400)"
```

---

## Task 6: Idempotency check + docs + memory

**Files:**
- Modify: `IMPLEMENTATION_NOTES.md`
- Create: `/home/dave/.claude/projects/-home-dave-Git-item-tree/memory/project-phase22-acceptance-done.md`
- Modify: `/home/dave/.claude/projects/-home-dave-Git-item-tree/memory/MEMORY.md`

- [ ] **Step 1: Re-run twice to prove idempotency + isolation from unit tests**

Ensure the app is running. Run:
```bash
./gradlew :acceptance:cucumber && ./gradlew :acceptance:cucumber
```
Expected: PASS both times (no duplicate-name or residue failures), proving cleanup works.

Then confirm the main suite is untouched:
```bash
./gradlew test
```
Expected: the existing 705 tests still pass; the `cucumber` task is NOT triggered by `test`.

- [ ] **Step 2: Document the phase**

Append a `## Phase 22 — Cucumber acceptance suite` section to `IMPLEMENTATION_NOTES.md` describing: the `:acceptance` subproject, how to run it (`./gradlew :acceptance:cucumber -Ditemtree.baseUrl=...`), the configuration knobs (`itemtree.baseUrl`/`itemtree.user`/`itemtree.readinessTimeoutSeconds`), the sandbox-folder lifecycle, the generic step vocabulary, the five feature files, and the one-row `data.sql` addition for `crhonekd`. Reference the spec at `docs/superpowers/specs/2026-05-29-phase22-cucumber-acceptance-design.md`.

- [ ] **Step 3: Write the memory entry**

Create `/home/dave/.claude/projects/-home-dave-Git-item-tree/memory/project-phase22-acceptance-done.md`:

```markdown
---
name: project-phase22-acceptance-done
description: Phase 22 done — standalone :acceptance Cucumber suite hitting a live instance
metadata:
  type: project
---

Phase 22 done as of 2026-05-29. Standalone `:acceptance` Gradle subproject (no app
dependency) of Cucumber-JVM + RestAssured scenarios that exercise every ITEMTREE
endpoint over HTTP against a running instance. Hostname configurable via
`-Ditemtree.baseUrl` (default `http://localhost:8080`); user via `-Ditemtree.user`
(default `crhonekd`). Run: `./gradlew :acceptance:cucumber`. NOT part of `./gradlew
test`. A static `Sandbox` (`@BeforeAll`/`@AfterAll`) waits on
`/actuator/health/readiness`, resolves the home folder, creates a unique
`acceptance-<millis>` test folder, and cascade-deletes it at the end; per-scenario
`@After` cleans created ids. Generic steps (`I create a {word} named {string}`) +
Scenario Outlines cover the create→rename→move→copy→update-data→delete→verify-gone
lifecycle, all read endpoints, search (path + ancestors), and errors (404
ITEM_NOT_FOUND / 403 NOT_IN_USER_FOLDER / 400 TYPE_CANNOT_HAVE_DATA / 400 validation).
One local-only seed change: `crhonekd` home folder (id 13) added to `data.sql`; the
real instance already has it.
```

- [ ] **Step 4: Add the MEMORY.md index line**

Add this line to the end of the memory list in `/home/dave/.claude/projects/-home-dave-Git-item-tree/memory/MEMORY.md`:

```markdown
- [Phase 22 acceptance suite complete](project-phase22-acceptance-done.md) — Phase 22 done as of 2026-05-29; standalone :acceptance Cucumber+RestAssured suite against a live instance; configurable host; self-cleaning sandbox folder
```

- [ ] **Step 5: Commit**

```bash
git add IMPLEMENTATION_NOTES.md
git commit -m "docs(phase22): implementation notes for the acceptance suite"
```

---

## Self-Review notes

- **Spec coverage:** module/build (Task 1), config + readiness + sandbox + identity (Task 2), generic mutation steps + lifecycle (Task 3), read endpoints (Tasks 2 steps + Task 4 features), search incl. ancestors (Task 4), errors 404/403/400 (Task 5), H2 `crhonekd` seed (Task 1 Step 2), cleanup/idempotency (Task 2 Hooks + Task 6 Step 1), docs/memory (Task 6). All §1–§10 spec items map to a task.
- **Status codes** match the OpenAPI spec: create/copy 201, delete 204, move/rename/data/reads 200.
- **Empty-name** scenario asserts status 400 only (bean-validation problems may not carry an `errorCode`); domain errors assert `errorCode`.
- **Type consistency:** `World.currentItemId`/`currentParentId`/`created`/`named`, `Sandbox.api()`/`rootId()`/`init()`/`teardown()`, and `ApiClient.post/get/getQuery/delete` are referenced identically across all tasks.
```

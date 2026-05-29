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
        if (!ready) throw new IllegalStateException("Sandbox.init() has not been called");
        return api;
    }

    public static long rootId() {
        if (!ready) throw new IllegalStateException("Sandbox.init() has not been called");
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

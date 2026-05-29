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

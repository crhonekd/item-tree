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

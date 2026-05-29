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

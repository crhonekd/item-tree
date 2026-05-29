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

        world.created.removeIf(id -> id.equals(world.currentItemId)); // already gone; skip in cleanup
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

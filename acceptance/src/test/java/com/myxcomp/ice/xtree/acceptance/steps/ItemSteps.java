package com.myxcomp.ice.xtree.acceptance.steps;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxcomp.ice.xtree.acceptance.support.World;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.When;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;

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
        long id = ((Number) r.jsonPath().get("itemTreeId")).longValue();
        world.named.put(name, id);
        world.created.add(id);
    }

    @When("I create a {word} named {string}")
    public void iCreateNamed(String type, String name) {
        world.lastResponse = world.api.post("/items",
                Map.of("parentId", world.sandboxRootId, "name", name, "type", type));
        // If the type requires data, retry with a minimal payload so the lifecycle
        // outline works for both data-bearing (Report/Filter/View) and data-free types.
        if (world.lastResponse.statusCode() == 400
                && "DATA_REQUIRED".equals(world.lastResponse.jsonPath().getString("errorCode"))) {
            Map<String, Object> body = new HashMap<>();
            body.put("parentId", world.sandboxRootId);
            body.put("name", name);
            body.put("type", type);
            body.put("data", Map.of("_acc", true));
            world.lastResponse = world.api.post("/items", body);
        }
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
            // copy returns an array (BFS-ordered subtree); root of the copy is index 0
            long copiedId = ((Number) world.lastResponse.jsonPath().get("[0].itemTreeId")).longValue();
            world.created.add(copiedId);
        }
    }

    @When("I replace its data with:")
    public void iReplaceItsDataWith(String json) {
        // The data endpoint is PUT; ApiClient only exposes POST/GET/DELETE,
        // so we drive RestAssured directly here with the same headers.
        world.lastResponse = RestAssured.given()
                .baseUri(world.api.baseUrl())
                .basePath("/api/v1/itemtree")
                .header("X-Ice-User", world.api.user())
                .contentType(ContentType.JSON)
                .accept("application/json, application/problem+json")
                .body(Map.of("data", parse(json)))
                .put("/items/" + world.currentItemId + "/data");
    }

    @When("I delete it")
    public void iDeleteIt() {
        world.lastResponse = world.api.delete("/items/" + world.currentItemId);
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private void captureIfCreated(String name) {
        if (world.lastResponse.statusCode() == 201) {
            long id = ((Number) world.lastResponse.jsonPath().get("itemTreeId")).longValue();
            world.currentItemId = id;
            world.currentParentId = ((Number) world.lastResponse.jsonPath().get("parentId")).longValue();
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

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

    private static final String FIELD_ITEM_TREE_ID       = "itemTreeId";
    private static final String FIELD_PARENT_ID          = "parentId";
    private static final String FIELD_NAME               = "name";
    private static final String FIELD_TYPE               = "type";
    private static final String FIELD_DATA               = "data";
    private static final String FIELD_NEW_NAME           = "newName";
    private static final String FIELD_NEW_PARENT_ID      = "newParentId";
    private static final String FIELD_DESTINATION_FOLDER = "destinationFolderId";
    private static final String ERR_DATA_REQUIRED        = "DATA_REQUIRED";

    private static final TypeReference<Map<String, Object>> MAP_TYPE_REF = new TypeReference<>() {};
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final World world;

    public ItemSteps(World world) {
        this.world = world;
    }

    @Given("a folder named {string}")
    public void aFolderNamed(String name) {
        var r = world.api.post("/items",
                Map.of(FIELD_PARENT_ID, world.sandboxRootId, FIELD_NAME, name, FIELD_TYPE, "Folder"));
        assertThat(r.statusCode()).as("creating folder %s", name).isEqualTo(201);
        long id = r.jsonPath().getLong(FIELD_ITEM_TREE_ID);
        world.named.put(name, id);
        world.created.add(id);
    }

    @When("I create a {word} named {string}")
    public void iCreateNamed(String type, String name) {
        world.lastResponse = world.api.post("/items",
                Map.of(FIELD_PARENT_ID, world.sandboxRootId, FIELD_NAME, name, FIELD_TYPE, type));
        // If the type requires data, retry with a minimal payload so the lifecycle
        // outline works for both data-bearing (Report/Filter/View) and data-free types.
        if (world.lastResponse.statusCode() == 400
                && ERR_DATA_REQUIRED.equals(world.lastResponse.jsonPath().getString("errorCode"))) {
            Map<String, Object> body = new HashMap<>();
            body.put(FIELD_PARENT_ID, world.sandboxRootId);
            body.put(FIELD_NAME, name);
            body.put(FIELD_TYPE, type);
            body.put(FIELD_DATA, Map.of("_acc", true));
            world.lastResponse = world.api.post("/items", body);
        }
        captureIfCreated(name);
    }

    @When("I create a {word} named {string} with data:")
    public void iCreateNamedWithData(String type, String name, String json) {
        Map<String, Object> body = new HashMap<>();
        body.put(FIELD_PARENT_ID, world.sandboxRootId);
        body.put(FIELD_NAME, name);
        body.put(FIELD_TYPE, type);
        body.put(FIELD_DATA, parse(json));
        world.lastResponse = world.api.post("/items", body);
        captureIfCreated(name);
    }

    @When("I create a {word} named {string} in folder {int}")
    public void iCreateInFolder(String type, String name, int parentId) {
        world.lastResponse = world.api.post("/items",
                Map.of(FIELD_PARENT_ID, (long) parentId, FIELD_NAME, name, FIELD_TYPE, type));
        captureIfCreated(name);
    }

    @When("I rename it to {string}")
    public void iRenameItTo(String newName) {
        world.lastResponse = world.api.post("/items/" + world.currentItemId + "/rename",
                Map.of(FIELD_NEW_NAME, newName));
    }

    @When("I rename item {long} to {string}")
    public void iRenameItemTo(long id, String newName) {
        world.lastResponse = world.api.post("/items/" + id + "/rename", Map.of(FIELD_NEW_NAME, newName));
    }

    @When("I move it into {string}")
    public void iMoveItInto(String folderName) {
        Long dest = world.named.get(folderName);
        assertThat(dest).as("no folder named '%s' registered in scenario", folderName).isNotNull();
        world.lastResponse = world.api.post("/items/" + world.currentItemId + "/move",
                Map.of(FIELD_NEW_PARENT_ID, dest));
        if (world.lastResponse.statusCode() == 200) {
            world.currentParentId = dest;
        }
    }

    @When("I copy it into {string}")
    public void iCopyItInto(String folderName) {
        Long dest = world.named.get(folderName);
        assertThat(dest).as("no folder named '%s' registered in scenario", folderName).isNotNull();
        world.lastResponse = world.api.post("/items/" + world.currentItemId + "/copy",
                Map.of(FIELD_DESTINATION_FOLDER, dest));
        if (world.lastResponse.statusCode() == 201) {
            // copy returns an array (BFS-ordered subtree); root of the copy is index 0
            long copiedId = world.lastResponse.jsonPath().getLong("[0]." + FIELD_ITEM_TREE_ID);
            world.created.add(copiedId);
        }
    }

    @When("I replace its data with:")
    public void iReplaceItsDataWith(String json) {
        world.lastResponse = world.api.put("/items/" + world.currentItemId + "/data",
                Map.of(FIELD_DATA, parse(json)));
    }

    @When("I delete it")
    public void iDeleteIt() {
        world.lastResponse = world.api.delete("/items/" + world.currentItemId);
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private void captureIfCreated(String name) {
        if (world.lastResponse.statusCode() == 201) {
            var jp = world.lastResponse.jsonPath();
            long id = jp.getLong(FIELD_ITEM_TREE_ID);
            world.currentItemId = id;
            world.currentParentId = jp.getLong(FIELD_PARENT_ID);
            world.created.add(id);
            world.named.put(name, id);
        }
    }

    private Map<String, Object> parse(String json) {
        try {
            return MAPPER.readValue(json, MAP_TYPE_REF);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid JSON in step: " + json, e);
        }
    }
}

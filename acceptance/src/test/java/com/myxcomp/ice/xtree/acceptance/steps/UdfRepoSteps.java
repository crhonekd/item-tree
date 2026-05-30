package com.myxcomp.ice.xtree.acceptance.steps;

import com.myxcomp.ice.xtree.acceptance.support.Sandbox;
import com.myxcomp.ice.xtree.acceptance.support.World;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.When;
import io.restassured.response.Response;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class UdfRepoSteps {

    private static final String TYPE_UDF_REPO       = "UDFRepo";
    private static final String FIELD_PARENT_ID     = "parentId";
    private static final String FIELD_NAME          = "name";
    private static final String FIELD_TYPE          = "type";
    private static final String FIELD_NEW_NAME      = "newName";
    private static final String FIELD_NEW_PARENT_ID = "newParentId";
    private static final String FIELD_ITEM_TREE_ID  = "itemTreeId";
    private static final String FIELD_ERROR_CODE    = "errorCode";
    private static final String ERR_ALREADY_EXISTS  = "UDF_REPO_ALREADY_EXISTS";

    private final World world;

    public UdfRepoSteps(World world) {
        this.world = world;
    }

    @Given("my UDFRepo exists")
    public void myUdfRepoExists() {
        long homeId = Sandbox.homeId();
        Response r = world.api.post("/items", createBody(homeId));
        long id;
        if (r.statusCode() == 201) {
            id = ((Number) r.jsonPath().get(FIELD_ITEM_TREE_ID)).longValue();
        } else {
            assertThat(r.statusCode())
                    .as("create UDFRepo: body %s", r.getBody().asString())
                    .isEqualTo(400);
            assertThat(r.jsonPath().getString(FIELD_ERROR_CODE)).isEqualTo(ERR_ALREADY_EXISTS);
            id = findUdfRepoUnder(homeId);
        }
        // Deliberately NOT added to world.created: a UDFRepo cannot be deleted.
        world.currentItemId = id;
    }

    @When("I create another UDFRepo")
    public void iCreateAnotherUdfRepo() {
        world.lastResponse = world.api.post("/items", createBody(Sandbox.homeId()));
    }

    @When("I try to delete my UDFRepo")
    public void iTryToDeleteMyUdfRepo() {
        world.lastResponse = world.api.delete("/items/" + world.currentItemId);
    }

    @When("I try to rename my UDFRepo to {string}")
    public void iTryToRenameMyUdfRepo(String newName) {
        world.lastResponse = world.api.post("/items/" + world.currentItemId + "/rename",
                Map.of(FIELD_NEW_NAME, newName));
    }

    @When("I try to move my UDFRepo into the sandbox")
    public void iTryToMoveMyUdfRepoIntoTheSandbox() {
        world.lastResponse = world.api.post("/items/" + world.currentItemId + "/move",
                Map.of(FIELD_NEW_PARENT_ID, world.sandboxRootId));
    }

    private Map<String, Object> createBody(long homeId) {
        Map<String, Object> body = new HashMap<>();
        body.put(FIELD_PARENT_ID, homeId);
        body.put(FIELD_NAME, world.api.user());
        body.put(FIELD_TYPE, TYPE_UDF_REPO);
        body.put("data", Map.of("udfs", List.of()));
        return body;
    }

    private long findUdfRepoUnder(long homeId) {
        Response r = world.api.get("/tree/" + homeId + "/subtree");
        assertThat(r.statusCode()).isEqualTo(200);
        Long id = r.jsonPath().getLong("find { it.type == '" + TYPE_UDF_REPO + "' }." + FIELD_ITEM_TREE_ID);
        assertThat(id).as("no UDFRepo found directly under home folder %s", homeId).isNotNull();
        return id;
    }
}

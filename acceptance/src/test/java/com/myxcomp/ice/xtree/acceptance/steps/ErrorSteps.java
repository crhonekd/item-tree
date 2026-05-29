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

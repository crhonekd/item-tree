package com.myxcomp.ice.xtree;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ApiContractTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void apiDocsReturnsOkWithCorrectTitle() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                // Title must match itemtree-api.yaml info.title exactly — drift becomes a test failure
                .andExpect(jsonPath("$.info.title").value("ItemTree API"))
                // springdoc with openapi_3_0 emits "3.0.1"; accept any 3.0.x
                .andExpect(jsonPath("$.openapi").value(startsWith("3.0")));
    }

    @Test
    void apiDocsContainsAllTenOperations() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"createItem\"");
        assertThat(body).contains("\"deleteItem\"");
        assertThat(body).contains("\"moveItem\"");
        assertThat(body).contains("\"renameItem\"");
        assertThat(body).contains("\"updateItemData\"");
        assertThat(body).contains("\"getItems\"");
        assertThat(body).contains("\"getTree\"");
        assertThat(body).contains("\"getSubtree\"");
        assertThat(body).contains("\"search\"");
        assertThat(body).contains("\"getHomeFolder\"");
    }

    @Test
    void apiDocsHasRequiredComponents() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                // Core model schemas registered by springdoc from the generated Java model classes
                .andExpect(jsonPath("$.components.schemas.Problem").exists())
                .andExpect(jsonPath("$.components.schemas.ItemNode").exists())
                .andExpect(jsonPath("$.components.schemas.ItemNodeWithData").exists());
    }

    @Test
    void apiDocsHasRequiredErrorResponses() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Springdoc inlines error responses per-operation rather than emitting shared
        // components/responses entries; verify the error descriptions appear in the doc
        assertThat(body).contains("Bad request");
        assertThat(body).contains("Service unavailable");
        // The X-Ice-User header parameter is inlined per-operation (no shared components/parameters)
        assertThat(body).contains("X-Ice-User");
    }
}

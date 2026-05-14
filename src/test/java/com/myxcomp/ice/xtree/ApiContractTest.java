package com.myxcomp.ice.xtree;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
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
                .andExpect(jsonPath("$.info.title").value("ItemTree API"))
                .andExpect(jsonPath("$.openapi").value(org.hamcrest.Matchers.startsWith("3.0")));
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
}

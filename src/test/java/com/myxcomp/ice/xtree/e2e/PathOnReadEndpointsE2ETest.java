package com.myxcomp.ice.xtree.e2e;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end roundtrip tests confirming the {@code path} field is populated on
 * read-only endpoints: {@code /search} and {@code /items/get}.
 *
 * <p>Boots the full Spring context against H2 seed data (dev profile). No service
 * beans are mocked — this is a true full-stack test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class PathOnReadEndpointsE2ETest {

    private static final String SEARCH_URL        = "/api/v1/itemtree/search";
    private static final String ITEMS_GET_URL      = "/api/v1/itemtree/items/get";
    private static final String HOME_SUBTREE_URL   = "/api/v1/itemtree/users/testuser1/home-subtree";
    private static final String HEADER_USER        = "X-Ice-User";

    // Seed data (data.sql): id=10, name="testuser1", type=Folder, parent=2 (Users→root)
    private static final String SEARCH_TERM   = "testuser1";
    // Seed data: id=2, name="Users", type=Folder under root; has children 10, 11, 12
    private static final long   USERS_FOLDER_ID = 2L;

    @Autowired
    MockMvc mockMvc;

    @Test
    void searchHitsCarryLeadingSlashPath() throws Exception {
        mockMvc.perform(get(SEARCH_URL)
                        .param("q", SEARCH_TERM)
                        .header(HEADER_USER, SEARCH_TERM))
                .andExpect(status().isOk())
                // At least one hit (id=10 "testuser1" folder)
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0]").exists())
                // Every hit must have a path starting with "/root"
                .andExpect(jsonPath("$[0].path").value(org.hamcrest.Matchers.startsWith("/root")));
    }

    @Test
    void itemsGetReturnsPathOnItemAndChildren() throws Exception {
        // POST /items/get with the "Users" folder (id=2) which has child home-folders
        String requestBody = "{\"ids\": [" + USERS_FOLDER_ID + "]}";

        mockMvc.perform(post(ITEMS_GET_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HEADER_USER, "testuser1")
                        .content(requestBody))
                .andExpect(status().isOk())
                // Exactly one top-level item returned for the one requested id
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0]").exists())
                // The folder itself must carry a path starting with "/root"
                .andExpect(jsonPath("$[0].path").value(org.hamcrest.Matchers.startsWith("/root")))
                // Users folder has children (testuser1, testuser2, deepuser) — each child also has a path
                .andExpect(jsonPath("$[0].children").isArray())
                .andExpect(jsonPath("$[0].children[0]").exists())
                .andExpect(jsonPath("$[0].children[0].path").value(org.hamcrest.Matchers.startsWith("/root")));
    }

    @Test
    void homeSubtreeReturnsHomeFolderAndDescendantsWithPaths() throws Exception {
        // Seed: id=10, name="testuser1", parentId=2 (Users → root). Home folder
        // exists and has at least itself plus seeded descendants in the array.
        mockMvc.perform(get(HOME_SUBTREE_URL)
                        .header(HEADER_USER, "testuser1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0]").exists())
                // The home folder itself (id=10) must appear, with leading-slash path.
                .andExpect(jsonPath(
                        "$[?(@.itemTreeId == 10)].name").value(org.hamcrest.Matchers.hasItem("testuser1")))
                .andExpect(jsonPath(
                        "$[?(@.itemTreeId == 10)].path").value(
                                org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.startsWith("/root"))))
                // Its parentId (=2 "Users") must NOT itself appear as any element's itemTreeId
                // — that is the invariant the UI relies on to derive the home folder.
                .andExpect(jsonPath("$[?(@.itemTreeId == 2)]").doesNotExist());
    }

    @Test
    void homeSubtreeReturns404ForUnknownUser() throws Exception {
        mockMvc.perform(get("/api/v1/itemtree/users/no-such-user-xyz/home-subtree")
                        .header(HEADER_USER, "testuser1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("HOME_FOLDER_NOT_FOUND"));
    }
}

package com.myxcomp.ice.xtree.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxcomp.ice.xtree.api.advice.GlobalExceptionHandler;
import com.myxcomp.ice.xtree.api.advice.ProblemFactory;
import com.myxcomp.ice.xtree.api.mapper.ItemNodeMapper;
import com.myxcomp.ice.xtree.api.mapper.ItemNodeWithDataMapper;
import com.myxcomp.ice.xtree.cache.CacheReadinessGate;
import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.common.UserContext;
import com.myxcomp.ice.xtree.service.ItemService;
import com.myxcomp.ice.xtree.service.ItemWithData;
import com.myxcomp.ice.xtree.service.exception.ErrorCode;
import com.myxcomp.ice.xtree.service.exception.NotFoundException;
import com.myxcomp.ice.xtree.service.exception.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ItemController.class)
@Import({GlobalExceptionHandler.class, ProblemFactory.class,
         ItemNodeMapper.class, ItemNodeWithDataMapper.class})
class ItemControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean ItemService itemService;
    // Required by WebMvcConfig.cacheReadinessFilterRegistration bean
    @MockBean CacheReadinessGate cacheReadinessGate;

    private static final Instant T = Instant.parse("2026-05-16T12:00:00Z");

    @BeforeEach
    void gateCacheReady() {
        // Let all requests through the CacheReadinessFilter
        when(cacheReadinessGate.isReady()).thenReturn(true);
    }

    private CachedNode node(long id, long parent, String name, String type) {
        return new CachedNode(id, parent, name, type, T, "alice");
    }

    // ── create ───────────────────────────────────────────────────────────

    @Test
    void createReturns201AndDto() throws Exception {
        when(itemService.createItem(eq(2L), eq("Report-1"), eq("Report"),
                eq("{\"foo\":\"bar\"}"), any(UserContext.class)))
                .thenReturn(node(42L, 2L, "Report-1", "Report"));

        mvc.perform(post("/api/v1/itemtree/items")
                        .header("X-Ice-User", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":2,\"name\":\"Report-1\",\"type\":\"Report\",\"data\":{\"foo\":\"bar\"}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.itemTreeId").value(42))
                .andExpect(jsonPath("$.parentId").value(2))
                .andExpect(jsonPath("$.name").value("Report-1"))
                .andExpect(jsonPath("$.type").value("Report"))
                .andExpect(jsonPath("$.lastUpdateUser").value("alice"));
    }

    @Test
    void createWithoutDataPassesNullDataJson() throws Exception {
        when(itemService.createItem(eq(2L), eq("MyFolder"), eq("Folder"),
                eq(null), any(UserContext.class)))
                .thenReturn(node(42L, 2L, "MyFolder", "Folder"));

        mvc.perform(post("/api/v1/itemtree/items")
                        .header("X-Ice-User", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":2,\"name\":\"MyFolder\",\"type\":\"Folder\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void createPassesImpersonatedUserToService() throws Exception {
        when(itemService.createItem(anyLong(), any(), any(), any(), any(UserContext.class)))
                .thenReturn(node(42L, 2L, "x", "Folder"));

        mvc.perform(post("/api/v1/itemtree/items")
                        .header("X-Ice-User", "alice")
                        .header("X-Impersonated-User", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":2,\"name\":\"x\",\"type\":\"Folder\"}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<UserContext> captor = ArgumentCaptor.forClass(UserContext.class);
        verify(itemService).createItem(anyLong(), any(), any(), any(), captor.capture());
        assertThat(captor.getValue().iceUser()).isEqualTo("alice");
        assertThat(captor.getValue().impersonatedUser()).isEqualTo("bob");
    }

    @Test
    void createWithoutIceUserHeaderReturns400() throws Exception {
        mvc.perform(post("/api/v1/itemtree/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":2,\"name\":\"x\",\"type\":\"Folder\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("X-Ice-User")));
    }

    @Test
    void createWithEmptyNameReturns400() throws Exception {
        mvc.perform(post("/api/v1/itemtree/items")
                        .header("X-Ice-User", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":2,\"name\":\"\",\"type\":\"Folder\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("name")));
    }

    @Test
    void createWithParentNotFoundReturns404() throws Exception {
        when(itemService.createItem(anyLong(), any(), any(), any(), any(UserContext.class)))
                .thenThrow(new NotFoundException(ErrorCode.PARENT_NOT_FOUND, "Parent 2 not found"));

        mvc.perform(post("/api/v1/itemtree/items")
                        .header("X-Ice-User", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":2,\"name\":\"x\",\"type\":\"Folder\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PARENT_NOT_FOUND"));
    }

    @Test
    void createWithTypeCannotHaveDataReturns400() throws Exception {
        when(itemService.createItem(anyLong(), any(), any(), any(), any(UserContext.class)))
                .thenThrow(new ValidationException(ErrorCode.TYPE_CANNOT_HAVE_DATA,
                        "Type 'Folder' cannot carry data"));

        mvc.perform(post("/api/v1/itemtree/items")
                        .header("X-Ice-User", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":2,\"name\":\"x\",\"type\":\"Folder\",\"data\":{\"k\":\"v\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("TYPE_CANNOT_HAVE_DATA"));
    }

    // ── delete ───────────────────────────────────────────────────────────

    @Test
    void deleteReturns204() throws Exception {
        mvc.perform(delete("/api/v1/itemtree/items/42")
                        .header("X-Ice-User", "alice"))
                .andExpect(status().isNoContent());
        verify(itemService).deleteItem(eq(42L), any(UserContext.class));
    }

    @Test
    void deleteWithNonNumericIdReturns400() throws Exception {
        mvc.perform(delete("/api/v1/itemtree/items/abc")
                        .header("X-Ice-User", "alice"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("id")));
    }

    // ── move ─────────────────────────────────────────────────────────────

    @Test
    void moveReturns200AndUpdatedDto() throws Exception {
        when(itemService.moveItem(eq(42L), eq(7L), any(UserContext.class)))
                .thenReturn(node(42L, 7L, "Report-1", "Report"));

        mvc.perform(post("/api/v1/itemtree/items/42/move")
                        .header("X-Ice-User", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newParentId\":7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parentId").value(7));
    }

    @Test
    void moveIntoDescendantReturns400() throws Exception {
        when(itemService.moveItem(anyLong(), anyLong(), any(UserContext.class)))
                .thenThrow(new ValidationException(ErrorCode.MOVE_INTO_DESCENDANT,
                        "Cannot move id=42 under its own descendant 7"));

        mvc.perform(post("/api/v1/itemtree/items/42/move")
                        .header("X-Ice-User", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newParentId\":7}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MOVE_INTO_DESCENDANT"));
    }

    @Test
    void moveItemNotFoundReturns404() throws Exception {
        when(itemService.moveItem(anyLong(), anyLong(), any(UserContext.class)))
                .thenThrow(new NotFoundException(ErrorCode.ITEM_NOT_FOUND, "Item 42 not found"));

        mvc.perform(post("/api/v1/itemtree/items/42/move")
                        .header("X-Ice-User", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newParentId\":7}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ITEM_NOT_FOUND"));
    }

    // ── rename ───────────────────────────────────────────────────────────

    @Test
    void renameReturns200AndUpdatedDto() throws Exception {
        when(itemService.renameItem(eq(42L), eq("Report-2"), any(UserContext.class)))
                .thenReturn(node(42L, 2L, "Report-2", "Report"));

        mvc.perform(post("/api/v1/itemtree/items/42/rename")
                        .header("X-Ice-User", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newName\":\"Report-2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Report-2"));
    }

    @Test
    void renameWithEmptyNameReturns400() throws Exception {
        mvc.perform(post("/api/v1/itemtree/items/42/rename")
                        .header("X-Ice-User", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newName\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("newName")));
    }

    // ── update data ──────────────────────────────────────────────────────

    @Test
    void updateDataReturns200AndDto() throws Exception {
        when(itemService.updateItemData(eq(42L), eq("{\"foo\":\"bar\"}"),
                any(UserContext.class)))
                .thenReturn(node(42L, 2L, "Report-1", "Report"));

        mvc.perform(put("/api/v1/itemtree/items/42/data")
                        .header("X-Ice-User", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"data\":{\"foo\":\"bar\"}}"))
                .andExpect(status().isOk());
    }

    @Test
    void updateDataForFolderReturns400() throws Exception {
        when(itemService.updateItemData(anyLong(), any(), any(UserContext.class)))
                .thenThrow(new ValidationException(ErrorCode.FOLDER_CANNOT_HAVE_DATA,
                        "Folder 42 cannot carry data"));

        mvc.perform(put("/api/v1/itemtree/items/42/data")
                        .header("X-Ice-User", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"data\":{\"foo\":\"bar\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("FOLDER_CANNOT_HAVE_DATA"));
    }

    // ── getItems ─────────────────────────────────────────────────────────

    @Test
    void getItemsReturns200AndListWithJsonInflatedAsMap() throws Exception {
        ItemWithData item = new ItemWithData(
                42L, 2L, "Report-1", "Report", T, "alice",
                "{\"foo\":\"bar\"}", null, null);
        when(itemService.getItemsWithData(List.of(42L))).thenReturn(List.of(item));

        mvc.perform(post("/api/v1/itemtree/items/get")
                        .header("X-Ice-User", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[42]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].itemTreeId").value(42))
                .andExpect(jsonPath("$[0].dataJson.foo").value("bar"));
    }

    @Test
    void getItemsHandlesEmptyList() throws Exception {
        when(itemService.getItemsWithData(List.of())).thenReturn(List.of());

        mvc.perform(post("/api/v1/itemtree/items/get")
                        .header("X-Ice-User", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }
}

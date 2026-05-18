package com.myxcomp.ice.xtree.api.controller;

import com.myxcomp.ice.xtree.api.advice.GlobalExceptionHandler;
import com.myxcomp.ice.xtree.api.advice.ProblemFactory;
import com.myxcomp.ice.xtree.api.mapper.ItemNodeMapper;
import com.myxcomp.ice.xtree.cache.CacheReadinessGate;
import com.myxcomp.ice.xtree.config.SecurityProperties;
import com.myxcomp.ice.xtree.common.TimeMapper;
import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.common.UserContext;
import com.myxcomp.ice.xtree.service.TreeNodeView;
import com.myxcomp.ice.xtree.service.TreeService;
import com.myxcomp.ice.xtree.service.exception.ErrorCode;
import com.myxcomp.ice.xtree.service.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TreeController.class)
@Import({GlobalExceptionHandler.class, ProblemFactory.class, ItemNodeMapper.class, TimeMapper.class})
class TreeControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean TreeService treeService;
    @MockitoBean CacheReadinessGate cacheReadinessGate;
    @MockitoBean SecurityProperties securityProperties;

    private static final Instant T = Instant.parse("2026-05-16T12:00:00Z");

    @BeforeEach
    void gateReady() {
        when(cacheReadinessGate.isReady()).thenReturn(true);
    }

    private TreeNodeView view(long id, long parent, String name, String type, String path) {
        return new TreeNodeView(new CachedNode(id, parent, name, type, T, "alice"), path);
    }

    @Test
    void getTreeReturns200WithListOfItemNodes() throws Exception {
        when(treeService.getTree(any(UserContext.class))).thenReturn(List.of(
                view(1L, 0L, "root",  "Folder", "root"),
                view(2L, 1L, "Users", "Folder", "root/Users")));

        mvc.perform(get("/api/v1/itemtree/tree")
                        .header("X-Ice-User", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].itemTreeId").value(1))
                .andExpect(jsonPath("$[0].path").value("root"))
                .andExpect(jsonPath("$[1].path").value("root/Users"));
    }

    @Test
    void getTreePassesImpersonatedUserContext() throws Exception {
        when(treeService.getTree(any(UserContext.class))).thenReturn(List.of());

        mvc.perform(get("/api/v1/itemtree/tree")
                        .header("X-Ice-User", "alice")
                        .header("X-Impersonated-User", "bob"))
                .andExpect(status().isOk());

        ArgumentCaptor<UserContext> captor = ArgumentCaptor.forClass(UserContext.class);
        verify(treeService).getTree(captor.capture());
        assertThat(captor.getValue().iceUser()).isEqualTo("alice");
        assertThat(captor.getValue().impersonatedUser()).isEqualTo("bob");
    }

    @Test
    void getTreeReturns404WhenHomeFolderMissing() throws Exception {
        when(treeService.getTree(any(UserContext.class)))
                .thenThrow(new NotFoundException(ErrorCode.HOME_FOLDER_NOT_FOUND,
                        "No home folder for user 'ghost'"));

        mvc.perform(get("/api/v1/itemtree/tree")
                        .header("X-Ice-User", "ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("HOME_FOLDER_NOT_FOUND"));
    }

    @Test
    void getSubtreeReturns200WithPaths() throws Exception {
        when(treeService.getSubtree(7L)).thenReturn(List.of(
                view(7L, 0L, "root",  "Folder", "root"),
                view(8L, 7L, "child", "Folder", "root/child")));

        mvc.perform(get("/api/v1/itemtree/tree/7/subtree")
                        .header("X-Ice-User", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].path").value("root/child"));
    }

    @Test
    void getSubtreeReturns404WhenRootMissing() throws Exception {
        when(treeService.getSubtree(anyLong()))
                .thenThrow(new NotFoundException(ErrorCode.ITEM_NOT_FOUND, "Item 99 not found"));

        mvc.perform(get("/api/v1/itemtree/tree/99/subtree")
                        .header("X-Ice-User", "alice"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ITEM_NOT_FOUND"));
    }

    @Test
    void getSubtreeWithNonNumericRootReturns400() throws Exception {
        mvc.perform(get("/api/v1/itemtree/tree/abc/subtree")
                        .header("X-Ice-User", "alice"))
                .andExpect(status().isBadRequest());
    }
}

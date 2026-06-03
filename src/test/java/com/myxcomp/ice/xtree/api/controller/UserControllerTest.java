package com.myxcomp.ice.xtree.api.controller;

import com.myxcomp.ice.xtree.api.advice.GlobalExceptionHandler;
import com.myxcomp.ice.xtree.api.advice.ProblemFactory;
import com.myxcomp.ice.xtree.api.mapper.ItemNodeMapper;
import com.myxcomp.ice.xtree.cache.CacheReadinessGate;
import com.myxcomp.ice.xtree.config.SecurityProperties;
import com.myxcomp.ice.xtree.common.TimeMapper;
import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.service.HomeFolderService;
import com.myxcomp.ice.xtree.service.PathResolver;
import com.myxcomp.ice.xtree.service.TreeNodeView;
import com.myxcomp.ice.xtree.service.TreeService;
import com.myxcomp.ice.xtree.service.exception.ErrorCode;
import com.myxcomp.ice.xtree.service.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
@Import({GlobalExceptionHandler.class, ProblemFactory.class, ItemNodeMapper.class, TimeMapper.class})
class UserControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean HomeFolderService homeFolderService;
    @MockitoBean PathResolver pathResolver;
    @MockitoBean TreeService treeService;
    @MockitoBean CacheReadinessGate cacheReadinessGate;
    @MockitoBean SecurityProperties securityProperties;

    @BeforeEach
    void gateReady() {
        when(cacheReadinessGate.isReady()).thenReturn(true);
    }

    @Test
    void getHomeFolderReturns200AndItemNode() throws Exception {
        when(homeFolderService.findHomeFolder("alice"))
                .thenReturn(new CachedNode(
                        42L, 2L, "alice", "Folder", Instant.parse("2026-05-16T12:00:00Z"), "sys"));
        when(pathResolver.pathOf(42L)).thenReturn("/root/Users/alice");

        mvc.perform(get("/api/v1/itemtree/users/alice/home-folder")
                        .header("X-Ice-User", "caller"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemTreeId").value(42))
                .andExpect(jsonPath("$.name").value("alice"))
                .andExpect(jsonPath("$.type").value("Folder"))
                .andExpect(jsonPath("$.path").value("/root/Users/alice"));
    }

    @Test
    void getHomeFolderReturnsItemNodeWithPath() throws Exception {
        CachedNode folder = new CachedNode(20L, 2L, "alice", "Folder", Instant.parse("2026-05-16T12:00:00Z"), "sys");
        when(homeFolderService.findHomeFolder("alice")).thenReturn(folder);
        when(pathResolver.pathOf(20L)).thenReturn("/root/Users/alice");

        mvc.perform(get("/api/v1/itemtree/users/alice/home-folder")
                        .header("X-Ice-User", "tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemTreeId").value(20))
                .andExpect(jsonPath("$.name").value("alice"))
                .andExpect(jsonPath("$.path").value("/root/Users/alice"));
    }

    @Test
    void getHomeFolderReturns404WhenMissing() throws Exception {
        when(homeFolderService.findHomeFolder("ghost"))
                .thenThrow(new NotFoundException(
                        ErrorCode.HOME_FOLDER_NOT_FOUND,
                        "No home folder for user 'ghost'"));

        mvc.perform(get("/api/v1/itemtree/users/ghost/home-folder")
                        .header("X-Ice-User", "caller"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("HOME_FOLDER_NOT_FOUND"));
    }

    @Test
    void getHomeSubtreeReturns200AndFlatArrayWithPaths() throws Exception {
        CachedNode home = new CachedNode(
                42L, 2L, "alice", "Folder", Instant.parse("2026-05-16T12:00:00Z"), "sys");
        CachedNode child = new CachedNode(
                43L, 42L, "Notes", "Folder", Instant.parse("2026-05-16T12:00:00Z"), "sys");
        when(homeFolderService.findHomeFolder("alice")).thenReturn(home);
        when(treeService.getSubtreeFull(42L)).thenReturn(List.of(
                new TreeNodeView(home,  "/root/Users/alice"),
                new TreeNodeView(child, "/root/Users/alice/Notes")
        ));

        mvc.perform(get("/api/v1/itemtree/users/alice/home-subtree")
                        .header("X-Ice-User", "caller"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].itemTreeId").value(42))
                .andExpect(jsonPath("$[0].parentId").value(2))
                .andExpect(jsonPath("$[0].name").value("alice"))
                .andExpect(jsonPath("$[0].path").value("/root/Users/alice"))
                .andExpect(jsonPath("$[1].itemTreeId").value(43))
                .andExpect(jsonPath("$[1].parentId").value(42))
                .andExpect(jsonPath("$[1].path").value("/root/Users/alice/Notes"));
    }
}

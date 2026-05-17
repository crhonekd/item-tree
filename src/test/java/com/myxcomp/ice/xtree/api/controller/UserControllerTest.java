package com.myxcomp.ice.xtree.api.controller;

import com.myxcomp.ice.xtree.api.advice.GlobalExceptionHandler;
import com.myxcomp.ice.xtree.api.advice.ProblemFactory;
import com.myxcomp.ice.xtree.api.mapper.ItemNodeMapper;
import com.myxcomp.ice.xtree.cache.CacheReadinessGate;
import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.service.HomeFolderService;
import com.myxcomp.ice.xtree.service.exception.ErrorCode;
import com.myxcomp.ice.xtree.service.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
@Import({GlobalExceptionHandler.class, ProblemFactory.class, ItemNodeMapper.class})
class UserControllerTest {

    @Autowired MockMvc mvc;
    @MockBean HomeFolderService homeFolderService;
    @MockBean CacheReadinessGate cacheReadinessGate;

    @BeforeEach
    void gateReady() {
        when(cacheReadinessGate.isReady()).thenReturn(true);
    }

    @Test
    void getHomeFolderReturns200AndItemNode() throws Exception {
        when(homeFolderService.findHomeFolder("alice"))
                .thenReturn(new CachedNode(
                        42L, 2L, "alice", "Folder", Instant.parse("2026-05-16T12:00:00Z"), "sys"));

        mvc.perform(get("/api/v1/itemtree/users/alice/home-folder")
                        .header("X-Ice-User", "caller"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemTreeId").value(42))
                .andExpect(jsonPath("$.name").value("alice"))
                .andExpect(jsonPath("$.type").value("Folder"));
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
}

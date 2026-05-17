package com.myxcomp.ice.xtree.api.controller;

import com.myxcomp.ice.xtree.api.mapper.ItemNodeMapper;
import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.generated.api.UsersApi;
import com.myxcomp.ice.xtree.generated.model.ItemNode;
import com.myxcomp.ice.xtree.service.HomeFolderService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserController implements UsersApi {

    private final HomeFolderService homeFolderService;
    private final ItemNodeMapper itemNodeMapper;

    public UserController(HomeFolderService homeFolderService, ItemNodeMapper itemNodeMapper) {
        this.homeFolderService = homeFolderService;
        this.itemNodeMapper = itemNodeMapper;
    }

    @Override
    public ResponseEntity<ItemNode> getHomeFolder(String userName, String xIceUser, String xImpersonatedUser) {
        CachedNode folder = homeFolderService.findHomeFolder(userName);
        return ResponseEntity.ok(itemNodeMapper.toDto(folder));
    }
}

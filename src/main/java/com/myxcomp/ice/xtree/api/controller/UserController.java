package com.myxcomp.ice.xtree.api.controller;

import com.myxcomp.ice.xtree.api.mapper.ItemNodeMapper;
import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.generated.api.UsersApi;
import com.myxcomp.ice.xtree.generated.model.ItemNode;
import com.myxcomp.ice.xtree.service.HomeFolderService;
import com.myxcomp.ice.xtree.service.PathResolver;
import com.myxcomp.ice.xtree.service.TreeNodeView;
import com.myxcomp.ice.xtree.service.TreeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class UserController implements UsersApi {

    private final HomeFolderService homeFolderService;
    private final PathResolver pathResolver;
    private final TreeService treeService;
    private final ItemNodeMapper itemNodeMapper;

    public UserController(HomeFolderService homeFolderService,
                          PathResolver pathResolver,
                          TreeService treeService,
                          ItemNodeMapper itemNodeMapper) {
        this.homeFolderService = homeFolderService;
        this.pathResolver = pathResolver;
        this.treeService = treeService;
        this.itemNodeMapper = itemNodeMapper;
    }

    @Override
    public ResponseEntity<ItemNode> getHomeFolder(String userName, String xIceUser, String xImpersonatedUser) {
        CachedNode folder = homeFolderService.findHomeFolder(userName);
        String path = pathResolver.pathOf(folder.itemTreeId());
        return ResponseEntity.ok(itemNodeMapper.toDto(new TreeNodeView(folder, path)));
    }

    @Override
    public ResponseEntity<List<ItemNode>> getHomeSubtree(String userName, String xIceUser, String xImpersonatedUser) {
        CachedNode home = homeFolderService.findHomeFolder(userName);
        List<TreeNodeView> views = treeService.getSubtreeFull(home.itemTreeId());
        return ResponseEntity.ok(itemNodeMapper.toDtos(views));
    }
}

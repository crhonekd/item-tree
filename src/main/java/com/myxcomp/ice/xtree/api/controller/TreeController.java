package com.myxcomp.ice.xtree.api.controller;

import com.myxcomp.ice.xtree.api.mapper.ItemNodeMapper;
import com.myxcomp.ice.xtree.common.UserContext;
import com.myxcomp.ice.xtree.generated.api.TreeApi;
import com.myxcomp.ice.xtree.generated.model.ItemNode;
import com.myxcomp.ice.xtree.service.TreeNodeView;
import com.myxcomp.ice.xtree.service.TreeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class TreeController implements TreeApi {

    private final TreeService treeService;
    private final ItemNodeMapper itemNodeMapper;

    public TreeController(TreeService treeService, ItemNodeMapper itemNodeMapper) {
        this.treeService = treeService;
        this.itemNodeMapper = itemNodeMapper;
    }

    @Override
    public ResponseEntity<List<ItemNode>> getTree(String xIceUser, String xImpersonatedUser) {
        UserContext ctx = new UserContext(xIceUser, xImpersonatedUser);
        List<TreeNodeView> views = treeService.getTree(ctx);
        return ResponseEntity.ok(itemNodeMapper.toDtos(views));
    }

    @Override
    public ResponseEntity<List<ItemNode>> getSubtree(Long rootId, String xIceUser, String xImpersonatedUser) {
        List<TreeNodeView> views = treeService.getSubtree(rootId);
        return ResponseEntity.ok(itemNodeMapper.toDtos(views));
    }
}

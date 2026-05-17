package com.myxcomp.ice.xtree.service;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.TreeCache;
import com.myxcomp.ice.xtree.common.UserContext;
import com.myxcomp.ice.xtree.service.exception.ErrorCode;
import com.myxcomp.ice.xtree.service.exception.NotFoundException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class TreeService {

    private final TreeCache cache;
    private final PathResolver pathResolver;
    private final HomeFolderService homeFolderService;

    public TreeService(TreeCache cache,
                       PathResolver pathResolver,
                       HomeFolderService homeFolderService) {
        this.cache = cache;
        this.pathResolver = pathResolver;
        this.homeFolderService = homeFolderService;
    }

    /**
     * Returns the trimmed tree view (§8) for the caller, anchored on the caller's home folder.
     * Uses the impersonated user when present, else the authenticated user — design §13.
     */
    public List<TreeNodeView> getTree(UserContext userContext) {
        Objects.requireNonNull(userContext, "userContext");
        CachedNode home = homeFolderService.findHomeFolder(userContext.effectiveUser());
        List<CachedNode> nodes = cache.getTreeView(home.itemTreeId());
        return pairWithPaths(nodes);
    }

    /**
     * Returns every node in the subtree rooted at {@code rootId}, each paired with its path.
     *
     * @throws NotFoundException (ITEM_NOT_FOUND) when no item with {@code rootId} exists in the cache
     */
    public List<TreeNodeView> getSubtree(long rootId) {
        if (cache.getById(rootId).isEmpty()) {
            throw new NotFoundException(ErrorCode.ITEM_NOT_FOUND,
                    "Item " + rootId + " not found");
        }
        List<CachedNode> nodes = cache.getSubtreeFlat(rootId);
        return pairWithPaths(nodes);
    }

    private List<TreeNodeView> pairWithPaths(List<CachedNode> nodes) {
        if (nodes.isEmpty()) return List.of();
        List<Long> ids = nodes.stream().map(CachedNode::itemTreeId).toList();
        Map<Long, String> paths = pathResolver.pathsOf(ids);
        List<TreeNodeView> out = new ArrayList<>(nodes.size());
        for (CachedNode n : nodes) {
            out.add(new TreeNodeView(n, paths.getOrDefault(n.itemTreeId(), "")));
        }
        return List.copyOf(out);
    }
}

package com.myxcomp.ice.xtree.service;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.TreeCache;
import com.myxcomp.ice.xtree.common.TreeConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class DefaultPathResolver implements PathResolver {

    private static final Logger log = LoggerFactory.getLogger(DefaultPathResolver.class);

    /**
     * Defensive cap on the parent walk. Design §8 specifies 100; 10_000 is intentionally
     * larger to tolerate deep-but-valid trees without premature truncation.
     */
    private static final int MAX_TREE_DEPTH = 10_000;

    private static final String SEPARATOR = "/";

    private final TreeCache cache;

    public DefaultPathResolver(TreeCache cache) {
        this.cache = cache;
    }

    @Override
    public String pathOf(long itemTreeId) {
        return pathFor(itemTreeId, new HashMap<>());
    }

    @Override
    public Map<Long, String> pathsOf(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        Map<Long, String> memo = new HashMap<>();
        Map<Long, String> result = new HashMap<>();
        for (Long id : ids) {
            if (id == null) continue;
            result.put(id, pathFor(id, memo));
        }
        return result;
    }

    /**
     * Computes the path for {@code itemTreeId} using {@code memo} to reuse any ancestor paths
     * already resolved during this call. Fills {@code memo} for every node in the walked chain.
     */
    private String pathFor(long itemTreeId, Map<Long, String> memo) {
        String cached = memo.get(itemTreeId);
        if (cached != null) return cached;

        Optional<CachedNode> startOpt = cache.getById(itemTreeId);
        if (startOpt.isEmpty()) {
            memo.put(itemTreeId, "");
            return "";
        }

        List<CachedNode> chainLeafFirst = new ArrayList<>();
        String anchorPath = null;
        CachedNode cursor = startOpt.get();
        int steps = 0;
        while (cursor != null) {
            chainLeafFirst.add(cursor);
            if (cursor.parentId() == TreeConstants.ROOT_PARENT_ID) {
                break;
            }
            String memoForParent = memo.get(cursor.parentId());
            if (memoForParent != null) {
                anchorPath = memoForParent;
                break;
            }
            if (++steps > MAX_TREE_DEPTH) {
                log.warn("PathResolver: walk cap reached at id={}, possible cycle", itemTreeId);
                break;
            }
            CachedNode parent = cache.getById(cursor.parentId()).orElse(null);
            if (parent == null) {
                log.warn("PathResolver: missing ancestor parentId={} for id={} (originating id={})",
                        cursor.parentId(), cursor.itemTreeId(), itemTreeId);
                break;
            }
            cursor = parent;
        }

        StringBuilder accum = new StringBuilder(anchorPath == null ? "" : anchorPath);
        for (int i = chainLeafFirst.size() - 1; i >= 0; i--) {
            CachedNode node = chainLeafFirst.get(i);
            if (accum.length() > 0) accum.append(SEPARATOR);
            accum.append(node.name());
            memo.put(node.itemTreeId(), accum.toString());
        }

        return memo.getOrDefault(itemTreeId, "");
    }

}

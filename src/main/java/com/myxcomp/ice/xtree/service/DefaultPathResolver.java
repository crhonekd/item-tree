package com.myxcomp.ice.xtree.service;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.TreeCache;
import com.myxcomp.ice.xtree.common.TreeConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class DefaultPathResolver implements PathResolver {

    private static final Logger log = LoggerFactory.getLogger(DefaultPathResolver.class);

    /** Defensive cap on the parent walk; a healthy tree is well under this. */
    private static final int MAX_TREE_DEPTH = 10_000;

    private static final String SEPARATOR = "/";

    private final TreeCache cache;

    public DefaultPathResolver(TreeCache cache) {
        this.cache = cache;
    }

    @Override
    public String pathOf(long itemTreeId) {
        List<String> namesRootFirst = walkToRoot(itemTreeId);
        if (namesRootFirst.isEmpty()) return "";
        return String.join(SEPARATOR, namesRootFirst);
    }

    @Override
    public Map<Long, String> pathsOf(Collection<Long> ids) {
        throw new UnsupportedOperationException("pathsOf implemented in Task 8");
    }

    /**
     * Walks the parent chain from {@code itemTreeId} up to (but not including) the conceptual
     * root-parent (id 0). Returns the collected names in root-first order. Returns an empty list
     * if {@code itemTreeId} is not in the cache.
     */
    private List<String> walkToRoot(long itemTreeId) {
        Optional<CachedNode> start = cache.getById(itemTreeId);
        if (start.isEmpty()) return List.of();

        List<String> namesLeafFirst = new ArrayList<>();
        CachedNode cursor = start.get();
        int steps = 0;
        while (cursor != null) {
            namesLeafFirst.add(cursor.name());
            if (cursor.parentId() == TreeConstants.ROOT_PARENT_ID) {
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
        Collections.reverse(namesLeafFirst);
        return namesLeafFirst;
    }
}

package com.myxcomp.ice.xtree.cache;

import java.util.Map;
import java.util.Set;

public record TreeSnapshot(
        Map<Long, CachedNode> byId,
        Map<Long, Set<Long>> childrenByParent,
        Map<String, Set<Long>> foldersByName
) {}

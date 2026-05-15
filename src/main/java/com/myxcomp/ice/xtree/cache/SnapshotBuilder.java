package com.myxcomp.ice.xtree.cache;

import com.myxcomp.ice.xtree.common.Types;
import com.myxcomp.ice.xtree.persistence.StructuralRow;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class SnapshotBuilder {

    private final Map<Long, CachedNode> byId = new HashMap<>();
    private final Map<Long, Set<Long>> childrenByParent = new HashMap<>();
    private final Map<String, Set<Long>> foldersByName = new HashMap<>();

    public void accept(StructuralRow row) {
        CachedNode node = new CachedNode(
                row.itemTreeId(), row.parentId(), row.name(), row.type(),
                row.lastUpdate(), row.lastUpdateUser());
        byId.put(node.itemTreeId(), node);
        childrenByParent.computeIfAbsent(node.parentId(), k -> new HashSet<>())
                        .add(node.itemTreeId());
        if (Types.isFolder(node.type())) {
            foldersByName.computeIfAbsent(node.name(), k -> new HashSet<>())
                         .add(node.itemTreeId());
        }
    }

    public TreeSnapshot build() {
        return new TreeSnapshot(
                Map.copyOf(byId),
                Map.copyOf(childrenByParent),
                Map.copyOf(foldersByName));
    }
}

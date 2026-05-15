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

    /** Produces the snapshot. Do not call {@code accept} after calling this method. */
    public TreeSnapshot build() {
        Map<Long, Set<Long>> immutableChildren = new HashMap<>(childrenByParent.size());
        childrenByParent.forEach((k, v) -> immutableChildren.put(k, Set.copyOf(v)));

        Map<String, Set<Long>> immutableFolders = new HashMap<>(foldersByName.size());
        foldersByName.forEach((k, v) -> immutableFolders.put(k, Set.copyOf(v)));

        return new TreeSnapshot(
                Map.copyOf(byId),
                Map.copyOf(immutableChildren),
                Map.copyOf(immutableFolders));
    }
}

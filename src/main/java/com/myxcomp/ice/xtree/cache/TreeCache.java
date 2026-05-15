package com.myxcomp.ice.xtree.cache;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

public interface TreeCache {

    // ── Reads ─────────────────────────────────────────────────────────────
    Optional<CachedNode> getById(long id);
    List<CachedNode>     getChildren(long parentId);
    List<CachedNode>     getSubtreeFlat(long rootId);

    /**
     * Returns the trimmed tree view for the given home folder.
     * Algorithm per design §8. Implemented in Phase 6.
     */
    List<CachedNode>     getTreeView(long homeFolderId);

    Optional<CachedNode> findHomeFolder(String userName);
    Optional<CachedNode> searchById(long id);
    List<CachedNode>     searchByName(String needle, OptionalInt limit);
    boolean              isAncestor(long candidateAncestorId, long nodeId);
    boolean              exists(long id);
    boolean              isFolder(long id);
    int                  size();

    // ── Mutations ─────────────────────────────────────────────────────────
    void applyCreate(CachedNode node);
    void applyMetadataUpdate(long id, Instant lastUpdate, String lastUpdateUser);
    void applyMove(long id, long newParentId, Instant lastUpdate, String lastUpdateUser);
    void applyRename(long id, String newName, Instant lastUpdate, String lastUpdateUser);
    void applyDelete(Set<Long> ids);
    void replaceAll(TreeSnapshot newSnapshot);
}

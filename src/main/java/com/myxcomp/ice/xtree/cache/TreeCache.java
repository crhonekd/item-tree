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
    /**
     * Returns root + all descendants of the given node, in BFS order, as a defensive-copy flat list.
     * Returns an empty list if {@code rootId} is not in the cache.
     */
    List<CachedNode>     getSubtreeFlatFull(long rootId);

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
    /** Caller must pass the complete descendant set; partial sets leave dangling childrenByParent entries. */
    void applyDelete(Set<Long> ids);
    void replaceAll(TreeSnapshot newSnapshot);

    /**
     * Applies a BFS-ordered batch of newly created nodes — a copied subtree — under
     * a single write lock. Idempotent and tolerant per the {@link #applyCreate}
     * contract (design §4): id-already-present is upserted; missing parent
     * references are still recorded (apply* tolerance). Atomic from any concurrent
     * reader's view.
     *
     * @param newNodes BFS-ordered list, never null, may be empty; elements never null
     */
    void applyCopy(List<CachedNode> newNodes);

    /**
     * Returns an immutable copy of the current cache state, captured under the read lock.
     * Used by the full-reload drift diff (design §7) and by tests that need a frozen view.
     */
    TreeSnapshot snapshot();
}

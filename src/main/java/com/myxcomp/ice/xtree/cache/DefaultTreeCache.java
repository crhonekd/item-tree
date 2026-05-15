package com.myxcomp.ice.xtree.cache;

import com.myxcomp.ice.xtree.common.TreeConstants;
import com.myxcomp.ice.xtree.common.Types;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * Default in-memory implementation of {@link TreeCache}.
 *
 * <p>Holds the full structural tree (no XML/JSON payload) in three indexes protected by a
 * single {@link ReentrantReadWriteLock}. All read methods return defensive copies; the write
 * lock is never held across I/O.
 */
@Component
public class DefaultTreeCache implements TreeCache {

    private static final Logger log = LoggerFactory.getLogger(DefaultTreeCache.class);

    /** Ceiling for ancestor-walk cycle detection; effective cap is min(cache-size+1, this). */
    private static final int MAX_ANCESTOR_WALK = 10_000;

    /** Defensive cap for the chain walk in {@link #getTreeView}; effective cap is min(cache-size+1, this). */
    private static final int MAX_TREE_DEPTH = 10_000;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    // Replaced atomically under write lock in replaceAll:
    private Map<Long, CachedNode>     byId             = new ConcurrentHashMap<>();
    private Map<Long, Set<Long>>      childrenByParent = new ConcurrentHashMap<>();
    private Map<String, Set<Long>>    foldersByName    = new ConcurrentHashMap<>();

    // ── Read methods ──────────────────────────────────────────────────────────

    @Override
    public Optional<CachedNode> getById(long id) {
        lock.readLock().lock();
        try {
            return Optional.ofNullable(byId.get(id));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<CachedNode> getChildren(long parentId) {
        lock.readLock().lock();
        try {
            Set<Long> childIds = childrenByParent.get(parentId);
            if (childIds == null || childIds.isEmpty()) {
                return List.of();
            }
            return childIds.stream()
                    .map(byId::get)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toUnmodifiableList());
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<CachedNode> getSubtreeFlat(long rootId) {
        lock.readLock().lock();
        try {
            if (!byId.containsKey(rootId)) {
                return List.of();
            }
            List<CachedNode> result = new ArrayList<>();
            Deque<Long> queue = new ArrayDeque<>();
            queue.add(rootId);
            while (!queue.isEmpty()) {
                long current = queue.poll();
                CachedNode node = byId.get(current);
                if (node != null) {
                    result.add(node);
                    Set<Long> children = childrenByParent.get(current);
                    if (children != null) {
                        queue.addAll(children);
                    }
                }
            }
            return Collections.unmodifiableList(result);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<CachedNode> getTreeView(long homeFolderId) {
        lock.readLock().lock();
        try {
            CachedNode home = byId.get(homeFolderId);
            if (home == null) {
                throw new IllegalArgumentException("Home folder not found in cache: " + homeFolderId);
            }

            LinkedHashSet<Long> resultIds = new LinkedHashSet<>();
            addSkeletonFolders(resultIds);
            addChainRootToHome(resultIds, home, homeFolderId);
            resultIds.addAll(childrenByParent.getOrDefault(homeFolderId, Set.of()));

            return resultIds.stream()
                    .map(byId::get)
                    .filter(Objects::nonNull)
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Adds folder ids at depths 0, 1, 2 (root, its folder children, their folder grandchildren). */
    private void addSkeletonFolders(LinkedHashSet<Long> sink) {
        for (Long depth0Id : childrenByParent.getOrDefault(TreeConstants.ROOT_PARENT_ID, Set.of())) {
            CachedNode depth0 = byId.get(depth0Id);
            if (depth0 == null || !Types.isFolder(depth0.type())) continue;
            sink.add(depth0Id);
            for (Long depth1Id : childrenByParent.getOrDefault(depth0Id, Set.of())) {
                CachedNode depth1 = byId.get(depth1Id);
                if (depth1 == null || !Types.isFolder(depth1.type())) continue;
                sink.add(depth1Id);
                for (Long depth2Id : childrenByParent.getOrDefault(depth1Id, Set.of())) {
                    CachedNode depth2 = byId.get(depth2Id);
                    if (depth2 == null || !Types.isFolder(depth2.type())) continue;
                    sink.add(depth2Id);
                }
            }
        }
    }

    /** Walks home → root, then inserts the chain into {@code sink} in root → home order. */
    private void addChainRootToHome(LinkedHashSet<Long> sink, CachedNode home, long homeFolderId) {
        List<Long> chain = new ArrayList<>();
        int maxWalk = Math.min(byId.size() + 1, MAX_TREE_DEPTH);
        CachedNode cursor = home;
        int steps = 0;
        while (cursor != null) {
            chain.add(cursor.itemTreeId());
            if (cursor.parentId() == TreeConstants.ROOT_PARENT_ID) {
                break;
            }
            if (++steps > maxWalk) {
                log.warn("getTreeView: ancestor-walk cap reached at homeFolderId={}, possible cycle",
                        homeFolderId);
                break;
            }
            CachedNode parent = byId.get(cursor.parentId());
            if (parent == null) {
                log.warn("getTreeView: missing ancestor parentId={} for nodeId={} (homeFolderId={})",
                        cursor.parentId(), cursor.itemTreeId(), homeFolderId);
                break;
            }
            cursor = parent;
        }
        Collections.reverse(chain);
        sink.addAll(chain);
    }

    @Override
    public Optional<CachedNode> findHomeFolder(String userName) {
        lock.readLock().lock();
        try {
            Set<Long> candidates = foldersByName.getOrDefault(userName, Set.of());
            if (candidates.isEmpty()) {
                return Optional.empty();
            }
            if (candidates.size() > 1) {
                log.warn("Multiple home folders found for user '{}': ids={}", userName, candidates);
                // Iteration order over ConcurrentHashMap.newKeySet() is non-deterministic; any match is returned.
            }
            for (Long id : candidates) {
                CachedNode node = byId.get(id);
                if (node != null) {
                    return Optional.of(node);
                }
            }
            return Optional.empty();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Optional<CachedNode> searchById(long id) {
        return getById(id);
    }

    @Override
    public List<CachedNode> searchByName(String needle, OptionalInt limit) {
        Objects.requireNonNull(needle, "needle");
        lock.readLock().lock();
        try {
            String lowerNeedle = needle.toLowerCase(Locale.ROOT);
            var stream = byId.values().stream()
                    .filter(n -> n.name().toLowerCase(Locale.ROOT).contains(lowerNeedle));
            if (limit.isPresent()) {
                stream = stream.limit(limit.getAsInt());
            }
            return stream.collect(Collectors.toUnmodifiableList());
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public boolean isAncestor(long candidateAncestorId, long nodeId) {
        if (candidateAncestorId == nodeId) return false;
        lock.readLock().lock();
        try {
            int maxWalk = Math.min(byId.size() + 1, MAX_ANCESTOR_WALK);
            CachedNode node = byId.get(nodeId);
            int steps = 0;
            while (node != null) {
                long parentId = node.parentId();
                if (parentId == TreeConstants.ROOT_PARENT_ID) {
                    return false;
                }
                if (parentId == candidateAncestorId) {
                    return true;
                }
                if (++steps > maxWalk) {
                    log.warn("Cycle detected in ancestor walk at nodeId={}, candidateAncestorId={}",
                            nodeId, candidateAncestorId);
                    return false;
                }
                node = byId.get(parentId);
            }
            return false;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public boolean exists(long id) {
        lock.readLock().lock();
        try {
            return byId.containsKey(id);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public boolean isFolder(long id) {
        lock.readLock().lock();
        try {
            CachedNode node = byId.get(id);
            return node != null && Types.isFolder(node.type());
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public int size() {
        lock.readLock().lock();
        try {
            return byId.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    // ── Write methods ─────────────────────────────────────────────────────────

    @Override
    public void applyCreate(CachedNode node) {
        Objects.requireNonNull(node, "node");
        lock.writeLock().lock();
        try {
            CachedNode existing = byId.get(node.itemTreeId());
            if (existing != null) {
                // Remove from old parent's children index
                removeFromChildren(existing.parentId(), existing.itemTreeId());
                // Remove from foldersByName if it was a folder
                if (Types.isFolder(existing.type())) {
                    removeFromFoldersByName(existing.name(), existing.itemTreeId());
                }
            }
            byId.put(node.itemTreeId(), node);
            childrenByParent
                    .computeIfAbsent(node.parentId(), k -> ConcurrentHashMap.newKeySet())
                    .add(node.itemTreeId());
            if (Types.isFolder(node.type())) {
                foldersByName
                        .computeIfAbsent(node.name(), k -> ConcurrentHashMap.newKeySet())
                        .add(node.itemTreeId());
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void applyMetadataUpdate(long id, Instant lastUpdate, String lastUpdateUser) {
        Objects.requireNonNull(lastUpdate, "lastUpdate");
        Objects.requireNonNull(lastUpdateUser, "lastUpdateUser");
        lock.writeLock().lock();
        try {
            CachedNode existing = byId.get(id);
            if (existing == null) {
                log.warn("applyMetadataUpdate: id={} not found, skipping", id);
                return;
            }
            byId.put(id, new CachedNode(
                    existing.itemTreeId(),
                    existing.parentId(),
                    existing.name(),
                    existing.type(),
                    lastUpdate,
                    lastUpdateUser));
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void applyMove(long id, long newParentId, Instant lastUpdate, String lastUpdateUser) {
        Objects.requireNonNull(lastUpdate, "lastUpdate");
        Objects.requireNonNull(lastUpdateUser, "lastUpdateUser");
        lock.writeLock().lock();
        try {
            CachedNode existing = byId.get(id);
            if (existing == null) {
                log.warn("applyMove: id={} not found, skipping", id);
                return;
            }
            if (newParentId != TreeConstants.ROOT_PARENT_ID && !byId.containsKey(newParentId)) {
                log.warn("applyMove: newParentId={} not found in cache for id={}, skipping",
                        newParentId, id);
                return;
            }
            removeFromChildren(existing.parentId(), id);
            childrenByParent
                    .computeIfAbsent(newParentId, k -> ConcurrentHashMap.newKeySet())
                    .add(id);
            byId.put(id, new CachedNode(
                    existing.itemTreeId(),
                    newParentId,
                    existing.name(),
                    existing.type(),
                    lastUpdate,
                    lastUpdateUser));
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void applyRename(long id, String newName, Instant lastUpdate, String lastUpdateUser) {
        Objects.requireNonNull(newName, "newName");
        Objects.requireNonNull(lastUpdate, "lastUpdate");
        Objects.requireNonNull(lastUpdateUser, "lastUpdateUser");
        lock.writeLock().lock();
        try {
            CachedNode existing = byId.get(id);
            if (existing == null) {
                log.warn("applyRename: id={} not found, skipping", id);
                return;
            }
            if (Types.isFolder(existing.type())) {
                removeFromFoldersByName(existing.name(), id);
                foldersByName
                        .computeIfAbsent(newName, k -> ConcurrentHashMap.newKeySet())
                        .add(id);
            }
            byId.put(id, new CachedNode(
                    existing.itemTreeId(),
                    existing.parentId(),
                    newName,
                    existing.type(),
                    lastUpdate,
                    lastUpdateUser));
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void applyDelete(Set<Long> ids) {
        Objects.requireNonNull(ids, "ids");
        lock.writeLock().lock();
        try {
            // Caller must pass the complete descendant set; partial sets leave dangling childrenByParent entries.
            for (Long id : ids) {
                CachedNode node = byId.remove(id);
                if (node != null) {
                    removeFromChildren(node.parentId(), id);
                    if (Types.isFolder(node.type())) {
                        removeFromFoldersByName(node.name(), id);
                    }
                }
                // Remove the node's own children-index entry (its children were also deleted)
                childrenByParent.remove(id);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void replaceAll(TreeSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        // Deep-copy OUTSIDE the write lock to keep lock duration minimal (no I/O, just map copies)
        Map<Long, CachedNode> newById = new ConcurrentHashMap<>(snapshot.byId());

        Map<Long, Set<Long>> newChildrenByParent = new ConcurrentHashMap<>();
        for (Map.Entry<Long, Set<Long>> entry : snapshot.childrenByParent().entrySet()) {
            Set<Long> copy = ConcurrentHashMap.newKeySet();
            copy.addAll(entry.getValue());
            newChildrenByParent.put(entry.getKey(), copy);
        }

        Map<String, Set<Long>> newFoldersByName = new ConcurrentHashMap<>();
        for (Map.Entry<String, Set<Long>> entry : snapshot.foldersByName().entrySet()) {
            Set<Long> copy = ConcurrentHashMap.newKeySet();
            copy.addAll(entry.getValue());
            newFoldersByName.put(entry.getKey(), copy);
        }

        // Acquire write lock only to swap the three references atomically
        lock.writeLock().lock();
        try {
            this.byId             = newById;
            this.childrenByParent = newChildrenByParent;
            this.foldersByName    = newFoldersByName;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void removeFromChildren(long parentId, long childId) {
        Set<Long> siblings = childrenByParent.get(parentId);
        if (siblings != null) {
            siblings.remove(childId);
            if (siblings.isEmpty()) {
                childrenByParent.remove(parentId, siblings);
            }
        }
    }

    private void removeFromFoldersByName(String name, long id) {
        Set<Long> folders = foldersByName.get(name);
        if (folders != null) {
            folders.remove(id);
            if (folders.isEmpty()) {
                foldersByName.remove(name, folders);
            }
        }
    }
}

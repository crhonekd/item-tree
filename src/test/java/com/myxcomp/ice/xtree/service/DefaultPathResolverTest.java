package com.myxcomp.ice.xtree.service;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.DefaultTreeCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultPathResolverTest {

    DefaultTreeCache cache;
    DefaultPathResolver resolver;
    static final Instant T = Instant.EPOCH;

    static CachedNode folder(long id, long parentId, String name) {
        return new CachedNode(id, parentId, name, "Folder", T, "sys");
    }

    static CachedNode leaf(long id, long parentId, String name) {
        return new CachedNode(id, parentId, name, "Report", T, "sys");
    }

    static void loadFixture(DefaultTreeCache c) {
        c.applyCreate(folder(1L,  0L, "root"));
        c.applyCreate(folder(2L,  1L, "Users"));
        c.applyCreate(folder(10L, 2L, "testuser1"));
        c.applyCreate(folder(12L, 2L, "deepuser"));
        c.applyCreate(folder(20L, 12L, "L2"));
        c.applyCreate(folder(21L, 20L, "L3"));
        c.applyCreate(folder(22L, 21L, "L4"));
        c.applyCreate(leaf  (110L, 10L, "MyReport"));
        c.applyCreate(leaf  (210L, 22L, "DeepReport"));
    }

    @BeforeEach
    void setUp() {
        cache = new DefaultTreeCache();
        resolver = new DefaultPathResolver(cache);
    }

    @Nested
    class PathOf {

        @Test
        void rootReturnsItsName() {
            loadFixture(cache);
            assertThat(resolver.pathOf(1L)).isEqualTo("root");
        }

        @Test
        void depth1ReturnsRootSlashName() {
            loadFixture(cache);
            assertThat(resolver.pathOf(2L)).isEqualTo("root/Users");
        }

        @Test
        void depth2ReturnsThreeSegments() {
            loadFixture(cache);
            assertThat(resolver.pathOf(10L)).isEqualTo("root/Users/testuser1");
        }

        @Test
        void deepLeafReturnsFullPath() {
            loadFixture(cache);
            assertThat(resolver.pathOf(210L))
                    .isEqualTo("root/Users/deepuser/L2/L3/L4/DeepReport");
        }

        @Test
        void unknownIdReturnsEmptyString() {
            loadFixture(cache);
            assertThat(resolver.pathOf(999L)).isEmpty();
        }

        @Test
        void orphanParentMidChainReturnsPartialPath() {
            // OrphanA's parent (999) is not in the cache.
            // pathOf walks one step (collects "OrphanA"), finds parent missing, stops.
            // Returns the partial path with just the one name — no root prefix.
            cache.applyCreate(folder(50L, 999L, "OrphanA"));

            assertThat(resolver.pathOf(50L)).isEqualTo("OrphanA");
        }

        @Test
        void cycleInParentChainTerminatesWithPartialPath() {
            // Two-node cycle: A.parent=B, B.parent=A.
            cache.applyCreate(folder(100L, 200L, "A"));
            cache.applyCreate(folder(200L, 100L, "B"));

            // Must terminate (cap hit) without throwing. Result is non-null.
            assertThat(resolver.pathOf(100L)).isNotNull();
            assertThat(resolver.pathOf(200L)).isNotNull();
        }
    }

    @Nested
    class PathsOf {

        @Test
        void emptyInputReturnsEmptyMap() {
            assertThat(resolver.pathsOf(List.of())).isEmpty();
        }

        @Test
        void nullInputReturnsEmptyMap() {
            assertThat(resolver.pathsOf(null)).isEmpty();
        }

        @Test
        void singleIdReturnsSingletonMap() {
            loadFixture(cache);
            Map<Long, String> result = resolver.pathsOf(List.of(10L));
            assertThat(result).containsExactly(Map.entry(10L, "root/Users/testuser1"));
        }

        @Test
        void batchReturnsCorrectPathPerId() {
            loadFixture(cache);

            Map<Long, String> result = resolver.pathsOf(List.of(1L, 2L, 10L, 110L, 210L));

            assertThat(result).containsOnly(
                    Map.entry(1L,   "root"),
                    Map.entry(2L,   "root/Users"),
                    Map.entry(10L,  "root/Users/testuser1"),
                    Map.entry(110L, "root/Users/testuser1/MyReport"),
                    Map.entry(210L, "root/Users/deepuser/L2/L3/L4/DeepReport"));
        }

        @Test
        void duplicateIdsCollapseToSingleEntry() {
            loadFixture(cache);
            Map<Long, String> result = resolver.pathsOf(List.of(10L, 10L, 10L));
            assertThat(result).containsExactly(Map.entry(10L, "root/Users/testuser1"));
        }

        @Test
        void unknownIdMapsToEmptyString() {
            loadFixture(cache);
            Map<Long, String> result = resolver.pathsOf(List.of(10L, 999L));
            assertThat(result).containsOnly(
                    Map.entry(10L,  "root/Users/testuser1"),
                    Map.entry(999L, ""));
        }

        @Test
        void memoisationLimitsGetByIdCallsForSharedAncestorChain() {
            // root → A → B → C → D, with 50 leaves parented by D.
            // Without memoisation: 50 leaves × 4 ancestor lookups = 200 extra getById calls.
            // With memoisation: walk the chain once (4 ancestor calls), then 50 leaf lookups only.
            DefaultTreeCache realCache = new DefaultTreeCache();
            realCache.applyCreate(folder(1L, 0L, "root"));
            realCache.applyCreate(folder(2L, 1L, "A"));
            realCache.applyCreate(folder(3L, 2L, "B"));
            realCache.applyCreate(folder(4L, 3L, "C"));
            realCache.applyCreate(folder(5L, 4L, "D"));
            List<Long> leafIds = new java.util.ArrayList<>();
            for (long i = 100; i < 150; i++) {
                realCache.applyCreate(leaf(i, 5L, "leaf" + i));
                leafIds.add(i);
            }

            CountingTreeCache counting = new CountingTreeCache(realCache);
            DefaultPathResolver memoResolver = new DefaultPathResolver(counting);

            Map<Long, String> paths = memoResolver.pathsOf(leafIds);

            assertThat(paths).hasSize(50);
            assertThat(paths.get(100L)).isEqualTo("root/A/B/C/D/leaf100");
            assertThat(paths.get(149L)).isEqualTo("root/A/B/C/D/leaf149");
            // Upper bound: 50 leaf lookups + ~4 ancestor lookups on the first walk.
            // Allow slack of 10 for any incidental calls.
            assertThat(counting.getByIdCount())
                    .as("getById call count must be ~O(N + chain), not O(N * chain)")
                    .isLessThanOrEqualTo(60);
        }
    }

    static class CountingTreeCache implements com.myxcomp.ice.xtree.cache.TreeCache {
        private final com.myxcomp.ice.xtree.cache.TreeCache delegate;
        private int getByIdCount = 0;

        CountingTreeCache(com.myxcomp.ice.xtree.cache.TreeCache delegate) {
            this.delegate = delegate;
        }

        int getByIdCount() { return getByIdCount; }

        @Override public java.util.Optional<CachedNode> getById(long id) {
            getByIdCount++;
            return delegate.getById(id);
        }
        @Override public java.util.List<CachedNode> getChildren(long parentId) { return delegate.getChildren(parentId); }
        @Override public java.util.List<CachedNode> getSubtreeFlat(long rootId) { return delegate.getSubtreeFlat(rootId); }
        @Override public java.util.List<CachedNode> getTreeView(long homeFolderId) { return delegate.getTreeView(homeFolderId); }
        @Override public java.util.Optional<CachedNode> findHomeFolder(String userName) { return delegate.findHomeFolder(userName); }
        @Override public java.util.Optional<CachedNode> searchById(long id) { return delegate.searchById(id); }
        @Override public java.util.List<CachedNode> searchByName(String needle, java.util.OptionalInt limit) { return delegate.searchByName(needle, limit); }
        @Override public boolean isAncestor(long candidateAncestorId, long nodeId) { return delegate.isAncestor(candidateAncestorId, nodeId); }
        @Override public boolean exists(long id) { return delegate.exists(id); }
        @Override public boolean isFolder(long id) { return delegate.isFolder(id); }
        @Override public int size() { return delegate.size(); }
        @Override public void applyCreate(CachedNode node) { delegate.applyCreate(node); }
        @Override public void applyMetadataUpdate(long id, java.time.Instant lastUpdate, String lastUpdateUser) { delegate.applyMetadataUpdate(id, lastUpdate, lastUpdateUser); }
        @Override public void applyMove(long id, long newParentId, java.time.Instant lastUpdate, String lastUpdateUser) { delegate.applyMove(id, newParentId, lastUpdate, lastUpdateUser); }
        @Override public void applyRename(long id, String newName, java.time.Instant lastUpdate, String lastUpdateUser) { delegate.applyRename(id, newName, lastUpdate, lastUpdateUser); }
        @Override public void applyDelete(java.util.Set<Long> ids) { delegate.applyDelete(ids); }
        @Override public void replaceAll(com.myxcomp.ice.xtree.cache.TreeSnapshot newSnapshot) { delegate.replaceAll(newSnapshot); }
    }
}

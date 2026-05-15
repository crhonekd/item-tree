package com.myxcomp.ice.xtree.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultTreeCacheTest {

    DefaultTreeCache cache;
    static final Instant T = Instant.EPOCH;

    static CachedNode folder(long id, long parentId, String name) {
        return new CachedNode(id, parentId, name, "Folder", T, "sys");
    }

    static CachedNode leaf(long id, long parentId, String name) {
        return new CachedNode(id, parentId, name, "Report", T, "sys");
    }

    /** Loads root(1) → Users(2) → testuser1(3) → report1(100) into the given cache. */
    static void loadStandardTree(DefaultTreeCache c) {
        c.applyCreate(folder(1L, 0L, "root"));
        c.applyCreate(folder(2L, 1L, "Users"));
        c.applyCreate(folder(3L, 2L, "testuser1"));
        c.applyCreate(leaf(100L, 3L, "MyReport"));
    }

    @BeforeEach
    void setUp() {
        cache = new DefaultTreeCache();
    }

    @Nested
    class EmptyCache {
        @Test
        void getByIdReturnsEmptyForUnknownId() {
            assertThat(cache.getById(999L)).isEmpty();
        }

        @Test
        void sizeIsZero() {
            assertThat(cache.size()).isZero();
        }

        @Test
        void existsReturnsFalse() {
            assertThat(cache.exists(1L)).isFalse();
        }
    }

    @Nested
    class ApplyCreate {
        @Test
        void createdNodeIsRetrievableById() {
            cache.applyCreate(folder(1L, 0L, "root"));
            Optional<CachedNode> found = cache.getById(1L);
            assertThat(found).isPresent();
            assertThat(found.get().name()).isEqualTo("root");
        }

        @Test
        void sizeIncreasesOnCreate() {
            cache.applyCreate(folder(1L, 0L, "root"));
            cache.applyCreate(folder(2L, 1L, "child"));
            assertThat(cache.size()).isEqualTo(2);
        }

        @Test
        void applyCreateTwiceWithSameIdUpsertsNode() {
            cache.applyCreate(folder(1L, 0L, "original"));
            cache.applyCreate(folder(1L, 0L, "replaced"));
            assertThat(cache.getById(1L).get().name()).isEqualTo("replaced");
            assertThat(cache.size()).isEqualTo(1);
        }

        @Test
        void upsertUpdatesChildrenByParentIndex() {
            cache.applyCreate(folder(1L, 0L, "root"));
            cache.applyCreate(folder(2L, 1L, "child"));
            // Move child from parent 1 to parent 0 via upsert
            cache.applyCreate(new CachedNode(2L, 0L, "child", "Folder", T, "sys"));
            assertThat(cache.getChildren(0L)).extracting(CachedNode::itemTreeId).contains(2L);
            assertThat(cache.getChildren(1L)).extracting(CachedNode::itemTreeId).doesNotContain(2L);
        }
    }
}

package com.myxcomp.ice.xtree.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class DefaultTreeCacheGetTreeViewTest {

    DefaultTreeCache cache;
    static final Instant T = Instant.EPOCH;

    static CachedNode folder(long id, long parentId, String name) {
        return new CachedNode(id, parentId, name, "Folder", T, "sys");
    }

    static CachedNode leaf(long id, long parentId, String name) {
        return new CachedNode(id, parentId, name, "Report", T, "sys");
    }

    /**
     * Standard fixture:
     *   root(1)
     *     ├─ Users(2)
     *     │    ├─ testuser1(10)
     *     │    │    └─ MyReport(110)
     *     │    └─ deepuser(12)
     *     │         └─ L2(20) → L3(21) → L4(22) → L5(23) → L6(24) → leafItem(25)
     *     ├─ Reports(3)
     *     │    ├─ ReportSubA(30)   [folder — depth-2, in skeleton]
     *     │    └─ Report1(41)      [leaf — must NOT be in skeleton]
     *     ├─ Filters(4)
     *     └─ Datasets(6)
     *          └─ DrillDownSet1(40)  [leaf]
     */
    static void loadFixture(DefaultTreeCache c) {
        c.applyCreate(folder(1L,  0L, "root"));
        c.applyCreate(folder(2L,  1L, "Users"));
        c.applyCreate(folder(3L,  1L, "Reports"));
        c.applyCreate(folder(4L,  1L, "Filters"));
        c.applyCreate(folder(6L,  1L, "Datasets"));
        c.applyCreate(folder(10L, 2L, "testuser1"));
        c.applyCreate(folder(12L, 2L, "deepuser"));
        c.applyCreate(folder(20L, 12L, "L2"));
        c.applyCreate(folder(21L, 20L, "L3"));
        c.applyCreate(folder(22L, 21L, "L4"));
        c.applyCreate(folder(23L, 22L, "L5"));
        c.applyCreate(folder(24L, 23L, "L6"));
        c.applyCreate(folder(30L, 3L, "ReportSubA"));
        c.applyCreate(leaf  (41L, 3L, "Report1"));
        c.applyCreate(leaf  (40L, 6L, "DrillDownSet1"));
        c.applyCreate(leaf  (110L, 10L, "MyReport"));
        c.applyCreate(leaf  (25L, 24L, "leafItem"));
    }

    @BeforeEach
    void setUp() {
        cache = new DefaultTreeCache();
    }

    @Nested
    class HappyPath {

        @Test
        void depth2HomeFolderReturnsSkeletonChainAndHomeChildren() {
            loadFixture(cache);

            List<CachedNode> view = cache.getTreeView(10L);

            // skeleton (depths 0,1,2 folders): 1, 2, 3, 4, 6, 10, 12, 30
            // chain (root → home):             1, 2, 10  [all already in skeleton]
            // home children of testuser1:      110
            assertThat(view).extracting(CachedNode::itemTreeId)
                    .containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 6L, 10L, 12L, 30L, 110L);
        }

        @Test
        void depth0HomeFolderIsTheRoot() {
            loadFixture(cache);

            List<CachedNode> view = cache.getTreeView(1L);

            // Skeleton: root(1) + depth-1 folders (2,3,4,6) + depth-2 folders (10,12,30).
            // Chain is just [1]; home children of root are (2,3,4,6) — all already in skeleton.
            assertThat(view).extracting(CachedNode::itemTreeId)
                    .containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 6L, 10L, 12L, 30L);
        }

        @Test
        void depth1HomeFolderReportsAddsNonFolderChild() {
            loadFixture(cache);

            // Reports (id=3) is at depth 1. Its direct children are ReportSubA(30, folder)
            // and Report1(41, leaf). Both must appear in the result via home-children.
            List<CachedNode> view = cache.getTreeView(3L);

            assertThat(view).extracting(CachedNode::itemTreeId)
                    .containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 6L, 10L, 12L, 30L, 41L);
        }

        @Test
        void deepHomeFolderL6IncludesFullChainAndItsChildren() {
            loadFixture(cache);

            // L6 (id=24) is at depth 7: root(1)→Users(2)→deepuser(12)→L2(20)→L3(21)→L4(22)→L5(23)→L6(24).
            // Its child is leafItem(25).
            List<CachedNode> view = cache.getTreeView(24L);

            assertThat(view).extracting(CachedNode::itemTreeId)
                    .containsExactlyInAnyOrder(
                            1L, 2L, 3L, 4L, 6L, 10L, 12L, 30L,   // skeleton (depths 0-2)
                            20L, 21L, 22L, 23L, 24L,              // chain segment beyond skeleton
                            25L                                    // L6's child
                    );
        }

        @Test
        void chainAppearsInRootToHomeOrder() {
            loadFixture(cache);

            List<Long> ids = cache.getTreeView(24L).stream().map(CachedNode::itemTreeId).toList();

            // Chain root→L6: 1, 2, 12, 20, 21, 22, 23, 24. They must appear in that relative order.
            // (Not necessarily contiguous — skeleton elements 3, 4, 6, 10, 30 may be interleaved.)
            List<Long> chainOrder = List.of(1L, 2L, 12L, 20L, 21L, 22L, 23L, 24L);
            List<Integer> positions = chainOrder.stream().map(ids::indexOf).toList();
            assertThat(positions).doesNotContain(-1); // every chain id is present
            for (int i = 1; i < positions.size(); i++) {
                assertThat(positions.get(i))
                        .as("chain element %s must appear after %s", chainOrder.get(i), chainOrder.get(i - 1))
                        .isGreaterThan(positions.get(i - 1));
            }
        }
    }
}

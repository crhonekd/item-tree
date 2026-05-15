package com.myxcomp.ice.xtree.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Nested
    class ReadMethods {

        @BeforeEach
        void loadTree() {
            loadStandardTree(cache); // loads root(1,0) → Users(2,1) → testuser1(3,2) → MyReport(100,3)
        }

        @Test
        void getChildrenReturnsDirectChildrenOnly() {
            List<CachedNode> children = cache.getChildren(1L);
            assertThat(children).extracting(CachedNode::itemTreeId).containsExactly(2L);
        }

        @Test
        void getChildrenOfUnknownParentReturnsEmpty() {
            assertThat(cache.getChildren(999L)).isEmpty();
        }

        @Test
        void getSubtreeFlatIncludesRootAndAllDescendants() {
            List<CachedNode> subtree = cache.getSubtreeFlat(2L);
            assertThat(subtree).extracting(CachedNode::itemTreeId)
                    .containsExactlyInAnyOrder(2L, 3L, 100L);
        }

        @Test
        void getSubtreeFlatOnLeafReturnsOnlyThatNode() {
            List<CachedNode> subtree = cache.getSubtreeFlat(100L);
            assertThat(subtree).extracting(CachedNode::itemTreeId).containsExactly(100L);
        }

        @Test
        void getSubtreeFlatOnMissingIdReturnsEmpty() {
            assertThat(cache.getSubtreeFlat(999L)).isEmpty();
        }

        @Test
        void findHomeFolderReturnsMatchingFolder() {
            Optional<CachedNode> home = cache.findHomeFolder("testuser1");
            assertThat(home).isPresent();
            assertThat(home.get().itemTreeId()).isEqualTo(3L);
        }

        @Test
        void findHomeFolderReturnsEmptyForUnknownUser() {
            assertThat(cache.findHomeFolder("nobody")).isEmpty();
        }

        @Test
        void searchByIdReturnsNodeForKnownId() {
            assertThat(cache.searchById(100L)).isPresent();
        }

        @Test
        void searchByIdReturnsEmptyForUnknownId() {
            assertThat(cache.searchById(999L)).isEmpty();
        }

        @Test
        void searchByNameFindsMatchingNodes() {
            List<CachedNode> results = cache.searchByName("report", OptionalInt.empty());
            assertThat(results).extracting(CachedNode::name).containsExactly("MyReport");
        }

        @Test
        void searchByNameIsCaseInsensitive() {
            assertThat(cache.searchByName("MYREPORT", OptionalInt.empty())).hasSize(1);
        }

        @Test
        void searchByNameRespectsLimit() {
            cache.applyCreate(leaf(101L, 3L, "OtherReport"));
            List<CachedNode> results = cache.searchByName("report", OptionalInt.of(1));
            assertThat(results).hasSize(1);
        }

        @Test
        void searchByNameWithNullThrowsNpe() {
            assertThatNullPointerException()
                    .isThrownBy(() -> cache.searchByName(null, OptionalInt.empty()));
        }

        @Test
        void isFolderReturnsTrueForFolderNode() {
            assertThat(cache.isFolder(1L)).isTrue();
        }

        @Test
        void isFolderReturnsFalseForLeafNode() {
            assertThat(cache.isFolder(100L)).isFalse();
        }

        @Test
        void isFolderReturnsFalseForMissingId() {
            assertThat(cache.isFolder(999L)).isFalse();
        }

        @Test
        void isAncestorReturnsTrueForDirectAncestor() {
            assertThat(cache.isAncestor(1L, 3L)).isTrue();
        }

        @Test
        void isAncestorReturnsTrueForIndirectAncestor() {
            assertThat(cache.isAncestor(1L, 100L)).isTrue();
        }

        @Test
        void isAncestorReturnsFalseForNonAncestor() {
            assertThat(cache.isAncestor(3L, 1L)).isFalse();
        }

        @Test
        void isAncestorReturnsFalseForSelf() {
            assertThat(cache.isAncestor(1L, 1L)).isFalse();
        }

        @Test
        void getChildrenResultIsUnmodifiable() {
            List<CachedNode> children = cache.getChildren(1L);
            assertThatThrownBy(() -> children.add(folder(99L, 1L, "x")))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void getSubtreeFlatResultIsUnmodifiable() {
            List<CachedNode> subtree = cache.getSubtreeFlat(1L);
            assertThatThrownBy(() -> subtree.add(folder(99L, 1L, "x")))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    class ApplyMetadataUpdate {

        @Test
        void updatesTimestampAndUserOnExistingNode() {
            cache.applyCreate(folder(1L, 0L, "root"));
            Instant newTime = Instant.ofEpochSecond(100);
            cache.applyMetadataUpdate(1L, newTime, "editor");

            CachedNode updated = cache.getById(1L).get();
            assertThat(updated.lastUpdate()).isEqualTo(newTime);
            assertThat(updated.lastUpdateUser()).isEqualTo("editor");
            assertThat(updated.name()).isEqualTo("root");
            assertThat(updated.type()).isEqualTo("Folder");
        }

        @Test
        void onMissingIdDoesNotThrowAndCacheIsUnchanged() {
            cache.applyMetadataUpdate(999L, Instant.EPOCH, "user");
            assertThat(cache.size()).isZero();
        }
    }

    @Nested
    class ApplyMove {

        @BeforeEach
        void setup() {
            cache.applyCreate(folder(1L, 0L, "root"));
            cache.applyCreate(folder(2L, 1L, "A"));
            cache.applyCreate(folder(3L, 1L, "B"));
            cache.applyCreate(leaf(10L, 2L, "item"));
        }

        @Test
        void movesNodeToNewParent() {
            cache.applyMove(10L, 3L, Instant.EPOCH, "user");

            assertThat(cache.getById(10L).get().parentId()).isEqualTo(3L);
            assertThat(cache.getChildren(2L)).extracting(CachedNode::itemTreeId).doesNotContain(10L);
            assertThat(cache.getChildren(3L)).extracting(CachedNode::itemTreeId).contains(10L);
        }

        @Test
        void onMissingIdDoesNotThrowAndCacheIsUnchanged() {
            cache.applyMove(999L, 3L, Instant.EPOCH, "user");
            assertThat(cache.size()).isEqualTo(4);
        }

        @Test
        void onMissingNewParentDoesNotThrowAndNodeStaysAtOriginalParent() {
            cache.applyMove(10L, 999L, Instant.EPOCH, "user");
            assertThat(cache.getById(10L).get().parentId()).isEqualTo(2L);
            assertThat(cache.getChildren(2L)).extracting(CachedNode::itemTreeId).contains(10L);
        }
    }

    @Nested
    class ApplyRename {

        @BeforeEach
        void setup() {
            cache.applyCreate(folder(1L, 0L, "root"));
            cache.applyCreate(folder(2L, 1L, "OldName"));
            cache.applyCreate(leaf(10L, 2L, "report"));
        }

        @Test
        void renamedFolderUpdatesNameAndFoldersByName() {
            cache.applyRename(2L, "NewName", Instant.EPOCH, "user");

            assertThat(cache.getById(2L).get().name()).isEqualTo("NewName");
            assertThat(cache.findHomeFolder("OldName")).isEmpty();
            assertThat(cache.findHomeFolder("NewName")).isPresent();
        }

        @Test
        void renamedLeafDoesNotAffectFoldersByName() {
            cache.applyRename(10L, "renamed-report", Instant.EPOCH, "user");
            assertThat(cache.getById(10L).get().name()).isEqualTo("renamed-report");
            assertThat(cache.findHomeFolder("renamed-report")).isEmpty();
        }

        @Test
        void onMissingIdDoesNotThrowAndCacheIsUnchanged() {
            cache.applyRename(999L, "NewName", Instant.EPOCH, "user");
            assertThat(cache.size()).isEqualTo(3);
        }
    }

    @Nested
    class ApplyDelete {

        @BeforeEach
        void setup() {
            loadStandardTree(cache); // ids: 1, 2, 3, 100
        }

        @Test
        void deletedIdsAreRemovedFromByIdAndParentsChildSet() {
            cache.applyDelete(Set.of(100L));

            assertThat(cache.getById(100L)).isEmpty();
            assertThat(cache.getChildren(3L)).isEmpty();
            assertThat(cache.size()).isEqualTo(3);
        }

        @Test
        void deleteOnMissingIdIsToleratedWithoutException() {
            cache.applyDelete(Set.of(999L));
            assertThat(cache.size()).isEqualTo(4);
        }

        @Test
        void deleteMixOfExistingAndMissingIds() {
            cache.applyDelete(Set.of(100L, 999L));
            assertThat(cache.size()).isEqualTo(3);
            assertThat(cache.getById(100L)).isEmpty();
        }

        @Test
        void deleteFolderRemovesItFromFoldersByName() {
            cache.applyDelete(Set.of(3L));
            assertThat(cache.findHomeFolder("testuser1")).isEmpty();
        }

        @Test
        void deleteSubtreeRemovesAllCascadedIds() {
            cache.applyDelete(Set.of(2L, 3L, 100L));
            assertThat(cache.size()).isEqualTo(1); // only root remains
            assertThat(cache.getChildren(1L)).isEmpty();
        }
    }
}

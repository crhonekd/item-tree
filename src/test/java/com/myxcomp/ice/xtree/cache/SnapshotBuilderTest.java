package com.myxcomp.ice.xtree.cache;

import com.myxcomp.ice.xtree.persistence.StructuralRow;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SnapshotBuilderTest {

    private static final Instant T = Instant.EPOCH;

    private static StructuralRow row(long id, long parentId, String name, String type) {
        return new StructuralRow(id, parentId, name, type, T, "sys");
    }

    @Nested
    class EmptyBuilder {
        @Test
        void buildReturnsEmptyMaps() {
            TreeSnapshot snap = new SnapshotBuilder().build();
            assertThat(snap.byId()).isEmpty();
            assertThat(snap.childrenByParent()).isEmpty();
            assertThat(snap.foldersByName()).isEmpty();
        }
    }

    @Nested
    class SingleNode {
        @Test
        void folderAppearsInAllThreeIndexes() {
            SnapshotBuilder builder = new SnapshotBuilder();
            builder.accept(row(1L, 0L, "root", "Folder"));
            TreeSnapshot snap = builder.build();

            assertThat(snap.byId()).containsKey(1L);
            assertThat(snap.byId().get(1L).name()).isEqualTo("root");
            assertThat(snap.childrenByParent()).containsKey(0L);
            assertThat(snap.childrenByParent().get(0L)).containsExactly(1L);
            assertThat(snap.foldersByName()).containsKey("root");
            assertThat(snap.foldersByName().get("root")).containsExactly(1L);
        }

        @Test
        void snapshotMapsAndTheirSetsAreUnmodifiable() {
            SnapshotBuilder builder = new SnapshotBuilder();
            builder.accept(row(1L, 0L, "root", "Folder"));
            TreeSnapshot snap = builder.build();

            assertThatThrownBy(() -> snap.byId().put(99L, null))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> snap.childrenByParent().get(0L).add(99L))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> snap.foldersByName().get("root").add(99L))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void nonFolderDoesNotAppearInFoldersByName() {
            SnapshotBuilder builder = new SnapshotBuilder();
            builder.accept(row(10L, 1L, "MyReport", "Report"));
            TreeSnapshot snap = builder.build();

            assertThat(snap.byId()).containsKey(10L);
            assertThat(snap.foldersByName()).doesNotContainKey("MyReport");
        }
    }

    @Nested
    class MultipleNodes {
        @Test
        void childrenByParentGroupsCorrectly() {
            SnapshotBuilder builder = new SnapshotBuilder();
            builder.accept(row(1L, 0L, "root", "Folder"));
            builder.accept(row(2L, 1L, "Users", "Folder"));
            builder.accept(row(3L, 1L, "Reports", "Folder"));
            builder.accept(row(10L, 2L, "testuser1", "Folder"));
            TreeSnapshot snap = builder.build();

            assertThat(snap.childrenByParent().get(1L)).containsExactlyInAnyOrder(2L, 3L);
            assertThat(snap.childrenByParent().get(2L)).containsExactly(10L);
        }

        @Test
        void foldersByNameAccumulatesMultipleFoldersWithSameName() {
            SnapshotBuilder builder = new SnapshotBuilder();
            builder.accept(row(1L, 0L, "root", "Folder"));
            builder.accept(row(2L, 1L, "shared", "Folder"));
            builder.accept(row(3L, 1L, "shared", "Folder"));
            TreeSnapshot snap = builder.build();

            assertThat(snap.foldersByName().get("shared")).containsExactlyInAnyOrder(2L, 3L);
        }

        @Test
        void byIdContainsAllNodes() {
            SnapshotBuilder builder = new SnapshotBuilder();
            builder.accept(row(1L, 0L, "root", "Folder"));
            builder.accept(row(2L, 1L, "child", "Report"));
            TreeSnapshot snap = builder.build();

            assertThat(snap.byId()).hasSize(2);
            assertThat(snap.byId()).containsKeys(1L, 2L);
        }
    }
}

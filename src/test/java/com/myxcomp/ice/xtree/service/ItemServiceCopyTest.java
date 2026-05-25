package com.myxcomp.ice.xtree.service;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.TreeCache;
import com.myxcomp.ice.xtree.common.InstanceIdProvider;
import com.myxcomp.ice.xtree.common.TimeMapper;
import com.myxcomp.ice.xtree.common.TreeConstants;
import com.myxcomp.ice.xtree.common.UserContext;
import com.myxcomp.ice.xtree.config.CopyProperties;
import com.myxcomp.ice.xtree.conversion.XmlJsonConverter;
import com.myxcomp.ice.xtree.service.OwnershipChecker;
import com.myxcomp.ice.xtree.messaging.EventPublisher;
import com.myxcomp.ice.xtree.messaging.SequenceGenerator;
import com.myxcomp.ice.xtree.messaging.event.OperationType;
import com.myxcomp.ice.xtree.messaging.event.TreeMutationEvent;
import com.myxcomp.ice.xtree.messaging.event.payload.CopyPayload;
import com.myxcomp.ice.xtree.persistence.ItemTreeFullRow;
import com.myxcomp.ice.xtree.persistence.ItemTreeRepository;
import com.myxcomp.ice.xtree.policy.TypePolicy;
import com.myxcomp.ice.xtree.service.exception.CopyTooLargeException;
import com.myxcomp.ice.xtree.service.exception.ErrorCode;
import com.myxcomp.ice.xtree.service.exception.ForbiddenException;
import com.myxcomp.ice.xtree.service.exception.NotFoundException;
import com.myxcomp.ice.xtree.service.exception.ValidationException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.SyncTaskExecutor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ItemServiceCopyTest {

    @Mock TreeCache cache;
    @Mock ItemTreeRepository repository;
    @Mock TypePolicy policy;
    @Mock XmlJsonConverter converter;
    @Mock EventPublisher publisher;
    @Mock TimeMapper timeMapper;
    @Mock InstanceIdProvider instanceIdProvider;
    @Mock SequenceGenerator sequenceGenerator;
    @Mock CopyProperties copyProperties;
    @Mock OwnershipChecker ownershipChecker;

    ItemService service;

    @BeforeEach
    void setUp() {
        lenient().when(copyProperties.maxNodes()).thenReturn(100);
        service = new ItemService(cache, repository, policy, converter, publisher,
                timeMapper, instanceIdProvider, sequenceGenerator,
                new SyncTaskExecutor(), new SimpleMeterRegistry(), copyProperties, ownershipChecker);
    }

    @Nested
    class CopyItem {

        private final Instant T = Instant.parse("2026-05-24T10:00:00Z");
        private final UserContext ctx = new UserContext("alice", null);

        @BeforeEach
        void setup() {
            lenient().when(timeMapper.now()).thenReturn(T);
            lenient().when(instanceIdProvider.getInstanceId()).thenReturn("inst-1");
            lenient().when(sequenceGenerator.next()).thenReturn(1L);
        }

        @Test
        void happyPathSingleItemCopy() {
            long sourceId = 50L;
            long destId = 10L;
            long newId = 999L;
            CachedNode source = new CachedNode(sourceId, 99L, "Report A", "Report", T, "bob");
            CachedNode dest = new CachedNode(destId, 1L, "alice", "Folder", T, "alice");
            when(cache.getById(sourceId)).thenReturn(Optional.of(source));
            when(cache.getById(destId)).thenReturn(Optional.of(dest));
            when(ownershipChecker.requireHomeFolderExists("alice")).thenReturn(dest);
            when(cache.isAncestor(sourceId, destId)).thenReturn(false);
            when(cache.getChildren(destId)).thenReturn(List.of());
            when(cache.getSubtreeFlat(sourceId)).thenReturn(List.of(source));
            when(repository.findRowsForCopy(eq(sourceId), anyInt())).thenReturn(List.of(
                    new ItemTreeFullRow(sourceId, 99L, "Report A", "Report",
                            "{\"k\":1}", null, T, "bob")));
            when(repository.allocateIds(1)).thenReturn(List.of(newId));

            List<CachedNode> result = service.copyItem(sourceId, destId, ctx);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).itemTreeId()).isEqualTo(newId);
            assertThat(result.get(0).parentId()).isEqualTo(destId);
            assertThat(result.get(0).name()).isEqualTo("Report A");
            assertThat(result.get(0).type()).isEqualTo("Report");
            assertThat(result.get(0).lastUpdate()).isEqualTo(T);
            assertThat(result.get(0).lastUpdateUser()).isEqualTo("alice");

            // batch INSERT shape
            ArgumentCaptor<List<ItemTreeFullRow>> insertCaptor = ArgumentCaptor.captor();
            verify(repository).insertBatch(insertCaptor.capture());
            assertThat(insertCaptor.getValue()).hasSize(1);
            assertThat(insertCaptor.getValue().get(0).json()).isEqualTo("{\"k\":1}");
            assertThat(insertCaptor.getValue().get(0).lastUpdateUser()).isEqualTo("alice");

            // cache apply
            ArgumentCaptor<List<CachedNode>> cacheCaptor = ArgumentCaptor.captor();
            verify(cache).applyCopy(cacheCaptor.capture());
            assertThat(cacheCaptor.getValue()).extracting(CachedNode::itemTreeId).containsExactly(newId);

            // event publish
            ArgumentCaptor<TreeMutationEvent> eventCaptor = ArgumentCaptor.captor();
            verify(publisher).publish(eventCaptor.capture());
            assertThat(eventCaptor.getValue().getOperationType()).isEqualTo(OperationType.COPY);
            CopyPayload payload = (CopyPayload) eventCaptor.getValue().getPayload();
            assertThat(payload.newNodes()).hasSize(1);
            assertThat(payload.newNodes().get(0).itemTreeId()).isEqualTo(newId);
        }

        @Test
        void impersonatedUserIsUsedForHomeFolderAndStamping() {
            long sourceId = 50L;
            long destId = 10L;
            long newId = 999L;
            UserContext impersonatedCtx = new UserContext("realUser", "alice");
            CachedNode source = new CachedNode(sourceId, 99L, "Report A", "Report", T, "bob");
            CachedNode dest = new CachedNode(destId, 1L, "alice", "Folder", T, "alice");
            when(cache.getById(sourceId)).thenReturn(Optional.of(source));
            when(cache.getById(destId)).thenReturn(Optional.of(dest));
            when(ownershipChecker.requireHomeFolderExists("alice")).thenReturn(dest);
            when(cache.isAncestor(sourceId, destId)).thenReturn(false);
            when(cache.getChildren(destId)).thenReturn(List.of());
            when(cache.getSubtreeFlat(sourceId)).thenReturn(List.of(source));
            when(repository.findRowsForCopy(eq(sourceId), anyInt())).thenReturn(List.of(
                    new ItemTreeFullRow(sourceId, 99L, "Report A", "Report",
                            "{\"k\":1}", null, T, "bob")));
            when(repository.allocateIds(1)).thenReturn(List.of(newId));

            List<CachedNode> result = service.copyItem(sourceId, destId, impersonatedCtx);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).lastUpdateUser()).isEqualTo("alice");

            ArgumentCaptor<List<ItemTreeFullRow>> insertCaptor = ArgumentCaptor.captor();
            verify(repository).insertBatch(insertCaptor.capture());
            assertThat(insertCaptor.getValue()).hasSize(1);
            assertThat(insertCaptor.getValue().get(0).lastUpdateUser()).isEqualTo("alice");
        }

        @Test
        void itemNotFoundOnUnknownSource() {
            when(cache.getById(123L)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.copyItem(123L, 10L, ctx))
                    .isInstanceOf(NotFoundException.class)
                    .satisfies(e -> assertThat(((NotFoundException) e).errorCode())
                            .isEqualTo(ErrorCode.ITEM_NOT_FOUND));
        }

        @Test
        void cannotCopyRoot() {
            CachedNode root = new CachedNode(TreeConstants.ROOT_ID, 0L, "root", "Folder", T, "system");
            when(cache.getById(TreeConstants.ROOT_ID)).thenReturn(Optional.of(root));
            assertThatThrownBy(() -> service.copyItem(TreeConstants.ROOT_ID, 10L, ctx))
                    .isInstanceOf(ValidationException.class)
                    .satisfies(e -> assertThat(((ValidationException) e).errorCode())
                            .isEqualTo(ErrorCode.CANNOT_COPY_ROOT));
        }

        @Test
        void destinationNotFound() {
            CachedNode source = new CachedNode(50L, 99L, "X", "Report", T, "bob");
            when(cache.getById(50L)).thenReturn(Optional.of(source));
            when(cache.getById(999L)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.copyItem(50L, 999L, ctx))
                    .isInstanceOf(NotFoundException.class)
                    .satisfies(e -> assertThat(((NotFoundException) e).errorCode())
                            .isEqualTo(ErrorCode.DESTINATION_NOT_FOUND));
        }

        @Test
        void destinationNotFolder() {
            CachedNode source = new CachedNode(50L, 99L, "X", "Report", T, "bob");
            CachedNode notFolder = new CachedNode(11L, 1L, "thing", "Report", T, "bob");
            when(cache.getById(50L)).thenReturn(Optional.of(source));
            when(cache.getById(11L)).thenReturn(Optional.of(notFolder));
            assertThatThrownBy(() -> service.copyItem(50L, 11L, ctx))
                    .isInstanceOf(ValidationException.class)
                    .satisfies(e -> assertThat(((ValidationException) e).errorCode())
                            .isEqualTo(ErrorCode.DESTINATION_NOT_FOLDER));
        }

        @Test
        void homeFolderNotFound() {
            CachedNode source = new CachedNode(50L, 99L, "X", "Report", T, "bob");
            CachedNode dest = new CachedNode(10L, 1L, "anotherUser", "Folder", T, "x");
            when(cache.getById(50L)).thenReturn(Optional.of(source));
            when(cache.getById(10L)).thenReturn(Optional.of(dest));
            when(ownershipChecker.requireHomeFolderExists("alice")).thenThrow(
                    new NotFoundException(ErrorCode.HOME_FOLDER_NOT_FOUND,
                            "No home folder for user 'alice'"));
            assertThatThrownBy(() -> service.copyItem(50L, 10L, ctx))
                    .isInstanceOf(NotFoundException.class)
                    .satisfies(e -> assertThat(((NotFoundException) e).errorCode())
                            .isEqualTo(ErrorCode.HOME_FOLDER_NOT_FOUND));
        }

        @Test
        void destinationNotInUserFolder() {
            CachedNode source = new CachedNode(50L, 99L, "X", "Report", T, "bob");
            CachedNode dest = new CachedNode(10L, 1L, "other", "Folder", T, "x");
            CachedNode home = new CachedNode(20L, 1L, "alice", "Folder", T, "alice");
            when(cache.getById(50L)).thenReturn(Optional.of(source));
            when(cache.getById(10L)).thenReturn(Optional.of(dest));
            when(ownershipChecker.requireHomeFolderExists("alice")).thenReturn(home);
            org.mockito.Mockito.doThrow(new ForbiddenException(
                    ErrorCode.NOT_IN_USER_FOLDER,
                    "Destination 10 is not under home folder of 'alice'"))
                    .when(ownershipChecker).requireOwned(10L, home, "alice", "Destination");
            assertThatThrownBy(() -> service.copyItem(50L, 10L, ctx))
                    .isInstanceOf(ForbiddenException.class)
                    .satisfies(e -> assertThat(((ForbiddenException) e).errorCode())
                            .isEqualTo(ErrorCode.NOT_IN_USER_FOLDER));
        }

        @Test
        void copyIntoSelf() {
            CachedNode source = new CachedNode(10L, 1L, "alice", "Folder", T, "alice");
            when(cache.getById(10L)).thenReturn(Optional.of(source));
            when(ownershipChecker.requireHomeFolderExists("alice")).thenReturn(source);
            assertThatThrownBy(() -> service.copyItem(10L, 10L, ctx))
                    .isInstanceOf(ValidationException.class)
                    .satisfies(e -> assertThat(((ValidationException) e).errorCode())
                            .isEqualTo(ErrorCode.COPY_INTO_DESCENDANT));
        }

        @Test
        void copyIntoDescendant() {
            CachedNode source = new CachedNode(50L, 99L, "src", "Folder", T, "bob");
            CachedNode descendant = new CachedNode(60L, 50L, "child", "Folder", T, "bob");
            CachedNode home = new CachedNode(20L, 1L, "alice", "Folder", T, "alice");
            when(cache.getById(50L)).thenReturn(Optional.of(source));
            when(cache.getById(60L)).thenReturn(Optional.of(descendant));
            when(ownershipChecker.requireHomeFolderExists("alice")).thenReturn(home);
            when(cache.isAncestor(50L, 60L)).thenReturn(true);
            assertThatThrownBy(() -> service.copyItem(50L, 60L, ctx))
                    .isInstanceOf(ValidationException.class)
                    .satisfies(e -> assertThat(((ValidationException) e).errorCode())
                            .isEqualTo(ErrorCode.COPY_INTO_DESCENDANT));
        }

        @Test
        void copyTooLargePreFlight() {
            CachedNode source = new CachedNode(50L, 99L, "src", "Folder", T, "bob");
            CachedNode dest = new CachedNode(10L, 1L, "alice", "Folder", T, "alice");
            when(cache.getById(50L)).thenReturn(Optional.of(source));
            when(cache.getById(10L)).thenReturn(Optional.of(dest));
            when(ownershipChecker.requireHomeFolderExists("alice")).thenReturn(dest);
            when(cache.isAncestor(50L, 10L)).thenReturn(false);
            List<CachedNode> oversized = new ArrayList<>();
            for (int i = 0; i < 101; i++) {
                oversized.add(new CachedNode(50L + i, i == 0 ? 99L : 50L + i - 1,
                        "n" + i, "Folder", T, "bob"));
            }
            when(cache.getSubtreeFlat(50L)).thenReturn(oversized);

            assertThatThrownBy(() -> service.copyItem(50L, 10L, ctx))
                    .isInstanceOf(CopyTooLargeException.class);
            verify(repository, never()).findRowsForCopy(anyLong(), anyInt());
        }

        @Test
        void copyTooLargeAuthoritativeDb() {
            CachedNode source = new CachedNode(50L, 99L, "src", "Folder", T, "bob");
            CachedNode dest = new CachedNode(10L, 1L, "alice", "Folder", T, "alice");
            when(cache.getById(50L)).thenReturn(Optional.of(source));
            when(cache.getById(10L)).thenReturn(Optional.of(dest));
            when(ownershipChecker.requireHomeFolderExists("alice")).thenReturn(dest);
            when(cache.isAncestor(50L, 10L)).thenReturn(false);
            when(cache.getSubtreeFlat(50L)).thenReturn(List.of(source));
            List<ItemTreeFullRow> dbRows = new ArrayList<>();
            for (int i = 0; i < 101; i++) {
                dbRows.add(new ItemTreeFullRow(50L + i, i == 0 ? 99L : 50L + i - 1,
                        "n" + i, "Folder", null, null, T, "bob"));
            }
            when(repository.findRowsForCopy(eq(50L), anyInt())).thenReturn(dbRows);

            assertThatThrownBy(() -> service.copyItem(50L, 10L, ctx))
                    .isInstanceOf(CopyTooLargeException.class);
            verify(repository, never()).insertBatch(any());
        }

        @Test
        void folderSubtreeWithNameCollisionAutoSuffixed() {
            long sourceId = 50L;
            long destId = 10L;
            long destChildExisting = 30L;
            CachedNode source = new CachedNode(sourceId, 99L, "Things", "Folder", T, "bob");
            CachedNode dest = new CachedNode(destId, 1L, "alice", "Folder", T, "alice");
            CachedNode existingSibling = new CachedNode(destChildExisting, destId, "Things", "Folder", T, "alice");
            when(cache.getById(sourceId)).thenReturn(Optional.of(source));
            when(cache.getById(destId)).thenReturn(Optional.of(dest));
            when(ownershipChecker.requireHomeFolderExists("alice")).thenReturn(dest);
            when(cache.isAncestor(sourceId, destId)).thenReturn(false);
            when(cache.getSubtreeFlat(sourceId)).thenReturn(List.of(
                    source,
                    new CachedNode(51L, sourceId, "leaf", "Report", T, "bob")));
            when(cache.getChildren(destId)).thenReturn(List.of(existingSibling));
            when(repository.findRowsForCopy(eq(sourceId), anyInt())).thenReturn(List.of(
                    new ItemTreeFullRow(sourceId, 99L, "Things", "Folder", null, null, T, "bob"),
                    new ItemTreeFullRow(51L, sourceId, "leaf", "Report", "{\"k\":1}", null, T, "bob")));
            when(repository.allocateIds(2)).thenReturn(List.of(900L, 901L));

            List<CachedNode> result = service.copyItem(sourceId, destId, ctx);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).name()).isEqualTo("Things (copy)");
            assertThat(result.get(0).itemTreeId()).isEqualTo(900L);
            assertThat(result.get(1).name()).isEqualTo("leaf");
            assertThat(result.get(1).parentId()).isEqualTo(900L);
        }

        @Test
        void nameSuffixWalksToNumberedVariant() {
            long sourceId = 50L;
            long destId = 10L;
            CachedNode source = new CachedNode(sourceId, 99L, "X", "Folder", T, "bob");
            CachedNode dest = new CachedNode(destId, 1L, "alice", "Folder", T, "alice");
            when(cache.getById(sourceId)).thenReturn(Optional.of(source));
            when(cache.getById(destId)).thenReturn(Optional.of(dest));
            when(ownershipChecker.requireHomeFolderExists("alice")).thenReturn(dest);
            when(cache.isAncestor(sourceId, destId)).thenReturn(false);
            when(cache.getSubtreeFlat(sourceId)).thenReturn(List.of(source));
            when(cache.getChildren(destId)).thenReturn(List.of(
                    new CachedNode(30L, destId, "X", "Folder", T, "alice"),
                    new CachedNode(31L, destId, "X (copy)", "Folder", T, "alice"),
                    new CachedNode(32L, destId, "X (copy 2)", "Folder", T, "alice")));
            when(repository.findRowsForCopy(eq(sourceId), anyInt())).thenReturn(List.of(
                    new ItemTreeFullRow(sourceId, 99L, "X", "Folder", null, null, T, "bob")));
            when(repository.allocateIds(1)).thenReturn(List.of(900L));

            List<CachedNode> result = service.copyItem(sourceId, destId, ctx);
            assertThat(result.get(0).name()).isEqualTo("X (copy 3)");
        }

        @Test
        void publisherExceptionIsSwallowed() {
            long sourceId = 50L;
            long destId = 10L;
            CachedNode source = new CachedNode(sourceId, 99L, "X", "Report", T, "bob");
            CachedNode dest = new CachedNode(destId, 1L, "alice", "Folder", T, "alice");
            when(cache.getById(sourceId)).thenReturn(Optional.of(source));
            when(cache.getById(destId)).thenReturn(Optional.of(dest));
            when(ownershipChecker.requireHomeFolderExists("alice")).thenReturn(dest);
            when(cache.isAncestor(sourceId, destId)).thenReturn(false);
            when(cache.getSubtreeFlat(sourceId)).thenReturn(List.of(source));
            when(cache.getChildren(destId)).thenReturn(List.of());
            when(repository.findRowsForCopy(eq(sourceId), anyInt())).thenReturn(List.of(
                    new ItemTreeFullRow(sourceId, 99L, "X", "Report", null, null, T, "bob")));
            when(repository.allocateIds(1)).thenReturn(List.of(900L));
            doThrow(new RuntimeException("solace boom")).when(publisher).publish(any());

            List<CachedNode> result = service.copyItem(sourceId, destId, ctx);
            assertThat(result).hasSize(1);
            verify(repository).insertBatch(any());
            verify(cache).applyCopy(any());
        }

        @Test
        void itemFoundInCacheButAbsentFromDb() {
            CachedNode source = new CachedNode(50L, 99L, "X", "Report", T, "bob");
            CachedNode dest = new CachedNode(10L, 1L, "alice", "Folder", T, "alice");
            when(cache.getById(50L)).thenReturn(Optional.of(source));
            when(cache.getById(10L)).thenReturn(Optional.of(dest));
            when(ownershipChecker.requireHomeFolderExists("alice")).thenReturn(dest);
            when(cache.isAncestor(50L, 10L)).thenReturn(false);
            when(cache.getSubtreeFlat(50L)).thenReturn(List.of(source));
            when(repository.findRowsForCopy(eq(50L), anyInt())).thenReturn(List.of());

            assertThatThrownBy(() -> service.copyItem(50L, 10L, ctx))
                    .isInstanceOf(NotFoundException.class)
                    .satisfies(e -> assertThat(((NotFoundException) e).errorCode())
                            .isEqualTo(ErrorCode.ITEM_NOT_FOUND));
            verify(repository, never()).insertBatch(any());
        }
    }
}

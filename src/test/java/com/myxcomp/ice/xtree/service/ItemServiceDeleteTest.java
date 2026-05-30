package com.myxcomp.ice.xtree.service;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.TreeCache;
import com.myxcomp.ice.xtree.common.InstanceIdProvider;
import com.myxcomp.ice.xtree.common.TimeMapper;
import com.myxcomp.ice.xtree.common.UserContext;
import com.myxcomp.ice.xtree.conversion.XmlJsonConverter;
import com.myxcomp.ice.xtree.messaging.EventPublisher;
import com.myxcomp.ice.xtree.messaging.SequenceGenerator;
import com.myxcomp.ice.xtree.messaging.event.OperationType;
import com.myxcomp.ice.xtree.messaging.event.TreeMutationEvent;
import com.myxcomp.ice.xtree.messaging.event.payload.DeletePayload;
import com.myxcomp.ice.xtree.config.CopyProperties;
import com.myxcomp.ice.xtree.persistence.ItemTreeRepository;
import com.myxcomp.ice.xtree.service.OwnershipChecker;
import com.myxcomp.ice.xtree.policy.TypePolicy;
import com.myxcomp.ice.xtree.service.exception.ErrorCode;
import com.myxcomp.ice.xtree.service.exception.ForbiddenException;
import com.myxcomp.ice.xtree.service.exception.NotFoundException;
import com.myxcomp.ice.xtree.service.exception.ValidationException;
import com.myxcomp.ice.xtree.common.Types;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.core.task.SyncTaskExecutor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ItemServiceDeleteTest {

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
    @Mock PathResolver pathResolver;

    ItemService service;
    static final UserContext CTX = new UserContext("alice", null);

    @BeforeEach
    void setUp() {
        lenient().when(copyProperties.maxNodes()).thenReturn(100);
        service = new ItemService(
                cache, repository, policy, converter, publisher,
                timeMapper, instanceIdProvider, sequenceGenerator,
                new SyncTaskExecutor(), new SimpleMeterRegistry(), copyProperties, ownershipChecker,
                pathResolver);
    }

    private static final CachedNode NODE_50 = new CachedNode(50L, 10L, "Report", "Report",
            Instant.parse("2026-05-16T12:00:00Z"), "sys");
    private static final CachedNode HOME_ALICE = new CachedNode(10L, 2L, "alice", "Folder",
            Instant.parse("2026-05-16T12:00:00Z"), "sys");

    @Test
    void deleteCascadesAndBroadcasts() {
        when(cache.getById(50L)).thenReturn(Optional.of(NODE_50));
        when(ownershipChecker.requireHomeFolderExists(CTX.effectiveUser())).thenReturn(HOME_ALICE);
        when(repository.cascadeDeleteSubtree(50L)).thenReturn(List.of(50L, 51L, 52L));
        when(timeMapper.now()).thenReturn(Instant.parse("2026-05-16T12:00:00Z"));
        when(instanceIdProvider.getInstanceId()).thenReturn("inst-1");
        when(sequenceGenerator.next()).thenReturn(8L);

        service.deleteItem(50L, CTX);

        InOrder order = inOrder(repository, cache, publisher);
        order.verify(repository).cascadeDeleteSubtree(50L);
        order.verify(cache).applyDelete(Set.of(50L, 51L, 52L));

        ArgumentCaptor<TreeMutationEvent> cap = ArgumentCaptor.forClass(TreeMutationEvent.class);
        order.verify(publisher).publish(cap.capture());
        TreeMutationEvent ev = cap.getValue();
        assertThat(ev.getOperationType()).isEqualTo(OperationType.DELETE);
        assertThat(ev.getPayload()).isInstanceOf(DeletePayload.class);
        assertThat(((DeletePayload) ev.getPayload()).deletedIds())
                .containsExactly(50L, 51L, 52L);
    }

    @Test
    void deleteOfMissingIdIsSilentNoOp() {
        when(cache.getById(999L)).thenReturn(Optional.empty());

        service.deleteItem(999L, CTX);

        verify(repository, never()).cascadeDeleteSubtree(anyLong());
        verifyNoInteractions(publisher);
        verify(cache, never()).applyDelete(any());
    }

    @Test
    void publisherThrowDoesNotPropagateOnDelete() {
        when(cache.getById(50L)).thenReturn(Optional.of(
                new CachedNode(50L, 10L, "Report", "Report",
                        Instant.parse("2026-05-16T12:00:00Z"), "sys")));
        when(ownershipChecker.requireHomeFolderExists(anyString())).thenReturn(
                new CachedNode(10L, 2L, CTX.effectiveUser(), "Folder",
                        Instant.parse("2026-05-16T12:00:00Z"), "sys"));
        when(repository.cascadeDeleteSubtree(50L)).thenReturn(List.of(50L));
        when(timeMapper.now()).thenReturn(Instant.parse("2026-05-16T12:00:00Z"));
        when(instanceIdProvider.getInstanceId()).thenReturn("inst-1");
        when(sequenceGenerator.next()).thenReturn(1L);
        doThrow(new RuntimeException("bus down")).when(publisher).publish(any());

        assertThatCode(() -> service.deleteItem(50L, CTX)).doesNotThrowAnyException();

        verify(repository).cascadeDeleteSubtree(50L);
        verify(cache).applyDelete(Set.of(50L));
        verify(publisher).publish(any());
    }

    @Nested
    class Ownership {

        private final UserContext ctx = new UserContext("alice", null);
        private final CachedNode targetItem = new CachedNode(50L, 10L, "Report", "Report",
                Instant.parse("2026-05-25T10:00:00Z"), "sys");
        private final CachedNode aliceHome = new CachedNode(10L, 2L, "alice", "Folder",
                Instant.parse("2026-05-25T10:00:00Z"), "sys");

        @Test
        void deletingItemNotInUserHomeRejectsWith403() {
            when(cache.getById(50L)).thenReturn(Optional.of(targetItem));
            when(ownershipChecker.requireHomeFolderExists("alice")).thenReturn(aliceHome);
            doThrow(new ForbiddenException(ErrorCode.NOT_IN_USER_FOLDER,
                    "Item 50 is not under home folder of 'alice'"))
                    .when(ownershipChecker).requireOwned(50L, aliceHome, "alice", "Item");

            assertThatThrownBy(() -> service.deleteItem(50L, ctx))
                    .isInstanceOf(ForbiddenException.class)
                    .satisfies(e -> assertThat(((ForbiddenException) e).errorCode())
                            .isEqualTo(ErrorCode.NOT_IN_USER_FOLDER));

            verify(repository, never()).cascadeDeleteSubtree(anyLong());
            verifyNoInteractions(publisher);
        }

        @Test
        void deletingMissingIdIsNoopWithoutAuthCheck() {
            when(cache.getById(999L)).thenReturn(Optional.empty());

            assertThatCode(() -> service.deleteItem(999L, ctx))
                    .doesNotThrowAnyException();

            verify(repository, never()).cascadeDeleteSubtree(anyLong());
            verifyNoInteractions(ownershipChecker);
            verifyNoInteractions(publisher);
        }

        @Test
        void noHomeFolderRejectsWith404WhenItemExists() {
            when(cache.getById(50L)).thenReturn(Optional.of(targetItem));
            when(ownershipChecker.requireHomeFolderExists("alice")).thenThrow(
                    new NotFoundException(ErrorCode.HOME_FOLDER_NOT_FOUND,
                            "No home folder for user 'alice'"));

            assertThatThrownBy(() -> service.deleteItem(50L, ctx))
                    .isInstanceOf(NotFoundException.class)
                    .satisfies(e -> assertThat(((NotFoundException) e).errorCode())
                            .isEqualTo(ErrorCode.HOME_FOLDER_NOT_FOUND));

            verify(repository, never()).cascadeDeleteSubtree(anyLong());
        }

        @Test
        void ownedItemFlowsThroughToCascadeDelete() {
            when(cache.getById(50L)).thenReturn(Optional.of(targetItem));
            when(ownershipChecker.requireHomeFolderExists("alice")).thenReturn(aliceHome);
            // requireOwned returns void → default mock = passes
            when(repository.cascadeDeleteSubtree(50L)).thenReturn(List.of(50L));
            when(timeMapper.now()).thenReturn(Instant.parse("2026-05-25T11:00:00Z"));
            when(instanceIdProvider.getInstanceId()).thenReturn("inst-1");
            when(sequenceGenerator.next()).thenReturn(1L);

            service.deleteItem(50L, ctx);

            verify(repository).cascadeDeleteSubtree(50L);
            verify(publisher).publish(any());
        }
    }

    @Test
    void deletingUdfRepoIsRejected() {
        long id = 901L;
        when(cache.getById(id)).thenReturn(Optional.of(
                new CachedNode(id, 10L, "alice", "UDFRepo",
                        Instant.parse("2026-05-29T00:00:00Z"), "alice")));

        assertThatThrownBy(() -> service.deleteItem(id, new UserContext("alice", null)))
                .isInstanceOf(ValidationException.class)
                .extracting(e -> ((ValidationException) e).errorCode())
                .isEqualTo(ErrorCode.UDF_REPO_PROTECTED);

        verify(repository, never()).cascadeDeleteSubtree(anyLong());
    }
}

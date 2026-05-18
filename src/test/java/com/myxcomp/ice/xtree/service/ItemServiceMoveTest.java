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
import com.myxcomp.ice.xtree.messaging.event.payload.MovePayload;
import com.myxcomp.ice.xtree.persistence.ItemTreeRepository;
import com.myxcomp.ice.xtree.policy.TypePolicy;
import com.myxcomp.ice.xtree.service.exception.ErrorCode;
import com.myxcomp.ice.xtree.service.exception.NotFoundException;
import com.myxcomp.ice.xtree.service.exception.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.core.task.SyncTaskExecutor;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ItemServiceMoveTest {

    @Mock TreeCache cache;
    @Mock ItemTreeRepository repository;
    @Mock TypePolicy policy;
    @Mock XmlJsonConverter converter;
    @Mock EventPublisher publisher;
    @Mock TimeMapper timeMapper;
    @Mock InstanceIdProvider instanceIdProvider;
    @Mock SequenceGenerator sequenceGenerator;

    ItemService service;
    static final Instant NOW = Instant.parse("2026-05-16T12:00:00Z");
    static final UserContext CTX = new UserContext("alice", null);

    @BeforeEach
    void setUp() {
        service = new ItemService(cache, repository, policy, converter, publisher,
                timeMapper, instanceIdProvider, sequenceGenerator, new SyncTaskExecutor(),
                new SimpleMeterRegistry());
    }

    private CachedNode folder(long id, long parentId, String name) {
        return new CachedNode(id, parentId, name, "Folder", Instant.EPOCH, "sys");
    }

    @Test
    void moveHappyPath() {
        CachedNode item = new CachedNode(7L, 2L, "doc", "Report", Instant.EPOCH, "u");
        CachedNode newParentNode = folder(3L, 1L, "Archive");
        CachedNode itemAfter = new CachedNode(7L, 3L, "doc", "Report", NOW, "alice");
        when(cache.getById(7L)).thenReturn(Optional.of(item), Optional.of(itemAfter));
        when(cache.getById(3L)).thenReturn(Optional.of(newParentNode));
        when(cache.isAncestor(7L, 3L)).thenReturn(false);
        when(timeMapper.now()).thenReturn(NOW);
        when(instanceIdProvider.getInstanceId()).thenReturn("inst-1");
        when(sequenceGenerator.next()).thenReturn(2L);

        CachedNode result = service.moveItem(7L, 3L, CTX);

        assertThat(result).isEqualTo(itemAfter);
        InOrder order = inOrder(repository, cache, publisher);
        order.verify(repository).updateParent(7L, 3L, NOW, "alice");
        order.verify(cache).applyMove(7L, 3L, NOW, "alice");
        ArgumentCaptor<TreeMutationEvent> cap = ArgumentCaptor.forClass(TreeMutationEvent.class);
        order.verify(publisher).publish(cap.capture());
        MovePayload payload = (MovePayload) cap.getValue().getPayload();
        assertThat(cap.getValue().getOperationType()).isEqualTo(OperationType.MOVE);
        assertThat(payload.itemTreeId()).isEqualTo(7L);
        assertThat(payload.oldParentId()).isEqualTo(2L);
        assertThat(payload.newParentId()).isEqualTo(3L);
    }

    @Test
    void rejectsItemNotFound() {
        when(cache.getById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.moveItem(99L, 3L, CTX))
                .isInstanceOf(NotFoundException.class)
                .satisfies(t -> assertThat(((NotFoundException) t).errorCode())
                        .isEqualTo(ErrorCode.ITEM_NOT_FOUND));
        verifyNoInteractions(publisher);
        verify(repository, org.mockito.Mockito.never())
            .updateParent(org.mockito.ArgumentMatchers.anyLong(),
                          org.mockito.ArgumentMatchers.anyLong(),
                          org.mockito.ArgumentMatchers.any(),
                          org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void rejectsNewParentNotFound() {
        CachedNode item = new CachedNode(7L, 2L, "doc", "Report", Instant.EPOCH, "u");
        when(cache.getById(7L)).thenReturn(Optional.of(item));
        when(cache.getById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.moveItem(7L, 999L, CTX))
                .isInstanceOf(NotFoundException.class)
                .satisfies(t -> assertThat(((NotFoundException) t).errorCode())
                        .isEqualTo(ErrorCode.NEW_PARENT_NOT_FOUND));
        verifyNoInteractions(publisher);
        verify(repository, org.mockito.Mockito.never())
            .updateParent(org.mockito.ArgumentMatchers.anyLong(),
                          org.mockito.ArgumentMatchers.anyLong(),
                          org.mockito.ArgumentMatchers.any(),
                          org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void rejectsMoveIntoSelf() {
        CachedNode item = new CachedNode(7L, 2L, "doc", "Report", Instant.EPOCH, "u");
        when(cache.getById(7L)).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> service.moveItem(7L, 7L, CTX))
                .isInstanceOf(ValidationException.class)
                .satisfies(t -> assertThat(((ValidationException) t).errorCode())
                        .isEqualTo(ErrorCode.MOVE_INTO_DESCENDANT));
        verifyNoInteractions(publisher);
        verify(repository, org.mockito.Mockito.never())
            .updateParent(org.mockito.ArgumentMatchers.anyLong(),
                          org.mockito.ArgumentMatchers.anyLong(),
                          org.mockito.ArgumentMatchers.any(),
                          org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void rejectsNewParentNotFolder() {
        CachedNode item = new CachedNode(7L, 2L, "doc", "Report", Instant.EPOCH, "u");
        CachedNode nonFolder = new CachedNode(3L, 1L, "Other", "Report", Instant.EPOCH, "u");
        when(cache.getById(7L)).thenReturn(Optional.of(item));
        when(cache.getById(3L)).thenReturn(Optional.of(nonFolder));

        assertThatThrownBy(() -> service.moveItem(7L, 3L, CTX))
                .isInstanceOf(ValidationException.class)
                .satisfies(t -> assertThat(((ValidationException) t).errorCode())
                        .isEqualTo(ErrorCode.NEW_PARENT_NOT_FOLDER));
        verifyNoInteractions(publisher);
        verify(repository, org.mockito.Mockito.never())
            .updateParent(org.mockito.ArgumentMatchers.anyLong(),
                          org.mockito.ArgumentMatchers.anyLong(),
                          org.mockito.ArgumentMatchers.any(),
                          org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void rejectsMoveIntoDescendant() {
        CachedNode item = folder(7L, 2L, "doc");
        CachedNode child = folder(8L, 7L, "sub");
        when(cache.getById(7L)).thenReturn(Optional.of(item));
        when(cache.getById(8L)).thenReturn(Optional.of(child));
        when(cache.isAncestor(7L, 8L)).thenReturn(true);

        assertThatThrownBy(() -> service.moveItem(7L, 8L, CTX))
                .isInstanceOf(ValidationException.class)
                .satisfies(t -> assertThat(((ValidationException) t).errorCode())
                        .isEqualTo(ErrorCode.MOVE_INTO_DESCENDANT));
        verifyNoInteractions(publisher);
        verify(repository, org.mockito.Mockito.never())
            .updateParent(org.mockito.ArgumentMatchers.anyLong(),
                          org.mockito.ArgumentMatchers.anyLong(),
                          org.mockito.ArgumentMatchers.any(),
                          org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void publisherThrowDoesNotPropagateOnMove() {
        CachedNode item = new CachedNode(7L, 2L, "doc", "Report", Instant.EPOCH, "u");
        CachedNode newParentNode = folder(3L, 1L, "Archive");
        CachedNode itemAfter = new CachedNode(7L, 3L, "doc", "Report", NOW, "alice");
        when(cache.getById(7L)).thenReturn(Optional.of(item), Optional.of(itemAfter));
        when(cache.getById(3L)).thenReturn(Optional.of(newParentNode));
        when(cache.isAncestor(7L, 3L)).thenReturn(false);
        when(timeMapper.now()).thenReturn(NOW);
        when(instanceIdProvider.getInstanceId()).thenReturn("inst-1");
        when(sequenceGenerator.next()).thenReturn(1L);
        doThrow(new RuntimeException("bus down")).when(publisher).publish(any());

        CachedNode[] result = new CachedNode[1];
        assertThatCode(() -> result[0] = service.moveItem(7L, 3L, CTX)).doesNotThrowAnyException();

        assertThat(result[0]).isEqualTo(itemAfter);
        verify(repository).updateParent(7L, 3L, NOW, "alice");
        verify(cache).applyMove(7L, 3L, NOW, "alice");
        verify(publisher).publish(any());
    }
}

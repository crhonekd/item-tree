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
import com.myxcomp.ice.xtree.messaging.event.payload.RenamePayload;
import com.myxcomp.ice.xtree.persistence.ItemTreeRepository;
import com.myxcomp.ice.xtree.policy.TypePolicy;
import com.myxcomp.ice.xtree.service.exception.ErrorCode;
import com.myxcomp.ice.xtree.service.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.SyncTaskExecutor;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ItemServiceRenameTest {

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
                timeMapper, instanceIdProvider, sequenceGenerator, new SyncTaskExecutor());
    }

    @Test
    void renameHappyPath() {
        CachedNode before = new CachedNode(7L, 1L, "Old", "Report", Instant.EPOCH, "u");
        CachedNode after  = new CachedNode(7L, 1L, "New", "Report", NOW, "alice");
        when(cache.getById(7L)).thenReturn(Optional.of(before), Optional.of(after));
        when(timeMapper.now()).thenReturn(NOW);
        when(instanceIdProvider.getInstanceId()).thenReturn("inst-1");
        when(sequenceGenerator.next()).thenReturn(11L);

        CachedNode result = service.renameItem(7L, "New", CTX);

        assertThat(result).isEqualTo(after);
        InOrder order = inOrder(repository, cache, publisher);
        order.verify(repository).updateName(7L, "New", NOW, "alice");
        order.verify(cache).applyRename(7L, "New", NOW, "alice");

        ArgumentCaptor<TreeMutationEvent> cap = ArgumentCaptor.forClass(TreeMutationEvent.class);
        order.verify(publisher).publish(cap.capture());
        assertThat(cap.getValue().getOperationType()).isEqualTo(OperationType.RENAME);
        RenamePayload payload = (RenamePayload) cap.getValue().getPayload();
        assertThat(payload.itemTreeId()).isEqualTo(7L);
        assertThat(payload.newName()).isEqualTo("New");
        assertThat(payload.lastUpdate()).isEqualTo(NOW);
        assertThat(payload.lastUpdateUser()).isEqualTo("alice");
    }

    @Test
    void rejectsUnknownId() {
        when(cache.getById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.renameItem(99L, "X", CTX))
                .isInstanceOf(NotFoundException.class)
                .satisfies(t -> assertThat(((NotFoundException) t).errorCode())
                        .isEqualTo(ErrorCode.ITEM_NOT_FOUND));

        verifyNoInteractions(publisher);
        verify(repository, org.mockito.Mockito.never())
            .updateName(org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void publisherThrowDoesNotPropagateOnRename() {
        CachedNode before = new CachedNode(7L, 1L, "Old", "Report", Instant.EPOCH, "u");
        CachedNode after  = new CachedNode(7L, 1L, "New", "Report", NOW, "alice");
        when(cache.getById(7L)).thenReturn(Optional.of(before), Optional.of(after));
        when(timeMapper.now()).thenReturn(NOW);
        when(instanceIdProvider.getInstanceId()).thenReturn("inst-1");
        when(sequenceGenerator.next()).thenReturn(1L);
        doThrow(new RuntimeException("bus down")).when(publisher).publish(any());

        CachedNode[] result = new CachedNode[1];
        assertThatCode(() -> result[0] = service.renameItem(7L, "New", CTX)).doesNotThrowAnyException();

        assertThat(result[0]).isEqualTo(after);
        verify(repository).updateName(7L, "New", NOW, "alice");
        verify(cache).applyRename(7L, "New", NOW, "alice");
        verify(publisher).publish(any());
    }
}

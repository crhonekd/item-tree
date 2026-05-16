package com.myxcomp.ice.xtree.service;

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
import com.myxcomp.ice.xtree.persistence.ItemTreeRepository;
import com.myxcomp.ice.xtree.policy.TypePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.SyncTaskExecutor;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
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

    ItemService service;
    static final UserContext CTX = new UserContext("alice", null);

    @BeforeEach
    void setUp() {
        service = new ItemService(
                cache, repository, policy, converter, publisher,
                timeMapper, instanceIdProvider, sequenceGenerator,
                new SyncTaskExecutor());
    }

    @Test
    void deleteCascadesAndBroadcasts() {
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
        when(repository.cascadeDeleteSubtree(999L)).thenReturn(List.of());

        service.deleteItem(999L, CTX);

        verify(repository).cascadeDeleteSubtree(999L);
        verifyNoInteractions(publisher);
        verify(cache, never()).applyDelete(any());
    }
}

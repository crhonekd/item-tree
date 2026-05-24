package com.myxcomp.ice.xtree.service;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.TreeCache;
import com.myxcomp.ice.xtree.common.InstanceIdProvider;
import com.myxcomp.ice.xtree.common.TimeMapper;
import com.myxcomp.ice.xtree.common.UserContext;
import com.myxcomp.ice.xtree.config.CopyProperties;
import com.myxcomp.ice.xtree.conversion.XmlJsonConverter;
import com.myxcomp.ice.xtree.messaging.EventPublisher;
import com.myxcomp.ice.xtree.messaging.SequenceGenerator;
import com.myxcomp.ice.xtree.messaging.event.OperationType;
import com.myxcomp.ice.xtree.messaging.event.TreeMutationEvent;
import com.myxcomp.ice.xtree.messaging.event.payload.CopyPayload;
import com.myxcomp.ice.xtree.persistence.ItemTreeFullRow;
import com.myxcomp.ice.xtree.persistence.ItemTreeRepository;
import com.myxcomp.ice.xtree.policy.TypePolicy;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
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

    ItemService service;

    @BeforeEach
    void setUp() {
        when(copyProperties.maxNodes()).thenReturn(100);
        service = new ItemService(cache, repository, policy, converter, publisher,
                timeMapper, instanceIdProvider, sequenceGenerator,
                new SyncTaskExecutor(), new SimpleMeterRegistry(), copyProperties);
    }

    @Nested
    class CopyItem {

        private final Instant T = Instant.parse("2026-05-24T10:00:00Z");
        private final UserContext ctx = new UserContext("alice", null);

        @BeforeEach
        void setup() {
            when(timeMapper.now()).thenReturn(T);
            when(instanceIdProvider.getInstanceId()).thenReturn("inst-1");
            when(sequenceGenerator.next()).thenReturn(1L);
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
            when(cache.findHomeFolder("alice")).thenReturn(Optional.of(dest));
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
    }
}

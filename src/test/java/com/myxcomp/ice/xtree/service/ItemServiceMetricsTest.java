package com.myxcomp.ice.xtree.service;

import com.myxcomp.ice.xtree.cache.TreeCache;
import com.myxcomp.ice.xtree.common.InstanceIdProvider;
import com.myxcomp.ice.xtree.common.TimeMapper;
import com.myxcomp.ice.xtree.common.UserContext;
import com.myxcomp.ice.xtree.conversion.XmlJsonConverter;
import com.myxcomp.ice.xtree.messaging.EventPublisher;
import com.myxcomp.ice.xtree.messaging.SequenceGenerator;
import com.myxcomp.ice.xtree.persistence.ItemTreeRepository;
import com.myxcomp.ice.xtree.policy.TypePolicy;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ItemServiceMetricsTest {

    private TreeCache cache;
    private ItemTreeRepository repository;
    private TypePolicy policy;
    private XmlJsonConverter converter;
    private EventPublisher publisher;
    private TimeMapper timeMapper;
    private SimpleMeterRegistry meterRegistry;
    private ItemService service;

    @BeforeEach
    void setUp() {
        cache = mock(TreeCache.class);
        repository = mock(ItemTreeRepository.class);
        policy = mock(TypePolicy.class);
        converter = mock(XmlJsonConverter.class);
        publisher = mock(EventPublisher.class);
        timeMapper = mock(TimeMapper.class);
        when(timeMapper.now()).thenReturn(Instant.parse("2026-05-18T10:00:00Z"));
        InstanceIdProvider instanceIdProvider = mock(InstanceIdProvider.class);
        when(instanceIdProvider.getInstanceId()).thenReturn("test-instance");
        SequenceGenerator seq = new SequenceGenerator();
        meterRegistry = new SimpleMeterRegistry();
        service = new ItemService(cache, repository, policy, converter, publisher,
                timeMapper, instanceIdProvider, seq, new SyncTaskExecutor(), meterRegistry);
    }

    @Test
    void deleteRecordsCascadeSize() {
        when(repository.cascadeDeleteSubtree(anyLong())).thenReturn(List.of(10L, 11L, 12L, 13L));

        service.deleteItem(10L, new UserContext("u", null));

        DistributionSummary summary = meterRegistry.find("itemtree.delete.cascade.size").summary();
        assertThat(summary).isNotNull();
        assertThat(summary.count()).isOne();
        assertThat(summary.totalAmount()).isEqualTo(4.0);
    }

    @Test
    void deleteOnUnknownIdDoesNotRecordCascadeSize() {
        when(repository.cascadeDeleteSubtree(anyLong())).thenReturn(List.of());

        service.deleteItem(999L, new UserContext("u", null));

        DistributionSummary summary = meterRegistry.find("itemtree.delete.cascade.size").summary();
        assertThat(summary == null || summary.count() == 0L).isTrue();
    }
}

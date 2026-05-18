package com.myxcomp.ice.xtree.service;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.TreeCache;
import com.myxcomp.ice.xtree.common.InstanceIdProvider;
import com.myxcomp.ice.xtree.common.TimeMapper;
import com.myxcomp.ice.xtree.common.Types;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.myxcomp.ice.xtree.persistence.PayloadRow;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
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

    @Test
    void createRejectsDataOnTypeWithoutDataIncrementsValidationRejectionCounter() {
        when(cache.getById(1L)).thenReturn(Optional.of(
                new CachedNode(1L, 0L, "root", Types.FOLDER, Instant.EPOCH, "u")));
        when(policy.isKnown("Folder")).thenReturn(true);
        when(policy.hasData("Folder")).thenReturn(false);

        assertThatThrownBy(() ->
                service.createItem(1L, "child", "Folder", "{\"k\":1}", new UserContext("u", null))
        ).isInstanceOf(com.myxcomp.ice.xtree.service.exception.ValidationException.class);

        assertThat(meterRegistry.find("itemtree.policy.validation_rejection")
                .tag("reason", "TYPE_CANNOT_HAVE_DATA").counter())
                .isNotNull();
    }

    @Test
    void createRejectsMissingDataOnTypeWithDataIncrementsValidationRejectionCounter() {
        when(cache.getById(1L)).thenReturn(Optional.of(
                new CachedNode(1L, 0L, "root", Types.FOLDER, Instant.EPOCH, "u")));
        when(policy.isKnown("Report")).thenReturn(true);
        when(policy.hasData("Report")).thenReturn(true);

        assertThatThrownBy(() ->
                service.createItem(1L, "child", "Report", null, new UserContext("u", null))
        ).isInstanceOf(com.myxcomp.ice.xtree.service.exception.ValidationException.class);

        assertThat(meterRegistry.find("itemtree.policy.validation_rejection")
                .tag("reason", "DATA_REQUIRED").counter())
                .isNotNull();
    }

    @Test
    void createWithUnknownTypeIncrementsUnknownTypeCounter() {
        when(cache.getById(1L)).thenReturn(Optional.of(
                new CachedNode(1L, 0L, "root", Types.FOLDER, Instant.EPOCH, "u")));
        when(policy.isKnown("MyExoticType")).thenReturn(false);
        when(policy.hasData("MyExoticType")).thenReturn(true);
        when(policy.isAlsoPersistedAsXmlOnWrite("MyExoticType")).thenReturn(false);
        when(repository.insert(anyLong(), anyString(), anyString(), anyString(),
                isNull(), any(Instant.class), anyString())).thenReturn(42L);

        service.createItem(1L, "child", "MyExoticType", "{\"k\":1}", new UserContext("u", null));

        assertThat(meterRegistry.find("itemtree.policy.unknown_type")
                .tag("type", "MyExoticType").counter())
                .isNotNull();
    }

    @Test
    void createWithJsonToXmlFailureIncrementsTaggedCounter() {
        when(cache.getById(1L)).thenReturn(Optional.of(
                new CachedNode(1L, 0L, "root", Types.FOLDER, Instant.EPOCH, "u")));
        when(policy.isKnown("Report")).thenReturn(true);
        when(policy.hasData("Report")).thenReturn(true);
        when(policy.isAlsoPersistedAsXmlOnWrite("Report")).thenReturn(true);
        when(converter.jsonToXml(anyString()))
                .thenThrow(new IllegalArgumentException("bad json"));

        assertThatThrownBy(() ->
                service.createItem(1L, "r", "Report", "{\"k\":1}", new UserContext("u", null))
        ).isInstanceOf(IllegalArgumentException.class);

        assertThat(meterRegistry.find("itemtree.conversion.json_to_xml.failure")
                .tag("type", "Report").counter())
                .isNotNull();
    }

    @Test
    void getItemsWithDataXmlToJsonFailureIncrementsTaggedCounter() {
        CachedNode reportNode = new CachedNode(42L, 1L, "r", "Report", Instant.EPOCH, "u");
        when(cache.getById(42L)).thenReturn(Optional.of(reportNode));
        when(policy.hasData("Report")).thenReturn(true);
        when(policy.isSentAsXmlToUi("Report")).thenReturn(false);
        when(repository.findPayloadByIds(anyCollection())).thenReturn(
                List.of(new PayloadRow(42L, null, "<root><k>1</k></root>")));
        when(converter.xmlToJson(anyString()))
                .thenThrow(new IllegalArgumentException("bad xml"));

        assertThatThrownBy(() ->
                service.getItemsWithData(List.of(42L))
        ).isInstanceOf(IllegalArgumentException.class);

        assertThat(meterRegistry.find("itemtree.conversion.xml_to_json.failure")
                .tag("type", "Report").counter())
                .isNotNull();
    }
}

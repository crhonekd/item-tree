package com.myxcomp.ice.xtree.service;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.TreeCache;
import com.myxcomp.ice.xtree.common.InstanceIdProvider;
import com.myxcomp.ice.xtree.common.TimeMapper;
import com.myxcomp.ice.xtree.common.UserContext;
import com.myxcomp.ice.xtree.conversion.XmlJsonConverter;
import com.myxcomp.ice.xtree.messaging.EventPublisher;
import com.myxcomp.ice.xtree.messaging.SequenceGenerator;
import com.myxcomp.ice.xtree.persistence.ItemTreeRepository;
import com.myxcomp.ice.xtree.persistence.JsonBackfillRow;
import com.myxcomp.ice.xtree.persistence.PayloadRow;
import com.myxcomp.ice.xtree.policy.TypePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ItemServiceGetItemsTest {

    @Mock TreeCache cache;
    @Mock ItemTreeRepository repository;
    @Mock TypePolicy policy;
    @Mock XmlJsonConverter converter;
    @Mock EventPublisher publisher;
    @Mock TimeMapper timeMapper;
    @Mock InstanceIdProvider instanceIdProvider;
    @Mock SequenceGenerator sequenceGenerator;

    ItemService service;
    static final Instant T = Instant.EPOCH;

    @BeforeEach
    void setUp() {
        service = new ItemService(cache, repository, policy, converter, publisher,
                timeMapper, instanceIdProvider, sequenceGenerator, new SyncTaskExecutor(),
                new SimpleMeterRegistry());
    }

    private CachedNode folder(long id, long parentId, String name) {
        return new CachedNode(id, parentId, name, "Folder", T, "sys");
    }
    private CachedNode leaf(long id, long parentId, String name, String type) {
        return new CachedNode(id, parentId, name, type, T, "sys");
    }

    @Test
    void emptyInputReturnsEmptyList() {
        assertThat(service.getItemsWithData(List.of())).isEmpty();
        verifyNoInteractions(repository);
        verifyNoInteractions(publisher);
    }

    @Test
    void missingIdsSilentlyOmitted() {
        when(cache.getById(7L)).thenReturn(Optional.empty());

        List<ItemWithData> result = service.getItemsWithData(List.of(7L));

        assertThat(result).isEmpty();
        verify(repository, never()).findPayloadByIds(anyCollection());
    }

    @Test
    void leafWithJsonPresentReturnsDataJsonNoBackfill() {
        CachedNode node = leaf(7L, 1L, "Doc", "Report");
        when(cache.getById(7L)).thenReturn(Optional.of(node));
        when(policy.hasData("Report")).thenReturn(true);
        when(policy.isSentAsXmlToUi("Report")).thenReturn(false);
        when(repository.findPayloadByIds(List.of(7L)))
                .thenReturn(List.of(new PayloadRow(7L, "{\"a\":1}", null)));

        List<ItemWithData> result = service.getItemsWithData(List.of(7L));

        assertThat(result).hasSize(1);
        ItemWithData out = result.get(0);
        assertThat(out.itemTreeId()).isEqualTo(7L);
        assertThat(out.dataJson()).isEqualTo("{\"a\":1}");
        assertThat(out.dataXml()).isNull();
        assertThat(out.children()).isNull();
        verify(repository, never()).backfillJsonWhereNull(anyCollection());
    }

    @Test
    void leafWithJsonNullAndXmlPresentConvertsAndSchedulesBackfill() {
        CachedNode node = leaf(7L, 1L, "Doc", "Report");
        when(cache.getById(7L)).thenReturn(Optional.of(node));
        when(policy.hasData("Report")).thenReturn(true);
        when(policy.isSentAsXmlToUi("Report")).thenReturn(false);
        when(repository.findPayloadByIds(List.of(7L)))
                .thenReturn(List.of(new PayloadRow(7L, null, "<a>1</a>")));
        when(converter.xmlToJson("<a>1</a>")).thenReturn("{\"a\":1}");

        List<ItemWithData> result = service.getItemsWithData(List.of(7L));

        assertThat(result).hasSize(1);
        ItemWithData out = result.get(0);
        assertThat(out.dataJson()).isEqualTo("{\"a\":1}");
        assertThat(out.dataXml()).isNull();

        ArgumentCaptor<Collection<JsonBackfillRow>> cap = ArgumentCaptor.forClass(Collection.class);
        verify(repository).backfillJsonWhereNull(cap.capture());
        assertThat(cap.getValue()).containsExactly(new JsonBackfillRow(7L, "{\"a\":1}"));
    }

    @Test
    void leafWithBothNullReturnsNullDataAndNoBackfill() {
        CachedNode node = leaf(7L, 1L, "Doc", "Report");
        when(cache.getById(7L)).thenReturn(Optional.of(node));
        when(policy.hasData("Report")).thenReturn(true);
        when(policy.isSentAsXmlToUi("Report")).thenReturn(false);
        when(repository.findPayloadByIds(List.of(7L)))
                .thenReturn(List.of(new PayloadRow(7L, null, null)));

        List<ItemWithData> result = service.getItemsWithData(List.of(7L));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).dataJson()).isNull();
        assertThat(result.get(0).dataXml()).isNull();
        verify(repository, never()).backfillJsonWhereNull(anyCollection());
    }

    @Test
    void typeWithoutDataNeverConsultsPayloadColumns() {
        CachedNode shortcut = leaf(7L, 1L, "S", "Shortcut");
        when(cache.getById(7L)).thenReturn(Optional.of(shortcut));
        when(policy.hasData("Shortcut")).thenReturn(false);

        List<ItemWithData> result = service.getItemsWithData(List.of(7L));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).dataJson()).isNull();
        assertThat(result.get(0).dataXml()).isNull();
        verify(repository, never()).findPayloadByIds(anyCollection());
    }

    @Test
    void folderExpandsOneLevelOfChildrenWithPayloadShaping() {
        CachedNode parent = folder(2L, 1L, "Box");
        CachedNode dataChild = leaf(3L, 2L, "Doc", "Report");
        CachedNode noDataChild = leaf(4L, 2L, "Short", "Shortcut");
        when(cache.getById(2L)).thenReturn(Optional.of(parent));
        when(cache.getChildren(2L)).thenReturn(List.of(dataChild, noDataChild));
        when(policy.hasData("Report")).thenReturn(true);
        when(policy.hasData("Shortcut")).thenReturn(false);
        when(policy.isSentAsXmlToUi("Report")).thenReturn(false);
        when(repository.findPayloadByIds(List.of(3L)))
                .thenReturn(List.of(new PayloadRow(3L, "{\"k\":1}", null)));

        List<ItemWithData> result = service.getItemsWithData(List.of(2L));

        assertThat(result).hasSize(1);
        ItemWithData folderItem = result.get(0);
        assertThat(folderItem.dataJson()).isNull();
        assertThat(folderItem.dataXml()).isNull();
        assertThat(folderItem.children()).hasSize(2);

        ItemWithData childData = folderItem.children().stream()
                .filter(c -> c.itemTreeId() == 3L).findFirst().orElseThrow();
        assertThat(childData.dataJson()).isEqualTo("{\"k\":1}");
        assertThat(childData.children()).isNull();

        ItemWithData childNoData = folderItem.children().stream()
                .filter(c -> c.itemTreeId() == 4L).findFirst().orElseThrow();
        assertThat(childNoData.dataJson()).isNull();
        assertThat(childNoData.dataXml()).isNull();
        assertThat(childNoData.children()).isNull();
    }

    @Test
    void batchedPayloadFetchAndBackfill() {
        CachedNode a = leaf(7L, 1L, "A", "Report");
        CachedNode b = leaf(8L, 1L, "B", "Report");
        when(cache.getById(7L)).thenReturn(Optional.of(a));
        when(cache.getById(8L)).thenReturn(Optional.of(b));
        when(policy.hasData("Report")).thenReturn(true);
        when(policy.isSentAsXmlToUi("Report")).thenReturn(false);
        when(repository.findPayloadByIds(anyCollection())).thenReturn(List.of(
                new PayloadRow(7L, null, "<a/>"),
                new PayloadRow(8L, "{\"b\":2}", null)
        ));
        when(converter.xmlToJson("<a/>")).thenReturn("{}");

        service.getItemsWithData(List.of(7L, 8L));

        ArgumentCaptor<Collection<JsonBackfillRow>> cap = ArgumentCaptor.forClass(Collection.class);
        verify(repository).backfillJsonWhereNull(cap.capture());
        assertThat(cap.getValue()).containsExactly(new JsonBackfillRow(7L, "{}"));
    }

    @Test
    void xmlToUiTypeSendXmlWhenPresent() {
        CachedNode node = leaf(7L, 1L, "Doc", "LegacyType");
        when(cache.getById(7L)).thenReturn(Optional.of(node));
        when(policy.hasData("LegacyType")).thenReturn(true);
        when(policy.isSentAsXmlToUi("LegacyType")).thenReturn(true);
        when(repository.findPayloadByIds(anyCollection()))
                .thenReturn(List.of(new PayloadRow(7L, null, "<x>1</x>")));

        List<ItemWithData> result = service.getItemsWithData(List.of(7L));

        assertThat(result).hasSize(1);
        ItemWithData out = result.get(0);
        assertThat(out.dataXml()).isEqualTo("<x>1</x>");
        assertThat(out.dataJson()).isNull();
        verify(repository, never()).backfillJsonWhereNull(anyCollection());
    }

    @Test
    void xmlToUiTypeFallsBackToConvertingJsonWhenXmlAbsent() {
        CachedNode node = leaf(7L, 1L, "Doc", "LegacyType");
        when(cache.getById(7L)).thenReturn(Optional.of(node));
        when(policy.hasData("LegacyType")).thenReturn(true);
        when(policy.isSentAsXmlToUi("LegacyType")).thenReturn(true);
        when(repository.findPayloadByIds(anyCollection()))
                .thenReturn(List.of(new PayloadRow(7L, "{\"j\":1}", null)));
        when(converter.jsonToXml("{\"j\":1}")).thenReturn("<j>1</j>");

        List<ItemWithData> result = service.getItemsWithData(List.of(7L));

        assertThat(result).hasSize(1);
        ItemWithData out = result.get(0);
        assertThat(out.dataXml()).isEqualTo("<j>1</j>");
        assertThat(out.dataJson()).isNull();
        verify(repository, never()).backfillJsonWhereNull(anyCollection());
    }

    @Test
    void nullElementsInInputListAreSilentlySkipped() {
        CachedNode node = leaf(7L, 1L, "Doc", "Report");
        when(cache.getById(7L)).thenReturn(Optional.of(node));
        when(policy.hasData("Report")).thenReturn(true);
        when(policy.isSentAsXmlToUi("Report")).thenReturn(false);
        when(repository.findPayloadByIds(anyCollection()))
                .thenReturn(List.of(new PayloadRow(7L, "{\"a\":1}", null)));

        // List contains a null element alongside a valid id
        List<Long> idsWithNull = new java.util.ArrayList<>();
        idsWithNull.add(null);
        idsWithNull.add(7L);

        List<ItemWithData> result = service.getItemsWithData(idsWithNull);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).itemTreeId()).isEqualTo(7L);
    }

    @Test
    void backfillQueueSaturationDoesNotLeakExceptionAndResultIsCorrect() {
        CachedNode node = leaf(7L, 1L, "Doc", "Report");
        when(cache.getById(7L)).thenReturn(Optional.of(node));
        when(policy.hasData("Report")).thenReturn(true);
        when(policy.isSentAsXmlToUi("Report")).thenReturn(false);
        when(repository.findPayloadByIds(anyCollection()))
                .thenReturn(List.of(new PayloadRow(7L, null, "<a>1</a>")));
        when(converter.xmlToJson("<a>1</a>")).thenReturn("{\"a\":1}");

        ItemService saturatingService = new ItemService(
                cache, repository, policy, converter, publisher,
                timeMapper, instanceIdProvider, sequenceGenerator,
                task -> { throw new TaskRejectedException("queue full"); },
                new SimpleMeterRegistry());

        List<ItemWithData> result = saturatingService.getItemsWithData(List.of(7L));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).itemTreeId()).isEqualTo(7L);
        assertThat(result.get(0).dataJson()).isEqualTo("{\"a\":1}");
        assertThat(result.get(0).dataXml()).isNull();
        verify(repository, never()).backfillJsonWhereNull(anyCollection());
    }

    @Test
    void backfillInnerFailureIsSwallowedAndResultIsCorrect() {
        CachedNode node = leaf(7L, 1L, "Doc", "Report");
        when(cache.getById(7L)).thenReturn(Optional.of(node));
        when(policy.hasData("Report")).thenReturn(true);
        when(policy.isSentAsXmlToUi("Report")).thenReturn(false);
        when(repository.findPayloadByIds(anyCollection()))
                .thenReturn(List.of(new PayloadRow(7L, null, "<a>1</a>")));
        when(converter.xmlToJson("<a>1</a>")).thenReturn("{\"a\":1}");
        when(repository.backfillJsonWhereNull(anyCollection()))
                .thenThrow(new RuntimeException("db gone"));

        List<ItemWithData> result = service.getItemsWithData(List.of(7L));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).itemTreeId()).isEqualTo(7L);
        assertThat(result.get(0).dataJson()).isEqualTo("{\"a\":1}");
        assertThat(result.get(0).dataXml()).isNull();
        verify(repository).backfillJsonWhereNull(anyCollection());
    }
}

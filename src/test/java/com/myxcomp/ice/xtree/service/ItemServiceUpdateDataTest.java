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
import com.myxcomp.ice.xtree.messaging.event.payload.UpdatePayload;
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
import org.springframework.core.task.SyncTaskExecutor;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ItemServiceUpdateDataTest {

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
    void updateJsonOnlyHappyPath() {
        CachedNode before = new CachedNode(7L, 1L, "Doc", "Report", Instant.EPOCH, "u");
        CachedNode after  = new CachedNode(7L, 1L, "Doc", "Report", NOW, "alice");
        when(cache.getById(7L)).thenReturn(Optional.of(before), Optional.of(after));
        when(policy.hasData("Report")).thenReturn(true);
        when(policy.isAlsoPersistedAsXmlOnWrite("Report")).thenReturn(false);
        when(timeMapper.now()).thenReturn(NOW);
        when(instanceIdProvider.getInstanceId()).thenReturn("inst-1");
        when(sequenceGenerator.next()).thenReturn(3L);

        CachedNode result = service.updateItemData(7L, "{\"a\":2}", CTX);

        assertThat(result).isEqualTo(after);
        InOrder order = inOrder(repository, cache, publisher);
        order.verify(repository).updateJson(7L, "{\"a\":2}", null, NOW, "alice");
        order.verify(cache).applyMetadataUpdate(7L, NOW, "alice");

        ArgumentCaptor<TreeMutationEvent> cap = ArgumentCaptor.forClass(TreeMutationEvent.class);
        order.verify(publisher).publish(cap.capture());
        assertThat(cap.getValue().getOperationType()).isEqualTo(OperationType.UPDATE);
        UpdatePayload payload = (UpdatePayload) cap.getValue().getPayload();
        assertThat(payload.itemTreeId()).isEqualTo(7L);
        assertThat(payload.lastUpdate()).isEqualTo(NOW);
        assertThat(payload.lastUpdateUser()).isEqualTo("alice");
        verifyNoInteractions(converter);
    }

    @Test
    void updateWithXmlFanOutConvertsAndPersistsBoth() {
        CachedNode before = new CachedNode(7L, 1L, "Doc", "Report", Instant.EPOCH, "u");
        when(cache.getById(7L)).thenReturn(Optional.of(before), Optional.of(before));
        when(policy.hasData("Report")).thenReturn(true);
        when(policy.isAlsoPersistedAsXmlOnWrite("Report")).thenReturn(true);
        when(converter.jsonToXml("{\"a\":2}")).thenReturn("<a>2</a>");
        when(timeMapper.now()).thenReturn(NOW);
        when(instanceIdProvider.getInstanceId()).thenReturn("inst-1");
        when(sequenceGenerator.next()).thenReturn(3L);

        service.updateItemData(7L, "{\"a\":2}", CTX);

        verify(converter).jsonToXml("{\"a\":2}");
        verify(repository).updateJson(7L, "{\"a\":2}", "<a>2</a>", NOW, "alice");
    }

    @Test
    void rejectsItemNotFound() {
        when(cache.getById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateItemData(99L, "{}", CTX))
                .isInstanceOf(NotFoundException.class)
                .satisfies(t -> assertThat(((NotFoundException) t).errorCode())
                        .isEqualTo(ErrorCode.ITEM_NOT_FOUND));
        verifyNoInteractions(publisher);
    }

    @Test
    void rejectsFolderCannotHaveData() {
        CachedNode folder = new CachedNode(7L, 1L, "F", "Folder", Instant.EPOCH, "u");
        when(cache.getById(7L)).thenReturn(Optional.of(folder));

        assertThatThrownBy(() -> service.updateItemData(7L, "{}", CTX))
                .isInstanceOf(ValidationException.class)
                .satisfies(t -> assertThat(((ValidationException) t).errorCode())
                        .isEqualTo(ErrorCode.FOLDER_CANNOT_HAVE_DATA));
        verifyNoInteractions(publisher);
    }

    @Test
    void rejectsTypeCannotHaveDataForNonFolderTypesWithoutData() {
        CachedNode shortcut = new CachedNode(7L, 1L, "S", "Shortcut", Instant.EPOCH, "u");
        when(cache.getById(7L)).thenReturn(Optional.of(shortcut));
        when(policy.hasData("Shortcut")).thenReturn(false);

        assertThatThrownBy(() -> service.updateItemData(7L, "{}", CTX))
                .isInstanceOf(ValidationException.class)
                .satisfies(t -> assertThat(((ValidationException) t).errorCode())
                        .isEqualTo(ErrorCode.TYPE_CANNOT_HAVE_DATA));
        verifyNoInteractions(publisher);
    }

    @Test
    void rejectsNullDataJson() {
        CachedNode node = new CachedNode(7L, 1L, "Doc", "Report", Instant.EPOCH, "u");
        when(cache.getById(7L)).thenReturn(Optional.of(node));
        when(policy.hasData("Report")).thenReturn(true);

        assertThatThrownBy(() -> service.updateItemData(7L, null, CTX))
                .isInstanceOf(ValidationException.class)
                .satisfies(t -> assertThat(((ValidationException) t).errorCode())
                        .isEqualTo(ErrorCode.DATA_REQUIRED));
        verifyNoInteractions(publisher);
    }
}

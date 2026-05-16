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
import com.myxcomp.ice.xtree.messaging.event.payload.CreatePayload;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ItemServiceCreateTest {

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
    static final UserContext CTX_DIRECT     = new UserContext("alice", null);
    static final UserContext CTX_IMPERSONAT = new UserContext("alice", "bob");

    @BeforeEach
    void setUp() {
        service = new ItemService(
                cache, repository, policy, converter, publisher,
                timeMapper, instanceIdProvider, sequenceGenerator,
                new SyncTaskExecutor());
    }

    private CachedNode folder(long id, long parentId, String name) {
        return new CachedNode(id, parentId, name, "Folder", NOW, "sys");
    }

    @Test
    void createJsonOnlyHappyPath() {
        when(cache.getById(2L)).thenReturn(Optional.of(folder(2L, 1L, "Users")));
        when(policy.hasData("Report")).thenReturn(true);
        when(policy.isAlsoPersistedAsXmlOnWrite("Report")).thenReturn(false);
        when(timeMapper.now()).thenReturn(NOW);
        when(repository.insert(eq(2L), eq("MyReport"), eq("Report"),
                eq("{\"a\":1}"), eq(null), eq(NOW), eq("alice"))).thenReturn(500L);
        when(instanceIdProvider.getInstanceId()).thenReturn("inst-1");
        when(sequenceGenerator.next()).thenReturn(7L);

        CachedNode created = service.createItem(2L, "MyReport", "Report", "{\"a\":1}", CTX_DIRECT);

        assertThat(created.itemTreeId()).isEqualTo(500L);
        assertThat(created.parentId()).isEqualTo(2L);
        assertThat(created.name()).isEqualTo("MyReport");
        assertThat(created.type()).isEqualTo("Report");
        assertThat(created.lastUpdate()).isEqualTo(NOW);
        assertThat(created.lastUpdateUser()).isEqualTo("alice");

        InOrder order = inOrder(repository, cache, publisher);
        order.verify(repository).insert(2L, "MyReport", "Report", "{\"a\":1}", null, NOW, "alice");
        order.verify(cache).applyCreate(created);

        ArgumentCaptor<TreeMutationEvent> evCap = ArgumentCaptor.forClass(TreeMutationEvent.class);
        order.verify(publisher).publish(evCap.capture());
        TreeMutationEvent ev = evCap.getValue();
        assertThat(ev.getEventId()).isNotBlank();
        assertThat(ev.getInstanceId()).isEqualTo("inst-1");
        assertThat(ev.getSequence()).isEqualTo(7L);
        assertThat(ev.getOccurredAt()).isEqualTo(NOW);
        assertThat(ev.getIceUser()).isEqualTo("alice");
        assertThat(ev.getImpersonatedUser()).isNull();
        assertThat(ev.getOperationType()).isEqualTo(OperationType.CREATE);
        assertThat(ev.getPayload()).isInstanceOf(CreatePayload.class);
        CreatePayload p = (CreatePayload) ev.getPayload();
        assertThat(p.itemTreeId()).isEqualTo(500L);
        assertThat(p.parentId()).isEqualTo(2L);
        assertThat(p.name()).isEqualTo("MyReport");
        assertThat(p.type()).isEqualTo("Report");
        assertThat(p.lastUpdate()).isEqualTo(NOW);
        assertThat(p.lastUpdateUser()).isEqualTo("alice");
        verifyNoInteractions(converter);
    }

    @Test
    void impersonatedUserStampingUsesImpersonated() {
        when(cache.getById(2L)).thenReturn(Optional.of(folder(2L, 1L, "Users")));
        when(policy.hasData("Report")).thenReturn(true);
        when(timeMapper.now()).thenReturn(NOW);
        when(repository.insert(anyLong(), anyString(), anyString(),
                anyString(), eq(null), any(), anyString())).thenReturn(501L);
        when(instanceIdProvider.getInstanceId()).thenReturn("inst-1");
        when(sequenceGenerator.next()).thenReturn(1L);

        service.createItem(2L, "R", "Report", "{}", CTX_IMPERSONAT);

        verify(repository).insert(2L, "R", "Report", "{}", null, NOW, "bob");
        ArgumentCaptor<TreeMutationEvent> evCap = ArgumentCaptor.forClass(TreeMutationEvent.class);
        verify(publisher).publish(evCap.capture());
        assertThat(evCap.getValue().getIceUser()).isEqualTo("alice");
        assertThat(evCap.getValue().getImpersonatedUser()).isEqualTo("bob");
    }

    @Test
    void createWithXmlFanOutConvertsAndPersistsBoth() {
        when(cache.getById(2L)).thenReturn(Optional.of(folder(2L, 1L, "Users")));
        when(policy.hasData("Report")).thenReturn(true);
        when(policy.isAlsoPersistedAsXmlOnWrite("Report")).thenReturn(true);
        when(converter.jsonToXml("{\"a\":1}")).thenReturn("<a>1</a>");
        when(timeMapper.now()).thenReturn(NOW);
        when(repository.insert(2L, "R", "Report", "{\"a\":1}", "<a>1</a>", NOW, "alice"))
                .thenReturn(502L);
        when(instanceIdProvider.getInstanceId()).thenReturn("inst-1");
        when(sequenceGenerator.next()).thenReturn(1L);

        service.createItem(2L, "R", "Report", "{\"a\":1}", CTX_DIRECT);

        verify(converter).jsonToXml("{\"a\":1}");
        verify(repository).insert(2L, "R", "Report", "{\"a\":1}", "<a>1</a>", NOW, "alice");
    }

    @Test
    void createFolderHappyPath() {
        when(cache.getById(2L)).thenReturn(Optional.of(folder(2L, 1L, "Users")));
        when(policy.hasData("Folder")).thenReturn(false);
        when(timeMapper.now()).thenReturn(NOW);
        when(repository.insert(2L, "Sub", "Folder", null, null, NOW, "alice")).thenReturn(503L);
        when(instanceIdProvider.getInstanceId()).thenReturn("inst-1");
        when(sequenceGenerator.next()).thenReturn(1L);

        CachedNode out = service.createItem(2L, "Sub", "Folder", null, CTX_DIRECT);

        assertThat(out.type()).isEqualTo("Folder");
        verify(repository).insert(2L, "Sub", "Folder", null, null, NOW, "alice");
        verifyNoInteractions(converter);
    }

    @Test
    void rejectsParentNotFound() {
        when(cache.getById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createItem(999L, "X", "Report", "{}", CTX_DIRECT))
                .isInstanceOf(NotFoundException.class)
                .satisfies(t -> assertThat(((NotFoundException) t).errorCode())
                        .isEqualTo(ErrorCode.PARENT_NOT_FOUND));

        verify(repository, never()).insert(anyLong(), anyString(), anyString(),
                anyString(), anyString(), any(), anyString());
        verifyNoInteractions(publisher);
    }

    @Test
    void rejectsParentNotFolder() {
        CachedNode nonFolder = new CachedNode(2L, 1L, "Doc", "Report", NOW, "sys");
        when(cache.getById(2L)).thenReturn(Optional.of(nonFolder));

        assertThatThrownBy(() -> service.createItem(2L, "X", "Report", "{}", CTX_DIRECT))
                .isInstanceOf(ValidationException.class)
                .satisfies(t -> assertThat(((ValidationException) t).errorCode())
                        .isEqualTo(ErrorCode.PARENT_NOT_FOLDER));

        verify(repository, never()).insert(anyLong(), anyString(), anyString(),
                any(), any(), any(), anyString());
        verifyNoInteractions(publisher);
    }

    @Test
    void rejectsTypeCannotHaveData() {
        when(cache.getById(2L)).thenReturn(Optional.of(folder(2L, 1L, "Users")));
        when(policy.hasData("Folder")).thenReturn(false);

        assertThatThrownBy(() -> service.createItem(2L, "X", "Folder", "{}", CTX_DIRECT))
                .isInstanceOf(ValidationException.class)
                .satisfies(t -> assertThat(((ValidationException) t).errorCode())
                        .isEqualTo(ErrorCode.TYPE_CANNOT_HAVE_DATA));

        verify(repository, never()).insert(anyLong(), anyString(), anyString(),
                any(), any(), any(), anyString());
        verifyNoInteractions(publisher);
    }

    @Test
    void rejectsDataRequired() {
        when(cache.getById(2L)).thenReturn(Optional.of(folder(2L, 1L, "Users")));
        when(policy.hasData("Report")).thenReturn(true);

        assertThatThrownBy(() -> service.createItem(2L, "X", "Report", null, CTX_DIRECT))
                .isInstanceOf(ValidationException.class)
                .satisfies(t -> assertThat(((ValidationException) t).errorCode())
                        .isEqualTo(ErrorCode.DATA_REQUIRED));

        verify(repository, never()).insert(anyLong(), anyString(), anyString(),
                any(), any(), any(), anyString());
        verifyNoInteractions(publisher);
    }

    @Test
    void publisherThrowDoesNotPropagateOnCreate() {
        when(cache.getById(2L)).thenReturn(Optional.of(folder(2L, 1L, "Users")));
        when(policy.hasData("Report")).thenReturn(true);
        when(policy.isAlsoPersistedAsXmlOnWrite("Report")).thenReturn(false);
        when(timeMapper.now()).thenReturn(NOW);
        when(repository.insert(eq(2L), eq("R"), eq("Report"), eq("{}"), eq(null), eq(NOW), eq("alice")))
                .thenReturn(600L);
        when(instanceIdProvider.getInstanceId()).thenReturn("inst-1");
        when(sequenceGenerator.next()).thenReturn(1L);
        doThrow(new RuntimeException("bus down")).when(publisher).publish(any());

        CachedNode[] result = new CachedNode[1];
        assertThatCode(() -> result[0] = service.createItem(2L, "R", "Report", "{}", CTX_DIRECT))
                .doesNotThrowAnyException();

        assertThat(result[0].itemTreeId()).isEqualTo(600L);
        verify(repository).insert(eq(2L), eq("R"), eq("Report"), eq("{}"), eq(null), eq(NOW), eq("alice"));
        verify(cache).applyCreate(result[0]);
        verify(publisher).publish(any());
    }
}

package com.myxcomp.ice.xtree.messaging;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.TreeCache;
import com.myxcomp.ice.xtree.messaging.event.OperationType;
import com.myxcomp.ice.xtree.messaging.event.TreeMutationEvent;
import com.myxcomp.ice.xtree.messaging.event.payload.CreatePayload;
import com.myxcomp.ice.xtree.messaging.event.payload.DeletePayload;
import com.myxcomp.ice.xtree.messaging.event.payload.MovePayload;
import com.myxcomp.ice.xtree.messaging.event.payload.RenamePayload;
import com.myxcomp.ice.xtree.messaging.event.payload.UpdatePayload;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class EventDispatcherTest {

    private static final Instant T = Instant.parse("2026-05-17T10:00:00Z");

    private final TreeCache cache = mock(TreeCache.class);
    private final EventDispatcher dispatcher = new EventDispatcher(cache);

    private TreeMutationEvent envelope(OperationType op,
            com.myxcomp.ice.xtree.messaging.event.payload.EventPayload payload) {
        return TreeMutationEvent.builder()
                .eventId("e").instanceId("peer").sequence(1L).occurredAt(T)
                .iceUser("u").impersonatedUser(null).operationType(op)
                .payload(payload)
                .build();
    }

    @Test
    void create_calls_applyCreate_with_constructed_CachedNode() {
        CreatePayload p = new CreatePayload(100L, 1L, "N", "Folder", T, "alice");
        dispatcher.dispatch(envelope(OperationType.CREATE, p));
        verify(cache).applyCreate(new CachedNode(100L, 1L, "N", "Folder", T, "alice"));
    }

    @Test
    void update_calls_applyMetadataUpdate() {
        UpdatePayload p = new UpdatePayload(100L, T, "alice");
        dispatcher.dispatch(envelope(OperationType.UPDATE, p));
        verify(cache).applyMetadataUpdate(100L, T, "alice");
    }

    @Test
    void move_calls_applyMove() {
        MovePayload p = new MovePayload(100L, 1L, 5L, T, "alice");
        dispatcher.dispatch(envelope(OperationType.MOVE, p));
        verify(cache).applyMove(100L, 5L, T, "alice");
    }

    @Test
    void rename_calls_applyRename() {
        RenamePayload p = new RenamePayload(100L, "NewName", T, "alice");
        dispatcher.dispatch(envelope(OperationType.RENAME, p));
        verify(cache).applyRename(100L, "NewName", T, "alice");
    }

    @Test
    void delete_calls_applyDelete_with_full_set() {
        DeletePayload p = new DeletePayload(List.of(100L, 101L, 102L));
        dispatcher.dispatch(envelope(OperationType.DELETE, p));
        verify(cache).applyDelete(new HashSet<>(List.of(100L, 101L, 102L)));
    }

    @Test
    void apply_exceptions_propagate_so_caller_can_count_them() {
        doThrow(new RuntimeException("boom")).when(cache).applyCreate(any());
        CreatePayload p = new CreatePayload(100L, 1L, "N", "Folder", T, "alice");
        assertThatThrownBy(() -> dispatcher.dispatch(envelope(OperationType.CREATE, p)))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("boom");
    }

    @Test
    void null_event_throws_NPE() {
        assertThatThrownBy(() -> dispatcher.dispatch(null))
                .isInstanceOf(NullPointerException.class);
        verifyNoInteractions(cache);
    }

    @Test
    void wrong_payload_type_for_operation_throws_ClassCastException() {
        UpdatePayload wrong = new UpdatePayload(100L, T, "alice");
        assertThatThrownBy(() -> dispatcher.dispatch(envelope(OperationType.CREATE, wrong)))
                .isInstanceOf(ClassCastException.class);
    }
}

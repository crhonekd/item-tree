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
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Objects;

/**
 * Routes a {@link TreeMutationEvent} to the matching {@link TreeCache} mutation method.
 *
 * <p>Pure logic — does not handle exceptions from {@code apply*}. The caller
 * ({@link EventConsumerService}) catches and counts apply failures.
 */
@Component
public class EventDispatcher {

    private final TreeCache cache;

    public EventDispatcher(TreeCache cache) {
        this.cache = cache;
    }

    public void dispatch(TreeMutationEvent event) {
        Objects.requireNonNull(event, "event");
        OperationType op = event.getOperationType();
        switch (op) {
            case CREATE -> {
                CreatePayload p = (CreatePayload) event.getPayload();
                cache.applyCreate(new CachedNode(
                        p.itemTreeId(), p.parentId(), p.name(), p.type(),
                        p.lastUpdate(), p.lastUpdateUser()));
            }
            case UPDATE -> {
                UpdatePayload p = (UpdatePayload) event.getPayload();
                cache.applyMetadataUpdate(p.itemTreeId(), p.lastUpdate(), p.lastUpdateUser());
            }
            case MOVE -> {
                MovePayload p = (MovePayload) event.getPayload();
                cache.applyMove(p.itemTreeId(), p.newParentId(), p.lastUpdate(), p.lastUpdateUser());
            }
            case RENAME -> {
                RenamePayload p = (RenamePayload) event.getPayload();
                cache.applyRename(p.itemTreeId(), p.newName(), p.lastUpdate(), p.lastUpdateUser());
            }
            case DELETE -> {
                DeletePayload p = (DeletePayload) event.getPayload();
                cache.applyDelete(new HashSet<>(p.deletedIds()));
            }
        }
    }
}

package com.myxcomp.ice.xtree.refresh;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.TreeCache;
import com.myxcomp.ice.xtree.persistence.StructuralRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;

@Component
public class DeltaReconciler {

    private static final Logger log = LoggerFactory.getLogger(DeltaReconciler.class);

    private final TreeCache cache;

    public DeltaReconciler(TreeCache cache) {
        this.cache = cache;
    }

    /**
     * Reconciles a single DB row into the live cache. Type changes are not handled; design §5
     * treats item type as immutable post-creation. A type change would only be reflected on the
     * next full reload.
     */
    public void reconcileRow(StructuralRow row, DeltaCounters counters) {
        Optional<CachedNode> existing = cache.getById(row.itemTreeId());
        if (existing.isEmpty()) {
            cache.applyCreate(toCachedNode(row));
            counters.incrementCreated();
            return;
        }
        CachedNode node = existing.get();
        boolean parentChanged = !Objects.equals(node.parentId(), row.parentId());
        boolean nameChanged   = !Objects.equals(node.name(), row.name());

        if (parentChanged) {
            cache.applyMove(row.itemTreeId(), row.parentId(), row.lastUpdate(), row.lastUpdateUser());
            counters.incrementMoved();
        }
        if (nameChanged) {
            cache.applyRename(row.itemTreeId(), row.name(), row.lastUpdate(), row.lastUpdateUser());
            counters.incrementRenamed();
        }
        if (!parentChanged && !nameChanged) {
            boolean metaChanged = !Objects.equals(node.lastUpdate(), row.lastUpdate())
                    || !Objects.equals(node.lastUpdateUser(), row.lastUpdateUser());
            if (metaChanged) {
                cache.applyMetadataUpdate(row.itemTreeId(), row.lastUpdate(), row.lastUpdateUser());
                counters.incrementMeta();
            } else {
                log.trace("Delta no-op for id {}", row.itemTreeId());
            }
        }
    }

    private static CachedNode toCachedNode(StructuralRow row) {
        return new CachedNode(
                row.itemTreeId(),
                row.parentId(),
                row.name(),
                row.type(),
                row.lastUpdate(),
                row.lastUpdateUser());
    }
}

package com.myxcomp.ice.xtree.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

public interface ItemTreeRepository {

    void streamAllStructural(Consumer<StructuralRow> rowHandler);

    List<StructuralRow> findStructuralChangedSince(Instant since);

    List<PayloadRow> findPayloadByIds(Collection<Long> ids);

    int backfillJsonWhereNull(Collection<JsonBackfillRow> rows);

    long insert(long parentId, String name, String type,
                String jsonOrNull, String xmlOrNull,
                Instant lastUpdate, String lastUpdateUser);

    void updateJson(long id, String json, String xmlOrNull,
                    Instant lastUpdate, String lastUpdateUser);

    void updateParent(long id, long newParentId,
                      Instant lastUpdate, String lastUpdateUser);

    void updateName(long id, String newName,
                    Instant lastUpdate, String lastUpdateUser);

    List<Long> cascadeDeleteSubtree(long rootId);
}

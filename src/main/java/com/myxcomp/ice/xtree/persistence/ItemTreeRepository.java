package com.myxcomp.ice.xtree.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

public interface ItemTreeRepository {

    /** Streams all rows structural-only (no payload columns); caller's handler is invoked once per row; fetchSize=1000 for Oracle compatibility. */
    void streamAllStructural(Consumer<StructuralRow> rowHandler);

    /** Returns structural rows where LASTUPDATE > since (strict greater-than, second granularity on Oracle DATE). */
    List<StructuralRow> findStructuralChangedSince(Instant since);

    /** Returns payload rows for given ids; ids are chunked at 1000 per query (Oracle IN-list limit); unknown ids silently omitted; empty input returns empty list. */
    List<PayloadRow> findPayloadByIds(Collection<Long> ids);

    /** Silently writes json to rows where JSON IS NULL; does NOT touch LASTUPDATE or LASTUPDATEUSER; conditional WHERE makes concurrent calls idempotent; returns count of rows actually updated. */
    int backfillJsonWhereNull(Collection<JsonBackfillRow> rows);

    /** Inserts a new row using the sequence for id; jsonOrNull and xmlOrNull may be null; returns the generated id. */
    long insert(long parentId, String name, String type,
                String jsonOrNull, String xmlOrNull,
                Instant lastUpdate, String lastUpdateUser);

    /** Updates JSON and XML columns and stamps LASTUPDATE/LASTUPDATEUSER. */
    void updateJson(long id, String json, String xmlOrNull,
                    Instant lastUpdate, String lastUpdateUser);

    /** Moves the item to a new parent and stamps LASTUPDATE/LASTUPDATEUSER. */
    void updateParent(long id, long newParentId,
                      Instant lastUpdate, String lastUpdateUser);

    /** Renames the item and stamps LASTUPDATE/LASTUPDATEUSER. */
    void updateName(long id, String newName,
                    Instant lastUpdate, String lastUpdateUser);

    /** Deletes rootId and all descendants in a single transaction using BFS; returns the full set of deleted ids (including rootId); returns empty list if rootId does not exist. */
    List<Long> cascadeDeleteSubtree(long rootId);
}

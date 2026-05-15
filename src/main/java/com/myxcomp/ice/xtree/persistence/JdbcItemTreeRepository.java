package com.myxcomp.ice.xtree.persistence;

import com.myxcomp.ice.xtree.common.TimeMapper;
import com.myxcomp.ice.xtree.persistence.rowmapper.PayloadRowMapper;
import com.myxcomp.ice.xtree.persistence.rowmapper.StructuralRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

@Repository
public class JdbcItemTreeRepository implements ItemTreeRepository {

    private static final int CHUNK_SIZE = 1000;

    private final JdbcClient jdbcClient;
    private final JdbcTemplate jdbcTemplate;
    private final StructuralRowMapper structuralRowMapper;
    private final PayloadRowMapper payloadRowMapper;
    private final TimeMapper timeMapper;

    public JdbcItemTreeRepository(JdbcClient jdbcClient,
                                   JdbcTemplate jdbcTemplate,
                                   StructuralRowMapper structuralRowMapper,
                                   PayloadRowMapper payloadRowMapper,
                                   TimeMapper timeMapper) {
        this.jdbcClient = jdbcClient;
        this.jdbcTemplate = jdbcTemplate;
        this.structuralRowMapper = structuralRowMapper;
        this.payloadRowMapper = payloadRowMapper;
        this.timeMapper = timeMapper;
    }

    @Override
    public void streamAllStructural(Consumer<StructuralRow> rowHandler) {
        jdbcTemplate.query(
                conn -> {
                    java.sql.PreparedStatement ps = conn.prepareStatement(
                            "SELECT ITEMTREEID, PARENTID, NAME, TYPE, LASTUPDATE, LASTUPDATEUSER FROM ITEMTREE");
                    ps.setFetchSize(1000);
                    return ps;
                },
                (org.springframework.jdbc.core.RowCallbackHandler)
                        rs -> rowHandler.accept(structuralRowMapper.mapRow(rs, 0))
        );
    }

    @Override
    public List<StructuralRow> findStructuralChangedSince(Instant since) {
        return jdbcClient.sql("""
                        SELECT ITEMTREEID, PARENTID, NAME, TYPE, LASTUPDATE, LASTUPDATEUSER
                        FROM ITEMTREE
                        WHERE LASTUPDATE > :since
                        """)
                .param("since", timeMapper.toLocalDateTime(since))
                .query(structuralRowMapper)
                .list();
    }

    @Override
    public List<PayloadRow> findPayloadByIds(Collection<Long> ids) {
        if (ids.isEmpty()) return Collections.emptyList();
        List<PayloadRow> result = new ArrayList<>();
        for (List<Long> chunk : partition(ids)) {
            result.addAll(
                    jdbcClient.sql("SELECT ITEMTREEID, JSON, XML FROM ITEMTREE WHERE ITEMTREEID IN (:ids)")
                            .param("ids", chunk)
                            .query(payloadRowMapper)
                            .list()
            );
        }
        return result;
    }

    @Override
    public int backfillJsonWhereNull(Collection<JsonBackfillRow> rows) {
        int updated = 0;
        for (JsonBackfillRow row : rows) {
            updated += jdbcClient.sql(
                            "UPDATE ITEMTREE SET JSON = :json WHERE ITEMTREEID = :id AND JSON IS NULL")
                    .param("json", row.json())
                    .param("id", row.itemTreeId())
                    .update();
        }
        return updated;
    }

    @Override
    public long insert(long parentId, String name, String type,
                       String jsonOrNull, String xmlOrNull,
                       Instant lastUpdate, String lastUpdateUser) {
        long id = jdbcClient.sql("SELECT ITEMTREE_ID_SQN.NEXTVAL FROM DUAL")
                .query(Long.class)
                .single();

        jdbcClient.sql("""
                        INSERT INTO ITEMTREE
                          (ITEMTREEID, PARENTID, NAME, TYPE, JSON, XML, LASTUPDATE, LASTUPDATEUSER)
                        VALUES
                          (:id, :parentId, :name, :type, :json, :xml, :lastUpdate, :lastUpdateUser)
                        """)
                .param("id", id)
                .param("parentId", parentId)
                .param("name", name)
                .param("type", type)
                .param("json", jsonOrNull)
                .param("xml", xmlOrNull)
                .param("lastUpdate", timeMapper.toLocalDateTime(lastUpdate))
                .param("lastUpdateUser", lastUpdateUser)
                .update();

        return id;
    }

    @Override
    public void updateJson(long id, String json, String xmlOrNull,
                           Instant lastUpdate, String lastUpdateUser) {
        jdbcClient.sql("""
                        UPDATE ITEMTREE
                           SET JSON = :json, XML = :xml,
                               LASTUPDATE = :lastUpdate, LASTUPDATEUSER = :lastUpdateUser
                         WHERE ITEMTREEID = :id
                        """)
                .param("json", json)
                .param("xml", xmlOrNull)
                .param("lastUpdate", timeMapper.toLocalDateTime(lastUpdate))
                .param("lastUpdateUser", lastUpdateUser)
                .param("id", id)
                .update();
    }

    @Override
    public void updateParent(long id, long newParentId,
                             Instant lastUpdate, String lastUpdateUser) {
        jdbcClient.sql("""
                        UPDATE ITEMTREE
                           SET PARENTID = :newParentId,
                               LASTUPDATE = :lastUpdate, LASTUPDATEUSER = :lastUpdateUser
                         WHERE ITEMTREEID = :id
                        """)
                .param("newParentId", newParentId)
                .param("lastUpdate", timeMapper.toLocalDateTime(lastUpdate))
                .param("lastUpdateUser", lastUpdateUser)
                .param("id", id)
                .update();
    }

    @Override
    public void updateName(long id, String newName,
                           Instant lastUpdate, String lastUpdateUser) {
        jdbcClient.sql("""
                        UPDATE ITEMTREE
                           SET NAME = :newName,
                               LASTUPDATE = :lastUpdate, LASTUPDATEUSER = :lastUpdateUser
                         WHERE ITEMTREEID = :id
                        """)
                .param("newName", newName)
                .param("lastUpdate", timeMapper.toLocalDateTime(lastUpdate))
                .param("lastUpdateUser", lastUpdateUser)
                .param("id", id)
                .update();
    }

    @Override
    public List<Long> cascadeDeleteSubtree(long rootId) {
        // BFS instead of recursive CTE: Spring uses PreparedStatement, and H2 2.x
        // cannot resolve a recursive CTE self-reference at prepare time.
        Long count = jdbcClient.sql("SELECT COUNT(*) FROM ITEMTREE WHERE ITEMTREEID = :id")
                .param("id", rootId)
                .query(Long.class)
                .single();
        if (count == null || count == 0) return Collections.emptyList();

        List<Long> allIds = new ArrayList<>();
        List<Long> frontier = new ArrayList<>();
        frontier.add(rootId);

        while (!frontier.isEmpty()) {
            allIds.addAll(frontier);
            List<Long> next = new ArrayList<>();
            for (List<Long> chunk : partition(frontier)) {
                next.addAll(jdbcClient.sql(
                                "SELECT ITEMTREEID FROM ITEMTREE WHERE PARENTID IN (:ids)")
                        .param("ids", chunk)
                        .query(Long.class)
                        .list());
            }
            frontier = next;
        }

        for (List<Long> chunk : partition(allIds)) {
            jdbcClient.sql("DELETE FROM ITEMTREE WHERE ITEMTREEID IN (:ids)")
                    .param("ids", chunk)
                    .update();
        }

        return allIds;
    }

    private List<List<Long>> partition(Collection<Long> ids) {
        List<Long> list = new ArrayList<>(ids);
        List<List<Long>> chunks = new ArrayList<>();
        for (int i = 0; i < list.size(); i += CHUNK_SIZE) {
            chunks.add(list.subList(i, Math.min(i + CHUNK_SIZE, list.size())));
        }
        return chunks;
    }
}

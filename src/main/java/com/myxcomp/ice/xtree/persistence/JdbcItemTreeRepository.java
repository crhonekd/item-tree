package com.myxcomp.ice.xtree.persistence;

import com.myxcomp.ice.xtree.common.TimeMapper;
import com.myxcomp.ice.xtree.persistence.rowmapper.PayloadRowMapper;
import com.myxcomp.ice.xtree.persistence.rowmapper.StructuralRowMapper;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

@Repository
public class JdbcItemTreeRepository implements ItemTreeRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcItemTreeRepository.class);

    private static final int CHUNK_SIZE = 1000;
    private static final String PARAM_LAST_UPDATE = "lastUpdate";
    private static final String PARAM_LAST_UPDATE_USER = "lastUpdateUser";

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
                        rs -> rowHandler.accept(structuralRowMapper.mapRow(rs, 0)) // rowNum unused by this mapper
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
    @Transactional
    public int backfillJsonWhereNull(Collection<JsonBackfillRow> rows) {
        if (rows.isEmpty()) return 0;
        List<JsonBackfillRow> list = new ArrayList<>(rows);
        int[] counts = jdbcTemplate.batchUpdate(
            "UPDATE ITEMTREE SET JSON = ? WHERE ITEMTREEID = ? AND JSON IS NULL",
            new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    ps.setString(1, list.get(i).json());
                    ps.setLong(2, list.get(i).itemTreeId());
                }
                @Override
                public int getBatchSize() { return list.size(); }
            }
        );
        int total = 0;
        for (int c : counts) total += (c > 0 ? c : 0);
        return total;
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
                .param(PARAM_LAST_UPDATE, timeMapper.toLocalDateTime(lastUpdate))
                .param(PARAM_LAST_UPDATE_USER, lastUpdateUser)
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
                .param(PARAM_LAST_UPDATE, timeMapper.toLocalDateTime(lastUpdate))
                .param(PARAM_LAST_UPDATE_USER, lastUpdateUser)
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
                .param(PARAM_LAST_UPDATE, timeMapper.toLocalDateTime(lastUpdate))
                .param(PARAM_LAST_UPDATE_USER, lastUpdateUser)
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
                .param(PARAM_LAST_UPDATE, timeMapper.toLocalDateTime(lastUpdate))
                .param(PARAM_LAST_UPDATE_USER, lastUpdateUser)
                .param("id", id)
                .update();
    }

    @Override
    @Transactional
    public List<Long> cascadeDeleteSubtree(long rootId) {
        // BFS instead of recursive CTE: Spring uses PreparedStatement, and H2 2.x
        // cannot resolve a recursive CTE self-reference at prepare time.

        // seed check: skip if root doesn't exist
        List<Long> existingRoots = jdbcClient.sql(
                "SELECT ITEMTREEID FROM ITEMTREE WHERE ITEMTREEID = :id")
            .param("id", rootId)
            .query(Long.class)
            .list();
        if (existingRoots.isEmpty()) return Collections.emptyList();

        List<Long> allIds = new ArrayList<>();
        List<Long> frontier = List.of(rootId);

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

    @Override
    public boolean lastUpdateIndexExists() {
        DataSource ds = jdbcTemplate.getDataSource();
        if (ds == null) {
            log.warn("DataSource is null — cannot check LASTUPDATE index");
            return false;
        }
        try (Connection conn = ds.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getIndexInfo(null, null, "ITEMTREE", false, true)) {
                while (rs.next()) {
                    String column = rs.getString("COLUMN_NAME");
                    if ("LASTUPDATE".equalsIgnoreCase(column)) {
                        return true;
                    }
                }
            }
        } catch (SQLException e) {
            log.warn("Index-presence check failed: {}", e.getMessage());
        }
        return false;
    }

    private List<List<Long>> partition(Collection<Long> ids) {
        List<Long> list = new ArrayList<>(ids);
        List<List<Long>> chunks = new ArrayList<>();
        for (int i = 0; i < list.size(); i += CHUNK_SIZE) {
            chunks.add(new ArrayList<>(list.subList(i, Math.min(i + CHUNK_SIZE, list.size()))));
        }
        return chunks;
    }
}

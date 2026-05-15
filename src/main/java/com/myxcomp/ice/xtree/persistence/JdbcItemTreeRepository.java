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
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public int backfillJsonWhereNull(Collection<JsonBackfillRow> rows) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public long insert(long parentId, String name, String type,
                       String jsonOrNull, String xmlOrNull,
                       Instant lastUpdate, String lastUpdateUser) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public void updateJson(long id, String json, String xmlOrNull,
                           Instant lastUpdate, String lastUpdateUser) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public void updateParent(long id, long newParentId,
                             Instant lastUpdate, String lastUpdateUser) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public void updateName(long id, String newName,
                           Instant lastUpdate, String lastUpdateUser) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public List<Long> cascadeDeleteSubtree(long rootId) {
        throw new UnsupportedOperationException("not yet implemented");
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

package com.myxcomp.ice.xtree.persistence.rowmapper;

import com.myxcomp.ice.xtree.common.TimeMapper;
import com.myxcomp.ice.xtree.persistence.ItemTreeFullRow;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;

public class ItemTreeFullRowMapper implements RowMapper<ItemTreeFullRow> {

    private final TimeMapper timeMapper;

    public ItemTreeFullRowMapper(TimeMapper timeMapper) {
        this.timeMapper = timeMapper;
    }

    @Override
    public ItemTreeFullRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        long id          = rs.getLong("ITEMTREEID");
        Long parentId    = rs.getObject("PARENTID", Long.class);
        String name      = rs.getString("NAME");
        String type      = rs.getString("TYPE");
        String json      = rs.getString("JSON");
        String xml       = rs.getString("XML");
        LocalDateTime ts = rs.getObject("LASTUPDATE", LocalDateTime.class);
        String user      = rs.getString("LASTUPDATEUSER");
        return new ItemTreeFullRow(id, parentId, name, type, json, xml,
                timeMapper.toInstant(ts), user);
    }
}

package com.myxcomp.ice.xtree.persistence.rowmapper;

import com.myxcomp.ice.xtree.common.TimeMapper;
import com.myxcomp.ice.xtree.persistence.StructuralRow;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;

@Component
public class StructuralRowMapper implements RowMapper<StructuralRow> {

    private final TimeMapper timeMapper;

    public StructuralRowMapper(TimeMapper timeMapper) {
        this.timeMapper = timeMapper;
    }

    @Override
    public StructuralRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new StructuralRow(
                rs.getLong("ITEMTREEID"),
                rs.getLong("PARENTID"),
                rs.getString("NAME"),
                rs.getString("TYPE"),
                timeMapper.toInstant(rs.getObject("LASTUPDATE", LocalDateTime.class)),
                rs.getString("LASTUPDATEUSER")
        );
    }
}

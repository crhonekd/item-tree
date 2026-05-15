package com.myxcomp.ice.xtree.persistence.rowmapper;

import com.myxcomp.ice.xtree.persistence.PayloadRow;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;

@Component
public class PayloadRowMapper implements RowMapper<PayloadRow> {

    @Override
    public PayloadRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new PayloadRow(
                rs.getLong("ITEMTREEID"),
                rs.getString("JSON"),
                rs.getString("XML")
        );
    }
}

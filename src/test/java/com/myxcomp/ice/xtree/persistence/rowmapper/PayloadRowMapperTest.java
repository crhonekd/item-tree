package com.myxcomp.ice.xtree.persistence.rowmapper;

import com.myxcomp.ice.xtree.persistence.PayloadRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.ResultSet;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PayloadRowMapperTest {

    @Mock
    ResultSet rs;

    PayloadRowMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new PayloadRowMapper();
    }

    @Nested
    class HappyPath {

        @Test
        void mapsAllColumnsCorrectly() throws SQLException {
            when(rs.getLong("ITEMTREEID")).thenReturn(55L);
            when(rs.getString("JSON")).thenReturn("{\"key\":\"value\"}");
            when(rs.getString("XML")).thenReturn("<root><key>value</key></root>");

            PayloadRow row = mapper.mapRow(rs, 1);

            assertThat(row.itemTreeId()).isEqualTo(55L);
            assertThat(row.json()).isEqualTo("{\"key\":\"value\"}");
            assertThat(row.xml()).isEqualTo("<root><key>value</key></root>");
        }
    }

    @Nested
    class NullHandling {

        @Test
        void mapsNullPayloadColumns() throws SQLException {
            when(rs.getLong("ITEMTREEID")).thenReturn(10L);
            when(rs.getString("JSON")).thenReturn(null);
            when(rs.getString("XML")).thenReturn(null);

            PayloadRow row = mapper.mapRow(rs, 1);

            assertThat(row.itemTreeId()).isEqualTo(10L);
            assertThat(row.json()).isNull();
            assertThat(row.xml()).isNull();
        }
    }
}

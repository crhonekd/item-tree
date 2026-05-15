package com.myxcomp.ice.xtree.persistence.rowmapper;

import com.myxcomp.ice.xtree.common.TimeMapper;
import com.myxcomp.ice.xtree.persistence.StructuralRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StructuralRowMapperTest {

    @Mock
    ResultSet rs;

    TimeMapper timeMapper = new TimeMapper();
    StructuralRowMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new StructuralRowMapper(timeMapper);
    }

    @Nested
    class HappyPath {

        @Test
        void mapsAllColumnsCorrectly() throws SQLException {
            LocalDateTime lastUpdate = LocalDateTime.of(2026, 5, 1, 10, 0, 0);
            when(rs.getLong("ITEMTREEID")).thenReturn(42L);
            when(rs.getObject("PARENTID", Long.class)).thenReturn(7L);
            when(rs.getString("NAME")).thenReturn("MyFolder");
            when(rs.getString("TYPE")).thenReturn("Folder");
            when(rs.getObject("LASTUPDATE", LocalDateTime.class)).thenReturn(lastUpdate);
            when(rs.getString("LASTUPDATEUSER")).thenReturn("jsmith");

            StructuralRow row = mapper.mapRow(rs, 1);

            assertThat(row.itemTreeId()).isEqualTo(42L);
            assertThat(row.parentId()).isEqualTo(7L);
            assertThat(row.name()).isEqualTo("MyFolder");
            assertThat(row.type()).isEqualTo("Folder");
            assertThat(row.lastUpdate()).isEqualTo(Instant.parse("2026-05-01T10:00:00Z"));
            assertThat(row.lastUpdateUser()).isEqualTo("jsmith");
        }

        @Test
        void mapsRootParentId() throws SQLException {
            when(rs.getLong("ITEMTREEID")).thenReturn(1L);
            when(rs.getObject("PARENTID", Long.class)).thenReturn(0L);
            when(rs.getString("NAME")).thenReturn("Root");
            when(rs.getString("TYPE")).thenReturn("Folder");
            when(rs.getObject("LASTUPDATE", LocalDateTime.class)).thenReturn(LocalDateTime.of(2026, 1, 1, 0, 0, 0));
            when(rs.getString("LASTUPDATEUSER")).thenReturn("admin");

            StructuralRow row = mapper.mapRow(rs, 1);

            assertThat(row.itemTreeId()).isEqualTo(1L);
            assertThat(row.parentId()).isZero();
        }
    }

    @Nested
    class NullHandling {

        @Test
        void mapsNullLastUpdate() throws SQLException {
            when(rs.getLong("ITEMTREEID")).thenReturn(99L);
            when(rs.getObject("PARENTID", Long.class)).thenReturn(1L);
            when(rs.getString("NAME")).thenReturn("Item");
            when(rs.getString("TYPE")).thenReturn("Document");
            when(rs.getObject("LASTUPDATE", LocalDateTime.class)).thenReturn(null);
            when(rs.getString("LASTUPDATEUSER")).thenReturn(null);

            StructuralRow row = mapper.mapRow(rs, 1);

            assertThat(row.lastUpdate()).isNull();
            assertThat(row.lastUpdateUser()).isNull();
        }
    }
}

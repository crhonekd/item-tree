package com.myxcomp.ice.xtree.persistence;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dev")
@Transactional
class JdbcItemTreeRepositoryIT {

    @Autowired
    private JdbcItemTreeRepository repository;

    @Autowired
    private JdbcClient jdbcClient;

    @Nested
    class StreamAllStructural {

        @Test
        void returnsAllSeedRows() {
            List<StructuralRow> rows = new ArrayList<>();
            repository.streamAllStructural(rows::add);
            assertThat(rows).hasSize(33);
        }

        @Test
        void spotChecksRootRow() {
            List<StructuralRow> rows = new ArrayList<>();
            repository.streamAllStructural(rows::add);

            StructuralRow root = rows.stream()
                    .filter(r -> r.itemTreeId() == 1L)
                    .findFirst()
                    .orElseThrow();

            assertThat(root.parentId()).isEqualTo(0L);
            assertThat(root.name()).isEqualTo("root");
            assertThat(root.type()).isEqualTo("Folder");
            assertThat(root.lastUpdate()).isEqualTo(Instant.parse("2026-05-01T10:00:00Z"));
        }
    }

    @Nested
    class FindStructuralChangedSince {

        @Test
        void returnsRowsAfterGivenInstant() {
            List<StructuralRow> rows = repository.findStructuralChangedSince(
                    Instant.parse("2026-04-30T00:00:00Z"));
            assertThat(rows).hasSize(33);
        }

        @Test
        void excludesRowsAtExactTimestamp() {
            // WHERE LASTUPDATE > :since — exact match is excluded
            List<StructuralRow> rows = repository.findStructuralChangedSince(
                    Instant.parse("2026-05-01T10:00:00Z"));
            assertThat(rows).isEmpty();
        }

        @Test
        void returnsNothingWhenSinceIsAfterAllRows() {
            List<StructuralRow> rows = repository.findStructuralChangedSince(
                    Instant.parse("2026-05-02T00:00:00Z"));
            assertThat(rows).isEmpty();
        }
    }

    @Nested
    class FindPayloadByIds {

        @Test
        void happyPath() {
            List<PayloadRow> rows = repository.findPayloadByIds(List.of(25L, 40L, 41L));
            assertThat(rows).hasSize(3);
            PayloadRow leafItem = rows.stream()
                    .filter(r -> r.itemTreeId() == 25L)
                    .findFirst()
                    .orElseThrow();
            assertThat(leafItem.json()).contains("\"name\":\"leaf\"");
            assertThat(leafItem.xml()).contains("<report>");
        }

        @Test
        void emptyInputReturnsEmptyList() {
            List<PayloadRow> rows = repository.findPayloadByIds(List.of());
            assertThat(rows).isEmpty();
        }

        @Test
        void unknownIdsAreSilentlyOmitted() {
            List<PayloadRow> rows = repository.findPayloadByIds(List.of(25L, 999_999L));
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).itemTreeId()).isEqualTo(25L);
        }

        @Test
        void chunksLargeIdSets() {
            // Insert 1001 rows to force chunking (CHUNK_SIZE=1000)
            List<Long> insertedIds = new ArrayList<>();
            for (long i = 200_000L; i < 201_001L; i++) {
                jdbcClient.sql("""
                                INSERT INTO ITEMTREE
                                  (ITEMTREEID, PARENTID, NAME, TYPE, JSON, XML, LASTUPDATE, LASTUPDATEUSER)
                                VALUES (:id, 1, :name, 'View', :json, NULL,
                                        TIMESTAMP '2026-05-01 10:00:00', 'test')
                                """)
                        .param("id", i)
                        .param("name", "bulk" + i)
                        .param("json", "{\"n\":" + i + "}")
                        .update();
                insertedIds.add(i);
            }

            List<PayloadRow> rows = repository.findPayloadByIds(insertedIds);

            assertThat(rows).hasSize(1001);
        }
    }

    @Nested
    class Insert {

        @Test
        void returnsGeneratedId() {
            long id = repository.insert(1L, "NewReport", "Report",
                    "{\"x\":1}", null,
                    Instant.parse("2026-05-15T12:00:00Z"), "tester");

            assertThat(id).isGreaterThanOrEqualTo(100_000L);
        }

        @Test
        void rowPresentInDbWithCorrectValues() {
            Instant ts = Instant.parse("2026-05-15T12:00:00Z");
            long id = repository.insert(1L, "NewFolder", "Folder", null, null, ts, "admin");

            record Row(long itemTreeId, long parentId, String name, String type,
                       java.time.LocalDateTime lastUpdate, String lastUpdateUser, String json) {}

            Row row = jdbcClient.sql("""
                            SELECT ITEMTREEID, PARENTID, NAME, TYPE, LASTUPDATE, LASTUPDATEUSER, JSON
                            FROM ITEMTREE WHERE ITEMTREEID = :id
                            """)
                    .param("id", id)
                    .query((rs, n) -> new Row(
                            rs.getLong("ITEMTREEID"),
                            rs.getLong("PARENTID"),
                            rs.getString("NAME"),
                            rs.getString("TYPE"),
                            rs.getObject("LASTUPDATE", java.time.LocalDateTime.class),
                            rs.getString("LASTUPDATEUSER"),
                            rs.getString("JSON")))
                    .single();

            assertThat(row.parentId()).isEqualTo(1L);
            assertThat(row.name()).isEqualTo("NewFolder");
            assertThat(row.type()).isEqualTo("Folder");
            assertThat(row.lastUpdate()).isEqualTo(java.time.LocalDateTime.of(2026, 5, 15, 12, 0, 0));
            assertThat(row.lastUpdateUser()).isEqualTo("admin");
            assertThat(row.json()).isNull();
        }
    }
}

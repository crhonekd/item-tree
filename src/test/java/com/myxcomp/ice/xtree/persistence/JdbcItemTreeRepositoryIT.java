package com.myxcomp.ice.xtree.persistence;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

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

            assertThat(root.parentId()).isZero();
            assertThat(root.name()).isEqualTo("root");
            assertThat(root.type()).isEqualTo("Folder");
            assertThat(root.lastUpdate()).isEqualTo(Instant.parse("2026-05-01T10:00:00Z"));
        }

        @Test
        void returnsNothingFromEmptyTable() {
            jdbcClient.sql("DELETE FROM ITEMTREE").update();

            List<StructuralRow> rows = new ArrayList<>();
            repository.streamAllStructural(rows::add);

            assertThat(rows).isEmpty();
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

        @ParameterizedTest(name = "{0}")
        @MethodSource("sinceInstantsThatYieldNoRows")
        void returnsEmptyForSinceAtOrAfterData(String description, Instant since) {
            assertThat(repository.findStructuralChangedSince(since)).isEmpty();
        }

        static Stream<Arguments> sinceInstantsThatYieldNoRows() {
            return Stream.of(
                // WHERE LASTUPDATE > :since — exact match is excluded (not >=)
                Arguments.of("exact stored timestamp", Instant.parse("2026-05-01T10:00:00Z")),
                // since is past all data
                Arguments.of("after all data", Instant.parse("2026-05-02T00:00:00Z")),
                // Oracle DATE has 1-second precision; sub-second past stored second is not > stored second
                Arguments.of("sub-second above stored second", Instant.parse("2026-05-01T10:00:00.001Z"))
            );
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

        @Test
        void roundTripsLargeClob() {
            String largeJson = "{\"data\":\"" + "x".repeat(8192) + "\"}";
            String largeXml  = "<root><data>" + "y".repeat(8192) + "</data></root>";
            jdbcClient.sql("""
                    INSERT INTO ITEMTREE
                      (ITEMTREEID, PARENTID, NAME, TYPE, JSON, XML, LASTUPDATE, LASTUPDATEUSER)
                    VALUES (:id, 1, 'ClobTest', 'Report', :json, :xml,
                            TIMESTAMP '2026-05-15 10:00:00', 'test')
                    """)
                .param("id", 300_001L)
                .param("json", largeJson)
                .param("xml", largeXml)
                .update();

            List<PayloadRow> rows = repository.findPayloadByIds(List.of(300_001L));

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).json()).isEqualTo(largeJson);
            assertThat(rows.get(0).xml()).isEqualTo(largeXml);
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

    @Nested
    class UpdateJson {

        @Test
        void updatesJsonAndXmlColumns() {
            repository.updateJson(41L, "{\"name\":\"updated\",\"n\":2}",
                    "<report><name>updated</name></report>",
                    Instant.parse("2026-05-15T14:00:00Z"), "editor");

            record Row(String json, String xml, String name) {}
            Row row = jdbcClient.sql(
                            "SELECT JSON, XML, NAME FROM ITEMTREE WHERE ITEMTREEID = 41")
                    .query((rs, n) -> new Row(
                            rs.getString("JSON"), rs.getString("XML"), rs.getString("NAME")))
                    .single();

            assertThat(row.json()).isEqualTo("{\"name\":\"updated\",\"n\":2}");
            assertThat(row.xml()).isEqualTo("<report><name>updated</name></report>");
            assertThat(row.name()).isEqualTo("Report1"); // NAME must not change
        }

        @Test
        void stampsLastUpdateAndUser() {
            repository.updateJson(41L, "{\"name\":\"updated\",\"n\":2}", null,
                    Instant.parse("2026-05-15T14:00:00Z"), "editor");

            record Row(java.time.LocalDateTime lastUpdate, String lastUpdateUser) {}
            Row row = jdbcClient.sql(
                            "SELECT LASTUPDATE, LASTUPDATEUSER FROM ITEMTREE WHERE ITEMTREEID = 41")
                    .query((rs, n) -> new Row(
                            rs.getObject("LASTUPDATE", java.time.LocalDateTime.class),
                            rs.getString("LASTUPDATEUSER")))
                    .single();

            assertThat(row.lastUpdate()).isEqualTo(java.time.LocalDateTime.of(2026, 5, 15, 14, 0, 0));
            assertThat(row.lastUpdateUser()).isEqualTo("editor");
        }
    }

    @Nested
    class UpdateParent {

        @Test
        void changesParentId() {
            repository.updateParent(41L, 6L,
                    Instant.parse("2026-05-15T14:00:00Z"), "mover");

            long parentId = jdbcClient.sql(
                            "SELECT PARENTID FROM ITEMTREE WHERE ITEMTREEID = 41")
                    .query((rs, n) -> rs.getLong("PARENTID"))
                    .single();

            assertThat(parentId).isEqualTo(6L);
        }

        @Test
        void stampsLastUpdateAndUser() {
            repository.updateParent(41L, 6L,
                    Instant.parse("2026-05-15T14:00:00Z"), "mover");

            record Row(java.time.LocalDateTime lastUpdate, String lastUpdateUser) {}
            Row row = jdbcClient.sql(
                            "SELECT LASTUPDATE, LASTUPDATEUSER FROM ITEMTREE WHERE ITEMTREEID = 41")
                    .query((rs, n) -> new Row(
                            rs.getObject("LASTUPDATE", java.time.LocalDateTime.class),
                            rs.getString("LASTUPDATEUSER")))
                    .single();

            assertThat(row.lastUpdate()).isEqualTo(java.time.LocalDateTime.of(2026, 5, 15, 14, 0, 0));
            assertThat(row.lastUpdateUser()).isEqualTo("mover");
        }
    }

    @Nested
    class UpdateName {

        @Test
        void changesName() {
            repository.updateName(41L, "RenamedReport",
                    Instant.parse("2026-05-15T14:00:00Z"), "renamer");

            String name = jdbcClient.sql(
                            "SELECT NAME FROM ITEMTREE WHERE ITEMTREEID = 41")
                    .query((rs, n) -> rs.getString("NAME"))
                    .single();

            assertThat(name).isEqualTo("RenamedReport");
        }

        @Test
        void stampsLastUpdateAndUser() {
            repository.updateName(41L, "RenamedReport",
                    Instant.parse("2026-05-15T14:00:00Z"), "renamer");

            record Row(java.time.LocalDateTime lastUpdate, String lastUpdateUser) {}
            Row row = jdbcClient.sql(
                            "SELECT LASTUPDATE, LASTUPDATEUSER FROM ITEMTREE WHERE ITEMTREEID = 41")
                    .query((rs, n) -> new Row(
                            rs.getObject("LASTUPDATE", java.time.LocalDateTime.class),
                            rs.getString("LASTUPDATEUSER")))
                    .single();

            assertThat(row.lastUpdate()).isEqualTo(java.time.LocalDateTime.of(2026, 5, 15, 14, 0, 0));
            assertThat(row.lastUpdateUser()).isEqualTo("renamer");
        }
    }

    @Nested
    class CascadeDeleteSubtree {

        @Test
        void returnsLeafNodeIdForSingleNodeSubtree() {
            // testuser1 (id=10) has no children in seed data
            List<Long> ids = repository.cascadeDeleteSubtree(10L);
            assertThat(ids).containsExactlyInAnyOrder(10L);
        }

        @Test
        void returnsAllDescendantsAndDeletesThem() {
            // deepuser (id=12) subtree: 12, 20, 21, 22, 23, 24, 25
            List<Long> ids = repository.cascadeDeleteSubtree(12L);

            assertThat(ids).containsExactlyInAnyOrder(12L, 20L, 21L, 22L, 23L, 24L, 25L);

            long remaining = jdbcClient.sql(
                            "SELECT COUNT(*) FROM ITEMTREE WHERE ITEMTREEID IN (12, 20, 21, 22, 23, 24, 25)")
                    .query(Long.class)
                    .single();
            assertThat(remaining).isZero();
        }

        @Test
        void returnsEmptyListForNonExistentId() {
            List<Long> ids = repository.cascadeDeleteSubtree(999_999L);
            assertThat(ids).isEmpty();
        }

        @Test
        void deletesEntireTreeFromRoot() {
            List<Long> ids = repository.cascadeDeleteSubtree(1L);

            assertThat(ids).hasSize(33); // all seed rows

            long remaining = jdbcClient.sql("SELECT COUNT(*) FROM ITEMTREE")
                    .query(Long.class).single();
            assertThat(remaining).isZero();
        }
    }

    @Nested
    class BackfillJsonWhereNull {

        @Test
        void returnsZeroWhenJsonAlreadyPresent() {
            // id=41 (Report1) already has JSON populated
            int updated = repository.backfillJsonWhereNull(
                    List.of(new JsonBackfillRow(41L, "{\"name\":\"new\",\"n\":99}")));
            assertThat(updated).isZero();
        }

        @Test
        void updatesRowsWhereJsonIsNull() {
            // id=60 (BackfillReport) has JSON=NULL
            int updated = repository.backfillJsonWhereNull(
                    List.of(new JsonBackfillRow(60L, "{\"name\":\"backfill-me\",\"n\":42}")));

            assertThat(updated).isEqualTo(1);

            String json = jdbcClient.sql(
                            "SELECT JSON FROM ITEMTREE WHERE ITEMTREEID = 60")
                    .query((rs, n) -> rs.getString("JSON"))
                    .single();
            assertThat(json).isEqualTo("{\"name\":\"backfill-me\",\"n\":42}");
        }

        @Test
        void doesNotTouchLastUpdate() {
            java.time.LocalDateTime before = jdbcClient.sql(
                            "SELECT LASTUPDATE FROM ITEMTREE WHERE ITEMTREEID = 60")
                    .query((rs, n) -> rs.getObject("LASTUPDATE", java.time.LocalDateTime.class))
                    .single();

            repository.backfillJsonWhereNull(
                    List.of(new JsonBackfillRow(60L, "{\"name\":\"backfill-me\",\"n\":42}")));

            java.time.LocalDateTime after = jdbcClient.sql(
                            "SELECT LASTUPDATE FROM ITEMTREE WHERE ITEMTREEID = 60")
                    .query((rs, n) -> rs.getObject("LASTUPDATE", java.time.LocalDateTime.class))
                    .single();

            assertThat(after).isEqualTo(before);
        }

        @Test
        void emptyInputReturnsZero() {
            int updated = repository.backfillJsonWhereNull(List.of());
            assertThat(updated).isZero();
        }

        @Test
        void updatesOnlyNullJsonRowsInBatch() {
            // id=60 has JSON=NULL; id=41 already has JSON
            int updated = repository.backfillJsonWhereNull(List.of(
                    new JsonBackfillRow(60L, "{\"name\":\"batch\",\"n\":1}"),
                    new JsonBackfillRow(41L, "{\"name\":\"ignored\",\"n\":99}")
            ));
            assertThat(updated).isEqualTo(1);

            String json60 = jdbcClient.sql("SELECT JSON FROM ITEMTREE WHERE ITEMTREEID = 60")
                    .query((rs, n) -> rs.getString("JSON")).single();
            assertThat(json60).isEqualTo("{\"name\":\"batch\",\"n\":1}");

            // id=41 must be unchanged
            String json41 = jdbcClient.sql("SELECT JSON FROM ITEMTREE WHERE ITEMTREEID = 41")
                    .query((rs, n) -> rs.getString("JSON")).single();
            assertThat(json41).isEqualTo("{\"name\":\"r1\",\"n\":1}");
        }

        @Test
        void allNullRowsBatchReturnsCorrectCount() {
            // insert two rows with JSON=NULL
            jdbcClient.sql("""
                    INSERT INTO ITEMTREE (ITEMTREEID, PARENTID, NAME, TYPE, XML, LASTUPDATEUSER, LASTUPDATE, JSON)
                    VALUES (:id, 1, :name, 'Report', NULL, 'system', TIMESTAMP '2026-05-15 10:00:00', NULL)
                    """)
                .param("id", 300_010L).param("name", "Batch1").update();
            jdbcClient.sql("""
                    INSERT INTO ITEMTREE (ITEMTREEID, PARENTID, NAME, TYPE, XML, LASTUPDATEUSER, LASTUPDATE, JSON)
                    VALUES (:id, 1, :name, 'Report', NULL, 'system', TIMESTAMP '2026-05-15 10:00:00', NULL)
                    """)
                .param("id", 300_011L).param("name", "Batch2").update();

            int updated = repository.backfillJsonWhereNull(List.of(
                    new JsonBackfillRow(300_010L, "{\"n\":10}"),
                    new JsonBackfillRow(300_011L, "{\"n\":11}")
            ));
            assertThat(updated).isEqualTo(2);
        }
    }
}

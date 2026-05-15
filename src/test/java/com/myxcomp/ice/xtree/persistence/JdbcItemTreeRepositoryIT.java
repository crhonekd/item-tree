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
}

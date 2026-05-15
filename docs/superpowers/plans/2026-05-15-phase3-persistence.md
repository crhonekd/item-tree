# Phase 3 — Persistence Layer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement `ItemTreeRepository` and `JdbcItemTreeRepository` against H2 (Oracle compat mode), fully verified by `JdbcItemTreeRepositoryIT`.

**Architecture:** `JdbcClient` is used for all standard queries and updates. `JdbcTemplate` is injected alongside it specifically for `streamAllStructural`, which requires a `RowCallbackHandler` with `fetchSize=1000`. `IN`-list chunking at 1000 is used for `findPayloadByIds` and the DELETE step in `cascadeDeleteSubtree`. `INSERT` uses a portable two-step: `SELECT ITEMTREE_ID_SQN.NEXTVAL FROM DUAL` then `INSERT` (avoids Oracle-only `RETURNING INTO`). `@Transactional` is managed at the service layer, not the repository.

**Tech Stack:** Java 21, Spring Boot 3.4.1, JdbcClient, JdbcTemplate, H2 (Oracle compat mode), JUnit 5, AssertJ, Mockito.

---

## File Structure

| File | Action | Purpose |
|------|--------|---------|
| `src/main/java/com/myxcomp/ice/xtree/persistence/rowmapper/StructuralRowMapper.java` | Create | Maps ITEMTREE structural columns → `StructuralRow`; uses `TimeMapper` |
| `src/main/java/com/myxcomp/ice/xtree/persistence/rowmapper/PayloadRowMapper.java` | Create | Maps ITEMTREEID/JSON/XML → `PayloadRow` |
| `src/test/java/com/myxcomp/ice/xtree/persistence/rowmapper/StructuralRowMapperTest.java` | Create | Unit tests for StructuralRowMapper (mocked ResultSet) |
| `src/test/java/com/myxcomp/ice/xtree/persistence/rowmapper/PayloadRowMapperTest.java` | Create | Unit tests for PayloadRowMapper (mocked ResultSet) |
| `src/main/java/com/myxcomp/ice/xtree/persistence/ItemTreeRepository.java` | Create | Repository interface (exact signature from design §12) |
| `src/main/java/com/myxcomp/ice/xtree/persistence/JdbcItemTreeRepository.java` | Create | `@Repository` implementation; all nine methods |
| `src/test/java/com/myxcomp/ice/xtree/persistence/JdbcItemTreeRepositoryIT.java` | Create | `@SpringBootTest` IT against H2; `@Transactional` for isolation; `@Nested` per method group |

No existing files are modified.

---

## Seed data reference (data.sql — 33 rows total)

Key rows referenced in tests:

| id | parentId | name | type | JSON | XML |
|----|----------|------|------|------|-----|
| 1 | 0 | root | Folder | NULL | NULL |
| 12 | 2 | deepuser | Folder | NULL | NULL |
| 20–24 | chain | L2…L6 | Folder | NULL | NULL |
| 25 | 24 | leafItem | Report | `{"name":"leaf","n":1}` | `<report>…` |
| 41 | 3 | Report1 | Report | `{"name":"r1","n":1}` | `<report>…` |
| 60 | 3 | BackfillReport | Report | **NULL** | `<report>…` |

All `LASTUPDATE` values in seed data are `2026-05-01T10:00:00Z`.
Seed has exactly **33 rows**: root(1) + first-level folders(5) + home folders(3) + depth chain(6) + shortcuts(4) + xml+json types(7) + json-only(3) + backfill candidate(1) + mixed folder(3).

---

## Task 1: Row Mappers

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/persistence/rowmapper/StructuralRowMapper.java`
- Create: `src/main/java/com/myxcomp/ice/xtree/persistence/rowmapper/PayloadRowMapper.java`
- Create: `src/test/java/com/myxcomp/ice/xtree/persistence/rowmapper/StructuralRowMapperTest.java`
- Create: `src/test/java/com/myxcomp/ice/xtree/persistence/rowmapper/PayloadRowMapperTest.java`

- [ ] **Step 1: Write failing tests**

`src/test/java/com/myxcomp/ice/xtree/persistence/rowmapper/StructuralRowMapperTest.java`:
```java
package com.myxcomp.ice.xtree.persistence.rowmapper;

import com.myxcomp.ice.xtree.common.TimeMapper;
import com.myxcomp.ice.xtree.persistence.StructuralRow;
import org.junit.jupiter.api.BeforeEach;
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
    private ResultSet rs;

    private final TimeMapper timeMapper = new TimeMapper();
    private StructuralRowMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new StructuralRowMapper(timeMapper);
    }

    @Test
    void mapsAllColumnsCorrectly() throws SQLException {
        LocalDateTime ldt = LocalDateTime.of(2026, 5, 1, 10, 0, 0);
        when(rs.getLong("ITEMTREEID")).thenReturn(42L);
        when(rs.getLong("PARENTID")).thenReturn(1L);
        when(rs.getString("NAME")).thenReturn("Reports");
        when(rs.getString("TYPE")).thenReturn("Folder");
        when(rs.getObject("LASTUPDATE", LocalDateTime.class)).thenReturn(ldt);
        when(rs.getString("LASTUPDATEUSER")).thenReturn("system");

        StructuralRow row = mapper.mapRow(rs, 1);

        assertThat(row.itemTreeId()).isEqualTo(42L);
        assertThat(row.parentId()).isEqualTo(1L);
        assertThat(row.name()).isEqualTo("Reports");
        assertThat(row.type()).isEqualTo("Folder");
        assertThat(row.lastUpdate()).isEqualTo(Instant.parse("2026-05-01T10:00:00Z"));
        assertThat(row.lastUpdateUser()).isEqualTo("system");
    }

    @Test
    void mapsNullLastUpdate() throws SQLException {
        when(rs.getLong("ITEMTREEID")).thenReturn(1L);
        when(rs.getLong("PARENTID")).thenReturn(0L);
        when(rs.getString("NAME")).thenReturn("root");
        when(rs.getString("TYPE")).thenReturn("Folder");
        when(rs.getObject("LASTUPDATE", LocalDateTime.class)).thenReturn(null);
        when(rs.getString("LASTUPDATEUSER")).thenReturn(null);

        StructuralRow row = mapper.mapRow(rs, 1);

        assertThat(row.lastUpdate()).isNull();
        assertThat(row.lastUpdateUser()).isNull();
    }

    @Test
    void mapsRootParentId() throws SQLException {
        when(rs.getLong("ITEMTREEID")).thenReturn(1L);
        when(rs.getLong("PARENTID")).thenReturn(0L);
        when(rs.getString("NAME")).thenReturn("root");
        when(rs.getString("TYPE")).thenReturn("Folder");
        when(rs.getObject("LASTUPDATE", LocalDateTime.class)).thenReturn(null);
        when(rs.getString("LASTUPDATEUSER")).thenReturn(null);

        StructuralRow row = mapper.mapRow(rs, 1);

        assertThat(row.parentId()).isEqualTo(0L);
    }
}
```

`src/test/java/com/myxcomp/ice/xtree/persistence/rowmapper/PayloadRowMapperTest.java`:
```java
package com.myxcomp.ice.xtree.persistence.rowmapper;

import com.myxcomp.ice.xtree.persistence.PayloadRow;
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
    private ResultSet rs;

    private final PayloadRowMapper mapper = new PayloadRowMapper();

    @Test
    void mapsAllColumnsCorrectly() throws SQLException {
        when(rs.getLong("ITEMTREEID")).thenReturn(25L);
        when(rs.getString("JSON")).thenReturn("{\"name\":\"leaf\",\"n\":1}");
        when(rs.getString("XML")).thenReturn("<report><name>leaf</name></report>");

        PayloadRow row = mapper.mapRow(rs, 1);

        assertThat(row.itemTreeId()).isEqualTo(25L);
        assertThat(row.json()).isEqualTo("{\"name\":\"leaf\",\"n\":1}");
        assertThat(row.xml()).isEqualTo("<report><name>leaf</name></report>");
    }

    @Test
    void mapsNullPayloadColumns() throws SQLException {
        when(rs.getLong("ITEMTREEID")).thenReturn(30L);
        when(rs.getString("JSON")).thenReturn(null);
        when(rs.getString("XML")).thenReturn(null);

        PayloadRow row = mapper.mapRow(rs, 1);

        assertThat(row.json()).isNull();
        assertThat(row.xml()).isNull();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew test --tests "com.myxcomp.ice.xtree.persistence.rowmapper.*"
```
Expected: FAIL — `StructuralRowMapper` and `PayloadRowMapper` classes do not exist yet.

- [ ] **Step 3: Implement StructuralRowMapper**

`src/main/java/com/myxcomp/ice/xtree/persistence/rowmapper/StructuralRowMapper.java`:
```java
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
```

- [ ] **Step 4: Implement PayloadRowMapper**

`src/main/java/com/myxcomp/ice/xtree/persistence/rowmapper/PayloadRowMapper.java`:
```java
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
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
./gradlew test --tests "com.myxcomp.ice.xtree.persistence.rowmapper.*"
```
Expected: `StructuralRowMapperTest` (3 tests) + `PayloadRowMapperTest` (2 tests) → PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/persistence/rowmapper/StructuralRowMapper.java \
        src/main/java/com/myxcomp/ice/xtree/persistence/rowmapper/PayloadRowMapper.java \
        src/test/java/com/myxcomp/ice/xtree/persistence/rowmapper/StructuralRowMapperTest.java \
        src/test/java/com/myxcomp/ice/xtree/persistence/rowmapper/PayloadRowMapperTest.java
git commit -m "feat(phase3): add StructuralRowMapper and PayloadRowMapper with unit tests"
```

---

## Task 2: ItemTreeRepository Interface + JdbcItemTreeRepository Skeleton + IT Shell

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/persistence/ItemTreeRepository.java`
- Create: `src/main/java/com/myxcomp/ice/xtree/persistence/JdbcItemTreeRepository.java`
- Create: `src/test/java/com/myxcomp/ice/xtree/persistence/JdbcItemTreeRepositoryIT.java`

- [ ] **Step 1: Create the repository interface**

`src/main/java/com/myxcomp/ice/xtree/persistence/ItemTreeRepository.java`:
```java
package com.myxcomp.ice.xtree.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

public interface ItemTreeRepository {

    void streamAllStructural(Consumer<StructuralRow> rowHandler);

    List<StructuralRow> findStructuralChangedSince(Instant since);

    List<PayloadRow> findPayloadByIds(Collection<Long> ids);

    int backfillJsonWhereNull(Collection<JsonBackfillRow> rows);

    long insert(long parentId, String name, String type,
                String jsonOrNull, String xmlOrNull,
                Instant lastUpdate, String lastUpdateUser);

    void updateJson(long id, String json, String xmlOrNull,
                    Instant lastUpdate, String lastUpdateUser);

    void updateParent(long id, long newParentId,
                      Instant lastUpdate, String lastUpdateUser);

    void updateName(long id, String newName,
                    Instant lastUpdate, String lastUpdateUser);

    List<Long> cascadeDeleteSubtree(long rootId);
}
```

- [ ] **Step 2: Create the repository implementation skeleton**

`src/main/java/com/myxcomp/ice/xtree/persistence/JdbcItemTreeRepository.java`:
```java
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
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public List<StructuralRow> findStructuralChangedSince(Instant since) {
        throw new UnsupportedOperationException("not yet implemented");
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
```

- [ ] **Step 3: Create the IT class shell**

`src/test/java/com/myxcomp/ice/xtree/persistence/JdbcItemTreeRepositoryIT.java`:
```java
package com.myxcomp.ice.xtree.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dev")
@Transactional
class JdbcItemTreeRepositoryIT {

    @Autowired
    private JdbcItemTreeRepository repository;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void contextLoads() {
        assertThat(repository).isNotNull();
    }
}
```

- [ ] **Step 4: Verify the shell compiles and context loads**

```bash
./gradlew test --tests "com.myxcomp.ice.xtree.persistence.JdbcItemTreeRepositoryIT.contextLoads"
```
Expected: PASS — Spring context loads, repository bean wires up.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/persistence/ItemTreeRepository.java \
        src/main/java/com/myxcomp/ice/xtree/persistence/JdbcItemTreeRepository.java \
        src/test/java/com/myxcomp/ice/xtree/persistence/JdbcItemTreeRepositoryIT.java
git commit -m "feat(phase3): add ItemTreeRepository interface and JdbcItemTreeRepository skeleton"
```

---

## Task 3: Structural Reads — streamAllStructural + findStructuralChangedSince

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/persistence/JdbcItemTreeRepository.java`
- Modify: `src/test/java/com/myxcomp/ice/xtree/persistence/JdbcItemTreeRepositoryIT.java`

- [ ] **Step 1: Add failing IT tests**

Add these `@Nested` classes to `JdbcItemTreeRepositoryIT` (replace the `contextLoads` test with proper nested structure):

```java
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
            // All seed rows have LASTUPDATE = 2026-05-01T10:00:00Z
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
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew test --tests "com.myxcomp.ice.xtree.persistence.JdbcItemTreeRepositoryIT"
```
Expected: FAIL — `UnsupportedOperationException` from both methods.

- [ ] **Step 3: Implement streamAllStructural**

Replace the `streamAllStructural` method body in `JdbcItemTreeRepository.java`:
```java
@Override
public void streamAllStructural(Consumer<StructuralRow> rowHandler) {
    jdbcTemplate.query(
            conn -> {
                java.sql.PreparedStatement ps = conn.prepareStatement(
                        "SELECT ITEMTREEID, PARENTID, NAME, TYPE, LASTUPDATE, LASTUPDATEUSER FROM ITEMTREE");
                ps.setFetchSize(1000);
                return ps;
            },
            rs -> rowHandler.accept(structuralRowMapper.mapRow(rs, 0))
    );
}
```

Also add the import at the top of the file: `import java.sql.PreparedStatement;` (or keep the fully-qualified name in the lambda as shown above — either works).

- [ ] **Step 4: Implement findStructuralChangedSince**

Replace the `findStructuralChangedSince` method body:
```java
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
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
./gradlew test --tests "com.myxcomp.ice.xtree.persistence.JdbcItemTreeRepositoryIT"
```
Expected: `StreamAllStructural` (2 tests) + `FindStructuralChangedSince` (3 tests) PASS; remaining nested classes not yet written.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/persistence/JdbcItemTreeRepository.java \
        src/test/java/com/myxcomp/ice/xtree/persistence/JdbcItemTreeRepositoryIT.java
git commit -m "feat(phase3): implement streamAllStructural and findStructuralChangedSince"
```

---

## Task 4: Payload Reads — findPayloadByIds

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/persistence/JdbcItemTreeRepository.java`
- Modify: `src/test/java/com/myxcomp/ice/xtree/persistence/JdbcItemTreeRepositoryIT.java`

- [ ] **Step 1: Add failing IT tests**

Add the following `@Nested` class to `JdbcItemTreeRepositoryIT`:
```java
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
        // Insert 1001 rows explicitly (bypasses the sequence) to prove chunking works
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
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew test --tests "com.myxcomp.ice.xtree.persistence.JdbcItemTreeRepositoryIT#FindPayloadByIds*"
```
Expected: FAIL — `UnsupportedOperationException`.

- [ ] **Step 3: Implement findPayloadByIds**

Replace the `findPayloadByIds` method body:
```java
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
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew test --tests "com.myxcomp.ice.xtree.persistence.JdbcItemTreeRepositoryIT"
```
Expected: `FindPayloadByIds` (4 tests) PASS; all prior nested classes still pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/persistence/JdbcItemTreeRepository.java \
        src/test/java/com/myxcomp/ice/xtree/persistence/JdbcItemTreeRepositoryIT.java
git commit -m "feat(phase3): implement findPayloadByIds with IN-list chunking at 1000"
```

---

## Task 5: Insert — Portable Two-Step Sequence

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/persistence/JdbcItemTreeRepository.java`
- Modify: `src/test/java/com/myxcomp/ice/xtree/persistence/JdbcItemTreeRepositoryIT.java`

- [ ] **Step 1: Add failing IT tests**

Add the following `@Nested` class to `JdbcItemTreeRepositoryIT`:
```java
@Nested
class Insert {

    @Test
    void returnsGeneratedId() {
        long id = repository.insert(1L, "NewReport", "Report",
                "{\"x\":1}", null,
                Instant.parse("2026-05-15T12:00:00Z"), "tester");

        assertThat(id).isGreaterThanOrEqualTo(100_000L); // sequence starts at 100000
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
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew test --tests "com.myxcomp.ice.xtree.persistence.JdbcItemTreeRepositoryIT#Insert*"
```
Expected: FAIL — `UnsupportedOperationException`.

- [ ] **Step 3: Implement insert**

Replace the `insert` method body:
```java
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
            .param("lastUpdate", timeMapper.toLocalDateTime(lastUpdate))
            .param("lastUpdateUser", lastUpdateUser)
            .update();

    return id;
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew test --tests "com.myxcomp.ice.xtree.persistence.JdbcItemTreeRepositoryIT"
```
Expected: `Insert` (2 tests) PASS; all prior tests still pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/persistence/JdbcItemTreeRepository.java \
        src/test/java/com/myxcomp/ice/xtree/persistence/JdbcItemTreeRepositoryIT.java
git commit -m "feat(phase3): implement insert using portable two-step sequence pattern"
```

---

## Task 6: Update Methods — updateJson, updateParent, updateName

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/persistence/JdbcItemTreeRepository.java`
- Modify: `src/test/java/com/myxcomp/ice/xtree/persistence/JdbcItemTreeRepositoryIT.java`

- [ ] **Step 1: Add failing IT tests**

Add the following three `@Nested` classes to `JdbcItemTreeRepositoryIT`:
```java
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
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew test --tests "com.myxcomp.ice.xtree.persistence.JdbcItemTreeRepositoryIT"
```
Expected: `UpdateJson`, `UpdateParent`, `UpdateName` tests FAIL with `UnsupportedOperationException`.

- [ ] **Step 3: Implement updateJson, updateParent, updateName**

Replace the three method bodies in `JdbcItemTreeRepository`:

```java
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
            .param("lastUpdate", timeMapper.toLocalDateTime(lastUpdate))
            .param("lastUpdateUser", lastUpdateUser)
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
            .param("lastUpdate", timeMapper.toLocalDateTime(lastUpdate))
            .param("lastUpdateUser", lastUpdateUser)
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
            .param("lastUpdate", timeMapper.toLocalDateTime(lastUpdate))
            .param("lastUpdateUser", lastUpdateUser)
            .param("id", id)
            .update();
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew test --tests "com.myxcomp.ice.xtree.persistence.JdbcItemTreeRepositoryIT"
```
Expected: `UpdateJson` (2), `UpdateParent` (2), `UpdateName` (2) PASS; all prior tests still pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/persistence/JdbcItemTreeRepository.java \
        src/test/java/com/myxcomp/ice/xtree/persistence/JdbcItemTreeRepositoryIT.java
git commit -m "feat(phase3): implement updateJson, updateParent, updateName"
```

---

## Task 7: Cascade Delete — cascadeDeleteSubtree

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/persistence/JdbcItemTreeRepository.java`
- Modify: `src/test/java/com/myxcomp/ice/xtree/persistence/JdbcItemTreeRepositoryIT.java`

- [ ] **Step 1: Add failing IT tests**

Add the following `@Nested` class to `JdbcItemTreeRepositoryIT`:
```java
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
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew test --tests "com.myxcomp.ice.xtree.persistence.JdbcItemTreeRepositoryIT#CascadeDeleteSubtree*"
```
Expected: FAIL — `UnsupportedOperationException`.

- [ ] **Step 3: Implement cascadeDeleteSubtree**

Replace the `cascadeDeleteSubtree` method body:
```java
@Override
public List<Long> cascadeDeleteSubtree(long rootId) {
    List<Long> ids = jdbcClient.sql("""
                    WITH sub(id) AS (
                        SELECT ITEMTREEID FROM ITEMTREE WHERE ITEMTREEID = :rootId
                        UNION ALL
                        SELECT t.ITEMTREEID FROM ITEMTREE t JOIN sub ON t.PARENTID = sub.id
                    )
                    SELECT id FROM sub
                    """)
            .param("rootId", rootId)
            .query(Long.class)
            .list();

    if (!ids.isEmpty()) {
        for (List<Long> chunk : partition(ids)) {
            jdbcClient.sql("DELETE FROM ITEMTREE WHERE ITEMTREEID IN (:ids)")
                    .param("ids", chunk)
                    .update();
        }
    }

    return ids;
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew test --tests "com.myxcomp.ice.xtree.persistence.JdbcItemTreeRepositoryIT"
```
Expected: `CascadeDeleteSubtree` (3 tests) PASS; all prior tests still pass.

Note: The recursive CTE `WITH sub(id) AS (... UNION ALL ...)` is verified to work in both H2 (Oracle compat mode) and Oracle 19c — H2 Oracle mode allows recursive CTEs without the `RECURSIVE` keyword.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/persistence/JdbcItemTreeRepository.java \
        src/test/java/com/myxcomp/ice/xtree/persistence/JdbcItemTreeRepositoryIT.java
git commit -m "feat(phase3): implement cascadeDeleteSubtree with recursive CTE and chunked DELETE"
```

---

## Task 8: Backfill — backfillJsonWhereNull

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/persistence/JdbcItemTreeRepository.java`
- Modify: `src/test/java/com/myxcomp/ice/xtree/persistence/JdbcItemTreeRepositoryIT.java`

- [ ] **Step 1: Add failing IT tests**

Add the following `@Nested` class to `JdbcItemTreeRepositoryIT`:
```java
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
        // id=60 (BackfillReport) has JSON=NULL in seed data
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
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew test --tests "com.myxcomp.ice.xtree.persistence.JdbcItemTreeRepositoryIT#BackfillJsonWhereNull*"
```
Expected: FAIL — `UnsupportedOperationException`.

- [ ] **Step 3: Implement backfillJsonWhereNull**

Replace the `backfillJsonWhereNull` method body:
```java
@Override
public int backfillJsonWhereNull(Collection<JsonBackfillRow> rows) {
    int updated = 0;
    for (JsonBackfillRow row : rows) {
        updated += jdbcClient.sql(
                        "UPDATE ITEMTREE SET JSON = :json WHERE ITEMTREEID = :id AND JSON IS NULL")
                .param("json", row.json())
                .param("id", row.itemTreeId())
                .update();
    }
    return updated;
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew test --tests "com.myxcomp.ice.xtree.persistence.JdbcItemTreeRepositoryIT"
```
Expected: `BackfillJsonWhereNull` (3 tests) PASS; all prior tests still pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/persistence/JdbcItemTreeRepository.java \
        src/test/java/com/myxcomp/ice/xtree/persistence/JdbcItemTreeRepositoryIT.java
git commit -m "feat(phase3): implement backfillJsonWhereNull — conditional update, no LASTUPDATE touch"
```

---

## Task 9: Final Build Verification

- [ ] **Step 1: Run the full build**

```bash
./gradlew clean build
```
Expected output ends with `BUILD SUCCESSFUL`. All prior test suites pass:
- `StructuralRowMapperTest` (3)
- `PayloadRowMapperTest` (2)
- `TimeMapperTest` (6)
- `InstanceIdProviderTest` (3)
- `TreeMutationEventTest` (9)
- `TypesTest`, `UserContextTest` (any)
- `ItemTreeApplicationTests` (3)
- `ApiContractTest`
- `JdbcItemTreeRepositoryIT` (23 tests across 8 nested classes)

- [ ] **Step 2: Confirm IT test count**

```bash
./gradlew test --tests "com.myxcomp.ice.xtree.persistence.JdbcItemTreeRepositoryIT" --info 2>&1 | grep "tests were run\|Test.*PASSED\|Test.*FAILED" | tail -5
```
Expected: 23 tests passed, 0 failed.

- [ ] **Step 3: Tag Phase 3 complete**

```bash
git tag phase-3-persistence
```

---

## Self-Review Checklist

**Spec coverage (design §12):**
- `streamAllStructural` ✓ — RowCallbackHandler with fetchSize=1000
- `findStructuralChangedSince` ✓ — WHERE LASTUPDATE > :since, TimeMapper at boundary
- `findPayloadByIds` ✓ — chunked at 1000, empty + unknown covered
- `insert` ✓ — portable two-step sequence (no RETURNING INTO)
- `updateJson` ✓ — stamps LASTUPDATE/LASTUPDATEUSER
- `updateParent` ✓ — stamps LASTUPDATE/LASTUPDATEUSER
- `updateName` ✓ — stamps LASTUPDATE/LASTUPDATEUSER
- `cascadeDeleteSubtree` ✓ — CTE + chunked DELETE, returns all ids
- `backfillJsonWhereNull` ✓ — conditional (AND JSON IS NULL), no LASTUPDATE touch

**Design §14 (time handling):**
- All `Instant` → `LocalDateTime` conversions go through `TimeMapper` ✓
- No bare `LocalDateTime.now()` calls ✓
- Delta-refresh `:since` parameter passed as UTC `LocalDateTime` ✓

**CLAUDE.md invariants:**
- `parentId` is `Long`, mapped via `rs.getLong()` (autoboxed, fine since NOT NULL in schema) ✓
- Sequence `ITEMTREE_ID_SQN.NEXTVAL FROM DUAL` — portable H2/Oracle ✓
- No `@Transactional` on the repository (service layer owns transactions) ✓

**Placeholder scan:** No TBDs, no "similar to Task N" references, all code blocks complete ✓

**Type consistency:** `StructuralRow`, `PayloadRow`, `JsonBackfillRow` signatures match Phase 2 records throughout ✓

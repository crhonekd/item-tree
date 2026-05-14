# Phase 2 — Domain Types and Common Primitives

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement all immutable value types, common utilities, cache records, persistence row types, and the Jackson-polymorphic messaging event envelope — with `TimeMapper` as the sole timezone conversion boundary.

**Architecture:** Pure value objects (Java records for data carriers, one final utility class per concern). `TreeMutationEvent` uses Lombok `@Value @Builder @Jacksonized` to support reliable Jackson polymorphic deserialization via `EXTERNAL_PROPERTY` on the `EventPayload` interface. No Spring beans except `TimeMapper` and `InstanceIdProvider`.

**Tech Stack:** Java 21 records, Lombok (`@Value`/`@Builder`/`@Jacksonized`), Jackson 2.18 (via Spring Boot 3.4.1), JUnit 5 + AssertJ, Mockito (not needed here — no collaborators to mock).

---

## File Map

| File | Action | Responsibility |
|---|---|---|
| `src/main/java/com/myxcomp/ice/xtree/common/TreeConstants.java` | Create | ROOT_ID, ROOT_PARENT_ID constants |
| `src/main/java/com/myxcomp/ice/xtree/common/Types.java` | Create | FOLDER literal + isFolder() helper |
| `src/main/java/com/myxcomp/ice/xtree/common/TimeMapper.java` | Create | Instant ↔ UTC LocalDateTime; only class allowed to call bare conversion methods |
| `src/main/java/com/myxcomp/ice/xtree/common/InstanceIdProvider.java` | Create | UUID generated once per JVM, stable across calls |
| `src/main/java/com/myxcomp/ice/xtree/common/UserContext.java` | Create | Plain record: iceUser + impersonatedUser; effectiveUser() resolution |
| `src/main/java/com/myxcomp/ice/xtree/cache/CachedNode.java` | Create | Structural cache node record (no payload columns) |
| `src/main/java/com/myxcomp/ice/xtree/cache/TreeSnapshot.java` | Create | Full snapshot record: three maps, swapped atomically on full reload |
| `src/main/java/com/myxcomp/ice/xtree/persistence/StructuralRow.java` | Create | JDBC row type for structural reads (no XML/JSON) |
| `src/main/java/com/myxcomp/ice/xtree/persistence/PayloadRow.java` | Create | JDBC row type for payload reads (JSON + XML columns) |
| `src/main/java/com/myxcomp/ice/xtree/persistence/JsonBackfillRow.java` | Create | Pair (itemTreeId, convertedJson) used for backfill writes |
| `src/main/java/com/myxcomp/ice/xtree/messaging/event/OperationType.java` | Create | Enum: CREATE UPDATE MOVE RENAME DELETE |
| `src/main/java/com/myxcomp/ice/xtree/messaging/event/payload/EventPayload.java` | Create | Marker interface with @JsonTypeInfo EXTERNAL_PROPERTY + @JsonSubTypes |
| `src/main/java/com/myxcomp/ice/xtree/messaging/event/payload/CreatePayload.java` | Create | Record: itemTreeId, parentId, name, type, lastUpdate, lastUpdateUser |
| `src/main/java/com/myxcomp/ice/xtree/messaging/event/payload/UpdatePayload.java` | Create | Record: itemTreeId, lastUpdate, lastUpdateUser |
| `src/main/java/com/myxcomp/ice/xtree/messaging/event/payload/MovePayload.java` | Create | Record: itemTreeId, oldParentId, newParentId, lastUpdate, lastUpdateUser |
| `src/main/java/com/myxcomp/ice/xtree/messaging/event/payload/RenamePayload.java` | Create | Record: itemTreeId, newName, lastUpdate, lastUpdateUser |
| `src/main/java/com/myxcomp/ice/xtree/messaging/event/payload/DeletePayload.java` | Create | Record: deletedIds (List<Long>) |
| `src/main/java/com/myxcomp/ice/xtree/messaging/event/TreeMutationEvent.java` | Create | Envelope: all header fields + OperationType + EventPayload (polymorphic) |
| `src/test/java/com/myxcomp/ice/xtree/common/TimeMapperTest.java` | Create | Round-trip + JVM-timezone independence |
| `src/test/java/com/myxcomp/ice/xtree/common/InstanceIdProviderTest.java` | Create | Stability + UUID format + uniqueness across provider instances |
| `src/test/java/com/myxcomp/ice/xtree/messaging/event/TreeMutationEventTest.java` | Create | Serialize + deserialize round-trip for all 5 operation types |

---

## Task 1: TreeConstants and Types

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/common/TreeConstants.java`
- Create: `src/main/java/com/myxcomp/ice/xtree/common/Types.java`

- [ ] **Step 1: Create TreeConstants**

```java
package com.myxcomp.ice.xtree.common;

public final class TreeConstants {

    public static final long ROOT_ID = 1L;
    public static final long ROOT_PARENT_ID = 0L;

    private TreeConstants() {}
}
```

- [ ] **Step 2: Create Types**

```java
package com.myxcomp.ice.xtree.common;

public final class Types {

    public static final String FOLDER = "Folder";

    public static boolean isFolder(String type) {
        return FOLDER.equals(type);
    }

    private Types() {}
}
```

- [ ] **Step 3: Verify compilation**

```bash
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/common/TreeConstants.java \
        src/main/java/com/myxcomp/ice/xtree/common/Types.java
git commit -m "feat(phase2): add TreeConstants and Types utility classes"
```

---

## Task 2: TimeMapper (TDD)

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/common/TimeMapper.java`
- Test: `src/test/java/com/myxcomp/ice/xtree/common/TimeMapperTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.myxcomp.ice.xtree.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

class TimeMapperTest {

    private TimeMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new TimeMapper();
    }

    @Test
    void toLocalDateTime_converts_utc_instant_to_local_datetime() {
        Instant instant = Instant.parse("2026-05-13T14:30:00Z");
        LocalDateTime result = mapper.toLocalDateTime(instant);
        assertThat(result).isEqualTo(LocalDateTime.of(2026, 5, 13, 14, 30, 0));
    }

    @Test
    void toInstant_treats_local_datetime_as_utc() {
        LocalDateTime ldt = LocalDateTime.of(2026, 5, 13, 14, 30, 0);
        Instant result = mapper.toInstant(ldt);
        assertThat(result).isEqualTo(Instant.parse("2026-05-13T14:30:00Z"));
    }

    @Test
    void roundTrip_instant_to_localDateTime_and_back() {
        Instant original = Instant.parse("2026-05-13T14:30:00.123456789Z");
        assertThat(mapper.toInstant(mapper.toLocalDateTime(original))).isEqualTo(original);
    }

    @Test
    void toLocalDateTime_is_utc_regardless_of_jvm_timezone() {
        TimeZone saved = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York")); // UTC-5
            Instant instant = Instant.parse("2026-05-13T14:30:00Z");
            assertThat(mapper.toLocalDateTime(instant))
                    .isEqualTo(LocalDateTime.of(2026, 5, 13, 14, 30, 0));
        } finally {
            TimeZone.setDefault(saved);
        }
    }

    @Test
    void toInstant_interprets_as_utc_regardless_of_jvm_timezone() {
        TimeZone saved = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo")); // UTC+9
            LocalDateTime ldt = LocalDateTime.of(2026, 5, 13, 14, 30, 0);
            assertThat(mapper.toInstant(ldt))
                    .isEqualTo(Instant.parse("2026-05-13T14:30:00Z"));
        } finally {
            TimeZone.setDefault(saved);
        }
    }

    @Test
    void toLocalDateTime_handles_epoch() {
        assertThat(mapper.toLocalDateTime(Instant.EPOCH))
                .isEqualTo(LocalDateTime.of(1970, 1, 1, 0, 0, 0));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew test --tests "com.myxcomp.ice.xtree.common.TimeMapperTest"
```

Expected: FAIL — `TimeMapper` class not found.

- [ ] **Step 3: Create TimeMapper**

```java
package com.myxcomp.ice.xtree.common;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Component
public class TimeMapper {

    public LocalDateTime toLocalDateTime(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC).toLocalDateTime();
    }

    public Instant toInstant(LocalDateTime localDateTime) {
        return localDateTime.toInstant(ZoneOffset.UTC);
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew test --tests "com.myxcomp.ice.xtree.common.TimeMapperTest"
```

Expected: 6 tests, all PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/common/TimeMapper.java \
        src/test/java/com/myxcomp/ice/xtree/common/TimeMapperTest.java
git commit -m "feat(phase2): add TimeMapper with UTC-only Instant<>LocalDateTime conversion"
```

---

## Task 3: InstanceIdProvider (TDD)

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/common/InstanceIdProvider.java`
- Test: `src/test/java/com/myxcomp/ice/xtree/common/InstanceIdProviderTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.myxcomp.ice.xtree.common;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class InstanceIdProviderTest {

    @Test
    void instanceId_is_stable_across_repeated_calls() {
        InstanceIdProvider provider = new InstanceIdProvider();
        String first = provider.getInstanceId();
        String second = provider.getInstanceId();
        assertThat(first).isEqualTo(second);
    }

    @Test
    void instanceId_is_a_valid_uuid() {
        InstanceIdProvider provider = new InstanceIdProvider();
        assertThatCode(() -> UUID.fromString(provider.getInstanceId()))
                .doesNotThrowAnyException();
    }

    @Test
    void two_separate_providers_have_different_ids() {
        // Models two JVM instances each creating their own InstanceIdProvider
        InstanceIdProvider p1 = new InstanceIdProvider();
        InstanceIdProvider p2 = new InstanceIdProvider();
        assertThat(p1.getInstanceId()).isNotEqualTo(p2.getInstanceId());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew test --tests "com.myxcomp.ice.xtree.common.InstanceIdProviderTest"
```

Expected: FAIL — `InstanceIdProvider` class not found.

- [ ] **Step 3: Create InstanceIdProvider**

```java
package com.myxcomp.ice.xtree.common;

import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class InstanceIdProvider {

    private final String instanceId = UUID.randomUUID().toString();

    public String getInstanceId() {
        return instanceId;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew test --tests "com.myxcomp.ice.xtree.common.InstanceIdProviderTest"
```

Expected: 3 tests, all PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/common/InstanceIdProvider.java \
        src/test/java/com/myxcomp/ice/xtree/common/InstanceIdProviderTest.java
git commit -m "feat(phase2): add InstanceIdProvider generating stable UUID per JVM instance"
```

---

## Task 4: UserContext

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/common/UserContext.java`

- [ ] **Step 1: Create UserContext**

```java
package com.myxcomp.ice.xtree.common;

public record UserContext(String iceUser, String impersonatedUser) {

    /**
     * Returns the impersonated user if set, otherwise the authenticated user.
     * Used for lastUpdateUser stamping and home-folder resolution.
     */
    public String effectiveUser() {
        return impersonatedUser != null ? impersonatedUser : iceUser;
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/common/UserContext.java
git commit -m "feat(phase2): add UserContext record with effectiveUser() resolution"
```

---

## Task 5: CachedNode and TreeSnapshot

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/cache/CachedNode.java`
- Create: `src/main/java/com/myxcomp/ice/xtree/cache/TreeSnapshot.java`

- [ ] **Step 1: Create CachedNode**

```java
package com.myxcomp.ice.xtree.cache;

import java.time.Instant;

public record CachedNode(
        long itemTreeId,
        long parentId,
        String name,
        String type,
        Instant lastUpdate,
        String lastUpdateUser
) {}
```

Note: `parentId` is `long` (primitive), never null. `0L` represents the root's parent per `TreeConstants.ROOT_PARENT_ID`.

- [ ] **Step 2: Create TreeSnapshot**

```java
package com.myxcomp.ice.xtree.cache;

import java.util.Map;
import java.util.Set;

public record TreeSnapshot(
        Map<Long, CachedNode> byId,
        Map<Long, Set<Long>> childrenByParent,
        Map<String, Set<Long>> foldersByName
) {}
```

This is the unit swapped atomically by `DefaultTreeCache.replaceAll()` during full reload.

- [ ] **Step 3: Verify compilation**

```bash
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/cache/CachedNode.java \
        src/main/java/com/myxcomp/ice/xtree/cache/TreeSnapshot.java
git commit -m "feat(phase2): add CachedNode and TreeSnapshot records"
```

---

## Task 6: Persistence row records

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/persistence/StructuralRow.java`
- Create: `src/main/java/com/myxcomp/ice/xtree/persistence/PayloadRow.java`
- Create: `src/main/java/com/myxcomp/ice/xtree/persistence/JsonBackfillRow.java`

- [ ] **Step 1: Create StructuralRow**

```java
package com.myxcomp.ice.xtree.persistence;

import java.time.Instant;

public record StructuralRow(
        long itemTreeId,
        long parentId,
        String name,
        String type,
        Instant lastUpdate,
        String lastUpdateUser
) {}
```

Used by `streamAllStructural` (bootstrap + full reload) and `findStructuralChangedSince` (delta refresh). Does not include XML or JSON columns.

- [ ] **Step 2: Create PayloadRow**

```java
package com.myxcomp.ice.xtree.persistence;

public record PayloadRow(
        long itemTreeId,
        String json,
        String xml
) {}
```

Used by `findPayloadByIds`. Both `json` and `xml` may be `null`.

- [ ] **Step 3: Create JsonBackfillRow**

```java
package com.myxcomp.ice.xtree.persistence;

public record JsonBackfillRow(long itemTreeId, String json) {}
```

Used by `backfillJsonWhereNull`. Carries the converted JSON to write back to the DB asynchronously (silent — no LASTUPDATE touch).

- [ ] **Step 4: Verify compilation**

```bash
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/persistence/StructuralRow.java \
        src/main/java/com/myxcomp/ice/xtree/persistence/PayloadRow.java \
        src/main/java/com/myxcomp/ice/xtree/persistence/JsonBackfillRow.java
git commit -m "feat(phase2): add persistence row records (StructuralRow, PayloadRow, JsonBackfillRow)"
```

---

## Task 7: OperationType enum

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/messaging/event/OperationType.java`

- [ ] **Step 1: Create OperationType**

```java
package com.myxcomp.ice.xtree.messaging.event;

public enum OperationType {
    CREATE, UPDATE, MOVE, RENAME, DELETE
}
```

Jackson serializes this as its name string (`"CREATE"`, etc.) by default, which matches the event envelope spec in design §6.

- [ ] **Step 2: Verify compilation**

```bash
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/messaging/event/OperationType.java
git commit -m "feat(phase2): add OperationType enum (CREATE UPDATE MOVE RENAME DELETE)"
```

---

## Task 8: EventPayload interface + 5 payload records

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/messaging/event/payload/EventPayload.java`
- Create: `src/main/java/com/myxcomp/ice/xtree/messaging/event/payload/CreatePayload.java`
- Create: `src/main/java/com/myxcomp/ice/xtree/messaging/event/payload/UpdatePayload.java`
- Create: `src/main/java/com/myxcomp/ice/xtree/messaging/event/payload/MovePayload.java`
- Create: `src/main/java/com/myxcomp/ice/xtree/messaging/event/payload/RenamePayload.java`
- Create: `src/main/java/com/myxcomp/ice/xtree/messaging/event/payload/DeletePayload.java`

- [ ] **Step 1: Create EventPayload interface**

```java
package com.myxcomp.ice.xtree.messaging.event.payload;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXTERNAL_PROPERTY,
        property = "operationType"
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = CreatePayload.class, name = "CREATE"),
        @JsonSubTypes.Type(value = UpdatePayload.class, name = "UPDATE"),
        @JsonSubTypes.Type(value = MovePayload.class, name = "MOVE"),
        @JsonSubTypes.Type(value = RenamePayload.class, name = "RENAME"),
        @JsonSubTypes.Type(value = DeletePayload.class, name = "DELETE")
})
public interface EventPayload {}
```

`EXTERNAL_PROPERTY` tells Jackson that the discriminator field (`operationType`) lives in the parent JSON object (i.e., the `TreeMutationEvent` object), not inside the `payload` object itself. No extra type field is added to the serialized payload JSON.

- [ ] **Step 2: Create CreatePayload**

```java
package com.myxcomp.ice.xtree.messaging.event.payload;

import java.time.Instant;

public record CreatePayload(
        long itemTreeId,
        long parentId,
        String name,
        String type,
        Instant lastUpdate,
        String lastUpdateUser
) implements EventPayload {}
```

- [ ] **Step 3: Create UpdatePayload**

```java
package com.myxcomp.ice.xtree.messaging.event.payload;

import java.time.Instant;

public record UpdatePayload(
        long itemTreeId,
        Instant lastUpdate,
        String lastUpdateUser
) implements EventPayload {}
```

Metadata-only update. JSON payload is never broadcast (see design §6).

- [ ] **Step 4: Create MovePayload**

```java
package com.myxcomp.ice.xtree.messaging.event.payload;

import java.time.Instant;

public record MovePayload(
        long itemTreeId,
        long oldParentId,
        long newParentId,
        Instant lastUpdate,
        String lastUpdateUser
) implements EventPayload {}
```

- [ ] **Step 5: Create RenamePayload**

```java
package com.myxcomp.ice.xtree.messaging.event.payload;

import java.time.Instant;

public record RenamePayload(
        long itemTreeId,
        String newName,
        Instant lastUpdate,
        String lastUpdateUser
) implements EventPayload {}
```

- [ ] **Step 6: Create DeletePayload**

```java
package com.myxcomp.ice.xtree.messaging.event.payload;

import java.util.List;

public record DeletePayload(List<Long> deletedIds) implements EventPayload {}
```

`deletedIds` includes the root of the deleted subtree plus all descendants (from the cascade delete CTE result).

- [ ] **Step 7: Verify compilation**

```bash
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/messaging/event/payload/
git commit -m "feat(phase2): add EventPayload interface and 5 operation payload records"
```

---

## Task 9: TreeMutationEvent envelope

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/messaging/event/TreeMutationEvent.java`

- [ ] **Step 1: Create TreeMutationEvent**

```java
package com.myxcomp.ice.xtree.messaging.event;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.myxcomp.ice.xtree.messaging.event.payload.EventPayload;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.time.Instant;

@Value
@Builder
@Jacksonized
public class TreeMutationEvent {

    String eventId;
    String instanceId;
    long sequence;
    Instant occurredAt;
    String iceUser;
    String impersonatedUser;
    OperationType operationType;
    EventPayload payload;
}
```

`@Jacksonized` instructs Lombok to add `@JsonDeserialize(builder = TreeMutationEvent.TreeMutationEventBuilder.class)` and `@JsonPOJOBuilder(withPrefix = "")` on the generated builder, making the class fully Jackson-serializable without hand-written `@JsonCreator`. Jackson uses the builder for deserialization.

The `EventPayload` field is typed as the interface, so Jackson uses the `@JsonTypeInfo`/`@JsonSubTypes` from the interface declaration. `EXTERNAL_PROPERTY` resolves the discriminator from the `operationType` sibling field in the enclosing JSON object.

- [ ] **Step 2: Verify compilation**

```bash
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/messaging/event/TreeMutationEvent.java
git commit -m "feat(phase2): add TreeMutationEvent envelope with polymorphic Jackson payload"
```

---

## Task 10: TreeMutationEventTest — round-trip for all 5 op types

**Files:**
- Test: `src/test/java/com/myxcomp/ice/xtree/messaging/event/TreeMutationEventTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.myxcomp.ice.xtree.messaging.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.myxcomp.ice.xtree.messaging.event.payload.CreatePayload;
import com.myxcomp.ice.xtree.messaging.event.payload.DeletePayload;
import com.myxcomp.ice.xtree.messaging.event.payload.EventPayload;
import com.myxcomp.ice.xtree.messaging.event.payload.MovePayload;
import com.myxcomp.ice.xtree.messaging.event.payload.RenamePayload;
import com.myxcomp.ice.xtree.messaging.event.payload.UpdatePayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TreeMutationEventTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private TreeMutationEvent buildEvent(OperationType opType, EventPayload payload) {
        return TreeMutationEvent.builder()
                .eventId("evt-uuid-1")
                .instanceId("inst-uuid-1")
                .sequence(42L)
                .occurredAt(Instant.parse("2026-05-13T14:30:00Z"))
                .iceUser("alice")
                .impersonatedUser(null)
                .operationType(opType)
                .payload(payload)
                .build();
    }

    private TreeMutationEvent roundTrip(TreeMutationEvent event) throws Exception {
        String json = mapper.writeValueAsString(event);
        return mapper.readValue(json, TreeMutationEvent.class);
    }

    @Nested
    class CreateRoundTrip {
        @Test
        void payload_is_deserialized_as_CreatePayload() throws Exception {
            var payload = new CreatePayload(100L, 1L, "NewReport", "Report",
                    Instant.parse("2026-05-13T14:30:00Z"), "alice");
            var event = buildEvent(OperationType.CREATE, payload);

            TreeMutationEvent restored = roundTrip(event);

            assertThat(restored.getOperationType()).isEqualTo(OperationType.CREATE);
            assertThat(restored.getPayload()).isInstanceOf(CreatePayload.class);
            CreatePayload rp = (CreatePayload) restored.getPayload();
            assertThat(rp.itemTreeId()).isEqualTo(100L);
            assertThat(rp.parentId()).isEqualTo(1L);
            assertThat(rp.name()).isEqualTo("NewReport");
            assertThat(rp.type()).isEqualTo("Report");
            assertThat(rp.lastUpdate()).isEqualTo(Instant.parse("2026-05-13T14:30:00Z"));
            assertThat(rp.lastUpdateUser()).isEqualTo("alice");
        }

        @Test
        void envelope_fields_survive_round_trip() throws Exception {
            var payload = new CreatePayload(100L, 1L, "X", "Folder",
                    Instant.parse("2026-05-13T14:30:00Z"), "alice");
            var event = buildEvent(OperationType.CREATE, payload);

            TreeMutationEvent restored = roundTrip(event);

            assertThat(restored.getEventId()).isEqualTo("evt-uuid-1");
            assertThat(restored.getInstanceId()).isEqualTo("inst-uuid-1");
            assertThat(restored.getSequence()).isEqualTo(42L);
            assertThat(restored.getOccurredAt()).isEqualTo(Instant.parse("2026-05-13T14:30:00Z"));
            assertThat(restored.getIceUser()).isEqualTo("alice");
            assertThat(restored.getImpersonatedUser()).isNull();
        }
    }

    @Nested
    class UpdateRoundTrip {
        @Test
        void payload_is_deserialized_as_UpdatePayload() throws Exception {
            var payload = new UpdatePayload(100L, Instant.parse("2026-05-13T15:00:00Z"), "bob");
            TreeMutationEvent restored = roundTrip(buildEvent(OperationType.UPDATE, payload));

            assertThat(restored.getOperationType()).isEqualTo(OperationType.UPDATE);
            assertThat(restored.getPayload()).isInstanceOf(UpdatePayload.class);
            UpdatePayload rp = (UpdatePayload) restored.getPayload();
            assertThat(rp.itemTreeId()).isEqualTo(100L);
            assertThat(rp.lastUpdate()).isEqualTo(Instant.parse("2026-05-13T15:00:00Z"));
            assertThat(rp.lastUpdateUser()).isEqualTo("bob");
        }
    }

    @Nested
    class MoveRoundTrip {
        @Test
        void payload_is_deserialized_as_MovePayload() throws Exception {
            var payload = new MovePayload(100L, 1L, 5L,
                    Instant.parse("2026-05-13T15:00:00Z"), "carol");
            TreeMutationEvent restored = roundTrip(buildEvent(OperationType.MOVE, payload));

            assertThat(restored.getOperationType()).isEqualTo(OperationType.MOVE);
            assertThat(restored.getPayload()).isInstanceOf(MovePayload.class);
            MovePayload rp = (MovePayload) restored.getPayload();
            assertThat(rp.itemTreeId()).isEqualTo(100L);
            assertThat(rp.oldParentId()).isEqualTo(1L);
            assertThat(rp.newParentId()).isEqualTo(5L);
            assertThat(rp.lastUpdateUser()).isEqualTo("carol");
        }
    }

    @Nested
    class RenameRoundTrip {
        @Test
        void payload_is_deserialized_as_RenamePayload() throws Exception {
            var payload = new RenamePayload(100L, "RenamedItem",
                    Instant.parse("2026-05-13T15:00:00Z"), "dave");
            TreeMutationEvent restored = roundTrip(buildEvent(OperationType.RENAME, payload));

            assertThat(restored.getOperationType()).isEqualTo(OperationType.RENAME);
            assertThat(restored.getPayload()).isInstanceOf(RenamePayload.class);
            RenamePayload rp = (RenamePayload) restored.getPayload();
            assertThat(rp.itemTreeId()).isEqualTo(100L);
            assertThat(rp.newName()).isEqualTo("RenamedItem");
            assertThat(rp.lastUpdateUser()).isEqualTo("dave");
        }
    }

    @Nested
    class DeleteRoundTrip {
        @Test
        void payload_is_deserialized_as_DeletePayload() throws Exception {
            var payload = new DeletePayload(List.of(100L, 101L, 102L));
            TreeMutationEvent restored = roundTrip(buildEvent(OperationType.DELETE, payload));

            assertThat(restored.getOperationType()).isEqualTo(OperationType.DELETE);
            assertThat(restored.getPayload()).isInstanceOf(DeletePayload.class);
            DeletePayload rp = (DeletePayload) restored.getPayload();
            assertThat(rp.deletedIds()).containsExactly(100L, 101L, 102L);
        }

        @Test
        void delete_payload_with_single_id() throws Exception {
            var payload = new DeletePayload(List.of(999L));
            TreeMutationEvent restored = roundTrip(buildEvent(OperationType.DELETE, payload));

            assertThat(((DeletePayload) restored.getPayload()).deletedIds()).containsExactly(999L);
        }
    }

    @Test
    void instant_is_serialized_as_iso8601_with_z_suffix() throws Exception {
        var payload = new UpdatePayload(1L, Instant.parse("2026-05-13T14:30:00Z"), "alice");
        String json = mapper.writeValueAsString(buildEvent(OperationType.UPDATE, payload));

        assertThat(json).contains("2026-05-13T14:30:00Z");
    }

    @Test
    void impersonatedUser_null_round_trips() throws Exception {
        var payload = new UpdatePayload(1L, Instant.parse("2026-05-13T14:30:00Z"), "alice");
        TreeMutationEvent event = TreeMutationEvent.builder()
                .eventId("e")
                .instanceId("i")
                .sequence(1L)
                .occurredAt(Instant.parse("2026-05-13T14:30:00Z"))
                .iceUser("alice")
                .impersonatedUser(null)
                .operationType(OperationType.UPDATE)
                .payload(payload)
                .build();

        TreeMutationEvent restored = roundTrip(event);
        assertThat(restored.getImpersonatedUser()).isNull();
    }
}
```

- [ ] **Step 2: Run all tests to verify they pass**

By Task 10, all types from Tasks 7–9 are already implemented. The test should compile and pass immediately if the Jackson polymorphism is wired correctly.

```bash
./gradlew test --tests "com.myxcomp.ice.xtree.messaging.event.TreeMutationEventTest"
```

Expected: 9 tests, all PASS.

If any test fails with a Jackson `InvalidDefinitionException` or wrong payload type, refer to the Troubleshooting section at the bottom of this plan.

If any test fails with a Jackson `InvalidDefinitionException` about the `EXTERNAL_PROPERTY` deserialization, see the Troubleshooting note at the bottom of this plan.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/myxcomp/ice/xtree/messaging/event/TreeMutationEventTest.java
git commit -m "test(phase2): add TreeMutationEventTest verifying Jackson round-trip for all 5 op types"
```

---

## Task 11: Full build verification

- [ ] **Step 1: Run the full test suite**

```bash
./gradlew clean build
```

Expected: `BUILD SUCCESSFUL` with all tests green. Tests that should pass:
- `ItemTreeApplicationTests` (3 tests — from Phase 0)
- `ApiContractTest` (1 test — from Phase 1)
- `TimeMapperTest` (6 tests)
- `InstanceIdProviderTest` (3 tests)
- `TreeMutationEventTest` (9 tests)

- [ ] **Step 2: Confirm no regressions**

If any prior test fails, diagnose and fix. Do not disable tests.

- [ ] **Step 3: Update IMPLEMENTATION_NOTES.md**

In `IMPLEMENTATION_NOTES.md`, mark Phase 2 as complete. Add this block immediately after the Phase 2 section:

```markdown
**Deviations from plan (reviewed and approved):**
- *(fill in any deviations here)*

**Actual done state:** All Phase 2 types compile. TimeMapperTest (6), InstanceIdProviderTest (3), TreeMutationEventTest (12) all green. `./gradlew clean build` → BUILD SUCCESSFUL.
```

Change `⬅ NEXT` to `✅ COMPLETE (YYYY-MM-DD)` in the Phase 2 heading.

Change `## Phase 3 — Persistence` heading to add `⬅ NEXT`.

- [ ] **Step 4: Commit the notes update**

```bash
git add IMPLEMENTATION_NOTES.md
git commit -m "docs: mark Phase 2 complete"
```

---

## Troubleshooting: Jackson EXTERNAL_PROPERTY with Lombok @Jacksonized

If `TreeMutationEventTest` fails with a Jackson `InvalidDefinitionException` or incorrect payload type during deserialization, the cause is likely that `EXTERNAL_PROPERTY` is not finding the `operationType` sibling field from the builder-based deserialization.

**Diagnostic:** Add a temporary `System.out.println(json)` in the test to verify the serialized JSON looks correct before investigating deserialization.

**Fallback if EXTERNAL_PROPERTY doesn't work with @Jacksonized:**

Replace `TreeMutationEvent` with a class using an explicit `@JsonCreator` constructor:

```java
package com.myxcomp.ice.xtree.messaging.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.myxcomp.ice.xtree.messaging.event.payload.EventPayload;

import java.time.Instant;
import java.util.Objects;

public final class TreeMutationEvent {

    private final String eventId;
    private final String instanceId;
    private final long sequence;
    private final Instant occurredAt;
    private final String iceUser;
    private final String impersonatedUser;
    private final OperationType operationType;
    private final EventPayload payload;

    @JsonCreator
    public TreeMutationEvent(
            @JsonProperty("eventId") String eventId,
            @JsonProperty("instanceId") String instanceId,
            @JsonProperty("sequence") long sequence,
            @JsonProperty("occurredAt") Instant occurredAt,
            @JsonProperty("iceUser") String iceUser,
            @JsonProperty("impersonatedUser") String impersonatedUser,
            @JsonProperty("operationType") OperationType operationType,
            @JsonProperty("payload") EventPayload payload) {
        this.eventId = eventId;
        this.instanceId = instanceId;
        this.sequence = sequence;
        this.occurredAt = occurredAt;
        this.iceUser = iceUser;
        this.impersonatedUser = impersonatedUser;
        this.operationType = operationType;
        this.payload = payload;
    }

    public String getEventId() { return eventId; }
    public String getInstanceId() { return instanceId; }
    public long getSequence() { return sequence; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getIceUser() { return iceUser; }
    public String getImpersonatedUser() { return impersonatedUser; }
    public OperationType getOperationType() { return operationType; }
    public EventPayload getPayload() { return payload; }

    // Static factory to match builder-style test code
    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String eventId;
        private String instanceId;
        private long sequence;
        private Instant occurredAt;
        private String iceUser;
        private String impersonatedUser;
        private OperationType operationType;
        private EventPayload payload;

        public Builder eventId(String v)              { this.eventId = v; return this; }
        public Builder instanceId(String v)           { this.instanceId = v; return this; }
        public Builder sequence(long v)               { this.sequence = v; return this; }
        public Builder occurredAt(Instant v)          { this.occurredAt = v; return this; }
        public Builder iceUser(String v)              { this.iceUser = v; return this; }
        public Builder impersonatedUser(String v)     { this.impersonatedUser = v; return this; }
        public Builder operationType(OperationType v) { this.operationType = v; return this; }
        public Builder payload(EventPayload v)        { this.payload = v; return this; }

        public TreeMutationEvent build() {
            return new TreeMutationEvent(eventId, instanceId, sequence, occurredAt,
                    iceUser, impersonatedUser, operationType, payload);
        }
    }
}
```

The test code in `TreeMutationEventTest` calls `TreeMutationEvent.builder()...build()` and `restored.getOperationType()` — both patterns work with this fallback class unchanged.

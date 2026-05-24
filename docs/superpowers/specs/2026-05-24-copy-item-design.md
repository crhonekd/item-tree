# Copy Item — Design Spec

**Status:** approved 2026-05-24, brainstormed in chat
**Phase:** new Phase 14 (existing Phase 14 → Phase 15)
**Companion docs:** `itemtree-service-design.md` (authoritative service design), `IMPLEMENTATION_NOTES.md` (phasing)

---

## 1. Goal

Add a new mutation operation: **copy** an item (any type) or an entire folder subtree into a folder owned by the caller. Behaviour mirrors the legacy stored-proc copy, modernised to fit the current cache + Solace architecture and the JSON-canonical data model.

The operation is implementable end-to-end in Phase A (H2 + stub messaging). Phase B brings nothing new beyond what the rest of the service needs.

---

## 2. REST surface

### 2.1 Endpoint

| Method | Path | Status | Purpose |
|---|---|---|---|
| POST | `/api/v1/itemtree/items/{id}/copy` | 201 Created | Copy item (or subtree) into a caller-owned folder |

Identity headers (`X-Ice-User`, optional `X-Impersonated-User`) are inherited from the common-headers contract (design §3, §13).

### 2.2 Request

```yaml
CopyItemRequest:
  type: object
  required: [destinationFolderId]
  properties:
    destinationFolderId:
      type: integer
      format: int64
      description: >
        Folder that will own the new copy. Must be the caller's home folder
        or a descendant of it. Caller identity = impersonatedUser if present,
        else iceUser.
```

### 2.3 Response

`HTTP 201 Created`. Body = `List<ItemNode>` — the full new subtree, BFS-ordered (new root first, then children depth-by-depth). Each `ItemNode` carries the standard fields (`itemTreeId`, `parentId`, `name`, `type`, `lastUpdate`, `lastUpdateUser`) plus `path` (root-anchored, slash-separated, like `/tree`).

No `data` payload in this response. Clients use `/items/get` for payloads, consistent with `/tree`.

### 2.4 Error model (RFC 7807)

All copy-specific errors share the existing `Problem` shape (design §3). New + reused `errorCode`s:

| HTTP | `errorCode` | Trigger | New? |
|---|---|---|---|
| 404 | `ITEM_NOT_FOUND` | Source `id` unknown to the cache | reused |
| 400 | `CANNOT_COPY_ROOT` | `id == TreeConstants.ROOT_ID` (1) | **new** |
| 404 | `DESTINATION_NOT_FOUND` | `destinationFolderId` unknown | **new** |
| 400 | `DESTINATION_NOT_FOLDER` | Destination exists but `type != "Folder"` | **new** |
| 404 | `HOME_FOLDER_NOT_FOUND` | Caller has no home folder | reused |
| 400 | `DESTINATION_NOT_IN_USER_FOLDER` | Destination is not the caller's home folder or a descendant of it | **new** |
| 400 | `COPY_INTO_DESCENDANT` | Source == destination, or destination is a descendant of source | **new** |
| 413 | `COPY_TOO_LARGE` | Subtree node count > `itemtree.copy.max-nodes` | **new** |

Validation order (same precedence pattern as `moveItem`):

1. `ITEM_NOT_FOUND`
2. `CANNOT_COPY_ROOT`
3. `DESTINATION_NOT_FOUND`
4. `DESTINATION_NOT_FOLDER`
5. `HOME_FOLDER_NOT_FOUND`
6. `DESTINATION_NOT_IN_USER_FOLDER`
7. `COPY_INTO_DESCENDANT`
8. `COPY_TOO_LARGE` (split: pre-flight against cache; authoritative inside the DB transaction)

`413 Payload Too Large` is chosen for `COPY_TOO_LARGE`; the cap refers to the size of the operation, not the request body.

---

## 3. Architecture

Same write-path discipline as every other mutation (design §5):

```
HTTP POST /items/{id}/copy
      │   body: { "destinationFolderId": <long> }
      ▼
ItemController.copyItem
      │
      ▼
ItemService.copyItem(sourceId, destinationFolderId, UserContext)
      │
      │   [validation, cache read lock]
      │   - source exists / not root
      │   - destination exists / is folder / lives under caller's home folder
      │   - source != destination && source is not ancestor of destination
      │   - BFS via cache: count subtree, fail fast if > cap
      │
      │   [persistence, @Transactional]
      │   - repository.findRowsForCopy(sourceId, cap + 1)   ← DB snapshot, BFS
      │   - re-check count; throw COPY_TOO_LARGE if > cap
      │   - allocate N new ids
      │   - compute oldId→newId map, remap parentIds, suffix top-level name on collision
      │   - repository.insertBatch(newRows)
      │
      │   [cache, single write lock]
      │   - cache.applyCopy(List<CachedNode> newSubtree)
      │
      │   [broadcast, fire-and-forget]
      │   - publisher.publish(COPY event with full subtree payload)
      │
      ▼
HTTP 201 Created
body: List<ItemNode>  (new subtree, BFS-ordered, each with path)
```

Boundaries unchanged: no new dependency between `cache/` and `persistence/`; no imports of generated DTOs outside `api/mapper/`; no per-user authorization (UI-enforced).

---

## 4. Validation & business rules

### 4.1 Caller identity (§13)

```
effectiveUser = userContext.impersonatedUser != null
                  ? userContext.impersonatedUser
                  : userContext.iceUser
```

Same convention as `/tree` and `lastUpdateUser` stamping.

### 4.2 Home-folder containment

```
homeFolder    = cache.findHomeFolder(effectiveUser)          // throws HOME_FOLDER_NOT_FOUND
destinationOk = destination.id == homeFolder.id
             || cache.isAncestor(homeFolder.id, destination.id)
```

`isAncestor` returns `false` for self (existing contract), hence the explicit equality OR.

### 4.3 Self/descendant guard

```
copyIntoSelfOrDescendant =
       source.id == destination.id
    || cache.isAncestor(source.id, destination.id)
```

Mirrors `moveItem`.

### 4.4 Subtree-size cap

**Pre-flight count (cache).** BFS the source subtree from the cache under the read lock, count nodes. Reject `COPY_TOO_LARGE` if count > cap. Avoids opening a DB transaction for an obviously-doomed request.

**Authoritative count (DB).** Inside the transaction, `findRowsForCopy(sourceId, cap + 1)` does an independent BFS. If the result has > cap rows, throw `COPY_TOO_LARGE` and roll back. This catches the case where the cache is behind the DB.

The `limit = cap + 1` parameter short-circuits the DB BFS the moment one row past the cap is collected — no full traversal of huge subtrees.

### 4.5 Top-level name suffix

Only the new root copy gets a name-collision check. Descendants keep their original names.

```
siblingNames = { c.name() : c in cache.getChildren(destinationFolderId) }

candidate = source.name()
if candidate not in siblingNames:
    newRootName = candidate
else:
    candidate = source.name() + " (copy)"
    if candidate not in siblingNames:
        newRootName = candidate
    else:
        n = 2
        candidate = source.name() + " (copy " + n + ")"
        while candidate in siblingNames:
            n += 1
            candidate = source.name() + " (copy " + n + ")"
        newRootName = candidate
```

Suffix format: `Foo` → `Foo (copy)` → `Foo (copy 2)` → `Foo (copy 3)`. Matches common file-manager UX.

A theoretical race exists if a peer creates a sibling with the suffixed name between the cache check and the INSERT. Accepted: same-name siblings are legal in the schema; the cap of 100 keeps the window narrow.

### 4.6 Field stamping on copied rows

| Column | Value |
|---|---|
| `ITEMTREEID` | freshly allocated from `ITEMTREE_ID_SQN.NEXTVAL` |
| `PARENTID` | new root → `destinationFolderId`; others → `newIdMap.get(source.parentId)` |
| `NAME` | new root → `newRootName` (suffixed if needed); others → original name |
| `TYPE` | copied verbatim |
| `JSON` | copied verbatim |
| `XML` | copied verbatim |
| `LASTUPDATE` | `timeMapper.now()` (one value for the whole operation) |
| `LASTUPDATEUSER` | `effectiveUser` |

`LASTUPDATE` / `LASTUPDATEUSER` are NOT copied from the source — the copy is a fresh creation.

### 4.7 What is *not* validated

- **Type policy.** Source rows already satisfy the type policy; verbatim copy preserves that.
- **Per-user authorization.** UI-enforced (CLAUDE.md). The home-folder containment is a structural guard, not a security boundary.
- **Cycles in the source subtree.** Cache invariants forbid them; cap aborts runaway BFS regardless.

---

## 5. Persistence layer (`persistence/`)

### 5.1 New row record

```java
public record ItemTreeFullRow(
    long itemTreeId,
    Long parentId,
    String name,
    String type,
    String json,            // nullable
    String xml,             // nullable
    Instant lastUpdate,
    String lastUpdateUser
) {}
```

Combined structural + payload row. Used only by the two new repo methods.

### 5.2 New repository methods

```java
/**
 * BFS-ordered read of {@code rootId} and all descendants — structural + payload
 * columns in one combined row. Stops after {@code limit} rows; caller passes
 * (cap + 1) to detect cap violations without traversing the whole subtree.
 *
 * @return list in which every non-root node appears after its parent; empty
 *         if {@code rootId} is unknown
 */
List<ItemTreeFullRow> findRowsForCopy(long rootId, int limit);

/**
 * Batched INSERT of N rows in a single round-trip. Ids and parentIds are
 * supplied by the caller (no SEQUENCE.NEXTVAL inside the INSERT).
 */
void insertBatch(List<ItemTreeFullRow> rows);

/** Allocates N fresh ids in one round-trip. */
List<Long> allocateIds(int n);
```

All three methods carry `@Transactional` at the method level (BFS reads need snapshot consistency; INSERT batch needs all-or-nothing; ID allocation is a single statement so the annotation is for documentation).

### 5.3 BFS implementation

Same pattern as the existing `cascadeDeleteSubtree` BFS (memory `feedback-h2-recursive-cte` — H2 2.x can't resolve recursive CTE self-references in a `PreparedStatement`, so BFS is the portable choice):

```
queue       = [rootId]
collected   = []
while queue not empty and collected.size() < limit:
    chunk   = take up to 1000 ids from front of queue           // Oracle IN-list cap
    rows    = SELECT ITEMTREEID, PARENTID, NAME, TYPE, JSON, XML,
                     LASTUPDATE, LASTUPDATEUSER
              FROM ITEMTREE
              WHERE ITEMTREEID IN (:chunk)
    sort rows so parents come first within the chunk             // preserve BFS order
    collected.addAll(rows up to (limit - collected.size()))
    queue.addAll(SELECT ITEMTREEID FROM ITEMTREE WHERE PARENTID IN (:chunk))
return collected
```

### 5.4 Batch INSERT

```sql
INSERT INTO ITEMTREE
  (ITEMTREEID, PARENTID, NAME, TYPE, JSON, XML, LASTUPDATE, LASTUPDATEUSER)
VALUES
  (?, ?, ?, ?, ?, ?, ?, ?)
```

Executed via `JdbcTemplate.batchUpdate(String sql, BatchPreparedStatementSetter)`, same mechanism as the existing `backfillJsonWhereNull`. The `INSERT … RETURNING` shape from the single-row `insert(...)` is not reused — we pre-allocate ids.

### 5.5 ID allocation

```sql
SELECT ITEMTREE_ID_SQN.NEXTVAL FROM DUAL CONNECT BY LEVEL <= :n
```

Oracle hierarchical query; H2 in Oracle compatibility mode supports `CONNECT BY` since 2.x — verify in Phase A. Fallback: N round-trips if H2 rejects it.

---

## 6. Cache layer (`cache/`)

### 6.1 New `TreeCache` method

```java
/**
 * Applies a batch of newly created nodes — a copied subtree — under a single
 * write lock. The list MUST be BFS-ordered (root first, then depth-by-depth)
 * so that each node's parent is either the destination folder (for the new
 * root) or an already-applied node from earlier in the list.
 *
 * Idempotency contract (matches {@link #applyCreate} per design §4): if any
 * id is already present, it is upserted; if a parent reference is missing,
 * the node is still added to {@code byId} (the apply* tolerance contract).
 * The whole batch applies under one lock — atomic from any concurrent
 * reader's view.
 */
void applyCopy(List<CachedNode> newNodes);
```

### 6.2 `DefaultTreeCache` implementation sketch

```java
@Override
public void applyCopy(List<CachedNode> newNodes) {
    Objects.requireNonNull(newNodes, "newNodes");
    if (newNodes.isEmpty()) {
        return;
    }
    lock.writeLock().lock();
    try {
        for (CachedNode n : newNodes) {
            Objects.requireNonNull(n, "newNodes element");
            byId.put(n.itemTreeId(), n);
            childrenByParent
                .computeIfAbsent(n.parentId(), k -> ConcurrentHashMap.newKeySet())
                .add(n.itemTreeId());
            if (Types.isFolder(n.type())) {
                foldersByName
                    .computeIfAbsent(n.name(), k -> ConcurrentHashMap.newKeySet())
                    .add(n.itemTreeId());
            }
        }
    } finally {
        lock.writeLock().unlock();
    }
}
```

Effectively `applyCreate` looped N times under one lock acquisition. Justification (versus the service calling `applyCreate` in a loop):

1. **Atomicity from readers' view.** A reader either sees zero or all of the new nodes — never half. Matches the `replaceAll` invariant and design §4 ("a mutation must update multiple indexes atomically").
2. **Performance.** One lock acquire vs N for what is conceptually one mutation. Negligible at cap 100, but the atomicity reason is sufficient.

### 6.3 BFS ordering is the caller's responsibility

The cache trusts the input. Service layer guarantees BFS order; the cache invariants survive because of the `apply*` idempotency/tolerance contract (design §4).

---

## 7. Messaging layer (`messaging/`)

### 7.1 New `OperationType`

```java
public enum OperationType {
    CREATE, UPDATE, MOVE, RENAME, DELETE,
    COPY                                              // new
}
```

### 7.2 New payload record

```java
package com.myxcomp.ice.xtree.messaging.event.payload;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CopyPayload(
    List<CopiedNode> newNodes
) implements EventPayload {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CopiedNode(
        long itemTreeId,
        Long parentId,
        String name,
        String type,
        Instant lastUpdate,
        String lastUpdateUser
    ) {}
}
```

`CopiedNode` is structurally identical to `CreatePayload` minus the operation framing. Not reused, because a `List<CreatePayload>` would semantically suggest "N CREATE operations" — and we deliberately broadcast COPY as a single atomic event.

JSON/XML payloads are NOT broadcast — design §6 ("the JSON payload is never broadcast"). Peer caches don't store payload; payload was already written to the DB by the originator and will be served on demand.

### 7.3 Jackson polymorphism

`TreeMutationEventDeserializer` already dispatches on `operationType` to pick the payload class (Phase 2). Add one case:

```
case COPY → CopyPayload.class
```

`@JsonIgnoreProperties(ignoreUnknown = true)` on both new records — forward-compat per the Phase 10 post-audit convention.

### 7.4 `EventDispatcher` change

One new branch:

```java
case COPY:
    CopyPayload cp = (CopyPayload) event.payload();
    cache.applyCopy(cp.newNodes().stream()
        .map(n -> new CachedNode(
                n.itemTreeId(), n.parentId(), n.name(), n.type(),
                n.lastUpdate(), n.lastUpdateUser()))
        .toList());
    break;
```

`ClassCastException` from a wrong-typed payload is caught and counted as `itemtree.event.consume.payload.type.mismatch` (Phase 10 post-audit metric).

### 7.5 Originator-side publish

Same try-catch pattern as `createItem`:

```java
try {
    publisher.publish(buildEvent(userContext, OperationType.COPY,
        new CopyPayload(newNodes), now));
} catch (RuntimeException e) {
    log.error("EventPublisher threw on {}; event dropped", OperationType.COPY, e);
}
```

Fire-and-forget. Publish failure leaves the DB write and local cache update intact; peers catch up at the next periodic refresh.

### 7.6 Envelope sizing

100 nodes × ~95 bytes structural per node ≈ ~10 KB raw; serialised JSON well under 64 KB. No size concerns for JMS `TextMessage`.

---

## 8. Configuration

```yaml
itemtree:
  copy:
    max-nodes: 100      # hard cap on subtree size per copy request
```

```java
@ConfigurationProperties("itemtree.copy")
public record CopyProperties(
    @DefaultValue("100") int maxNodes
) {
    @PostConstruct
    void validate() {
        if (maxNodes < 1) {
            throw new IllegalStateException(
                "itemtree.copy.max-nodes must be >= 1, got " + maxNodes);
        }
    }
}
```

Registered via `@EnableConfigurationProperties(CopyProperties.class)` on the existing `@Configuration` class in `config/`.

---

## 9. Metrics

| Metric | Type | Tags | Wired in |
|---|---|---|---|
| `itemtree.copy.requests` | Counter | `result=success\|rejected` | `ItemService.copyItem` (success path & each throw) |
| `itemtree.copy.rejected` | Counter | `reason=<ERROR_CODE>` | each validation throw, before re-throwing |
| `itemtree.copy.subtree.size` | DistributionSummary | — | recorded once per successful copy, after INSERT, before broadcast |

`instanceId` is already applied as a Micrometer common tag (Phase 12) and is automatically present on all three.

`ObservabilityExposureIT` (Phase 12) gains assertions on all three metric names after exercising one successful + one rejected copy.

---

## 10. Tests

### 10.1 Unit

| File | New tests |
|---|---|
| `ItemServiceTest` (new nested `CopyItem`) | Happy path single-item; happy path folder subtree; name-suffix algorithm (verbatim / `(copy)` / `(copy 2)` / `(copy 3)`); each of the 8 validation errors in order; verbatim JSON+XML preservation; `LASTUPDATE`/`LASTUPDATEUSER` stamped fresh; cache `applyCopy` called once with BFS-ordered list; publisher called once with `OperationType.COPY` matching payload; publisher exception swallowed; collapse same-shape error tests into `@ParameterizedTest` per CLAUDE.md |
| `DefaultTreeCacheTest` (new nested `ApplyCopy`) | Happy path; idempotency (re-apply same batch); tolerance (orphan parent); concurrency stress writer rotation includes `applyCopy`; atomicity from readers' view; null guards |
| `EventDispatcherTest` | COPY happy path; ClassCastException on wrong payload |
| `EventConsumerServiceTest` | COPY round-trip; self-echo drop on COPY |
| `TreeMutationEventTest` | COPY envelope serialise/deserialise; unknown field on `CopiedNode` ignored |
| `CopyPropertiesTest` (new) | `maxNodes < 1` rejected; default applied |

### 10.2 Integration

| File | New tests |
|---|---|
| `JdbcItemTreeRepositoryIT` | `findRowsForCopy` happy path; `limit = cap + 1` short-circuit; unknown id → empty; 1000-id IN-list boundary; BFS order; `insertBatch` happy path; `insertBatch` rolls back on constraint violation; `allocateIds(n)` returns N unique strictly-increasing |
| `MessagingLoopbackIT` | Copy round-trip: originator → peer cache convergence |
| `ObservabilityExposureIT` | New `itemtree.copy.*` assertions |

### 10.3 HTTP slice

| File | New tests |
|---|---|
| `ItemControllerTest` (new nested `CopyItem`) | Happy path → 201 + subtree body; missing `destinationFolderId` → 400; each of 8 service errors mapped to the right HTTP status + `errorCode`; headers propagated to service |

### 10.4 E2E

Add `copyPropagatesAcrossInstances` to `ItemTreeApplicationE2EIT` as part of the new Phase 14 (NOT retroactively into the existing Phase 13 E2E). Mirrors the existing `mutationPropagatesAcrossInstances` and `cascadeDeletePropagatesSubtreeRemovalToB` patterns.

### 10.5 Total ballpark

~40–55 new tests. Existing 548 → ~590–600 after this phase.

---

## 11. Phase numbering

`IMPLEMENTATION_NOTES.md` updates:

1. Rename existing `## Phase 14 — Work PC wiring (Phase B, user-managed)` heading to `## Phase 15 — Work PC wiring (Phase B, user-managed)`. Body unchanged.
2. Insert a new `## Phase 14 — Copy Item` section between the existing Phase 13 block and the renamed Phase 15 block. Body summarises goal, surface, tests, done-when, and notes that this phase is fully implementable in Phase A.

Memory updates happen later, when Phase 14 is completed (`project-phase14-copy-item-done.md`).

---

## 12. Decisions log (for traceability)

| Question | Decision |
|---|---|
| Target-user identity | Derived from headers (impersonatedUser ?: iceUser) |
| Source restrictions | reject self/descendant, reject root, 404 on missing source; **no** source-folder containment check |
| Subtree cap | configurable `itemtree.copy.max-nodes`, default 100; `COPY_TOO_LARGE` → HTTP 413 |
| Name collision | auto-suffix top-level only (`(copy)`, `(copy 2)`, …); children unchanged |
| Column copy | verbatim JSON + XML; `LASTUPDATE`/`LASTUPDATEUSER` stamped fresh |
| Event model | single COPY event with BFS-ordered subtree payload; new OperationType + new payload record |
| Response shape | full new subtree as `List<ItemNode>`, BFS-ordered, with `path` |
| Persistence | DB-snapshot approach (Approach A): cache validation → DB BFS inside transaction → batch INSERT → cache apply → broadcast |

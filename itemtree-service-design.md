# ITEMTREE Service — Design Specification

**Status:** Design phase
**Date:** 2026-05-13
**Scope:** Spring Boot REST service fronting the `ITEMTREE` Oracle table; multi-instance, cache-backed, event-distributed via Solace.

---

## Table of Contents

1. [Functional Overview](#1-functional-overview)
2. [Data Model](#2-data-model)
3. [REST API](#3-rest-api)
4. [Caching](#4-caching)
5. [Write Path & Consistency](#5-write-path--consistency)
6. [Distribution (Solace)](#6-distribution-solace)
7. [Bootstrap & Refresh](#7-bootstrap--refresh)
8. [`getTreeView` Algorithm](#8-gettreeview-algorithm)
9. [Path Computation](#9-path-computation)
10. [Type-driven Data Policy](#10-type-driven-data-policy)
11. [XML / JSON Conversion](#11-xml--json-conversion)
12. [Persistence Layer](#12-persistence-layer)
13. [Identity (Two-User Model)](#13-identity-two-user-model)
14. [Time Handling — UTC Throughout](#14-time-handling--utc-throughout)
15. [Tech Stack](#15-tech-stack)
16. [Module / Package Layout](#16-module--package-layout)
17. [Configuration Surface](#17-configuration-surface)
18. [Observability](#18-observability)
19. [Out of Scope](#19-out-of-scope)
20. [Open Items Pending Confirmation](#20-open-items-pending-confirmation)
21. [Glossary](#21-glossary)

---

## 1. Functional Overview

The service exposes REST endpoints for operations on a typed item tree persisted in a single Oracle table. The tree contains ~350,000 rows representing folders and various leaf types (reports, filters, datasets, etc.).

### Operations supported

- Create item
- Delete item (cascade on folders)
- Move item
- Rename item
- Update item (full JSON replace)
- Get items (by id list, returns payload)
- Get tree (trimmed view per user)
- Get subtree
- Search (by id or name)
- Get home folder for user

### Deployment shape

Up to ~6 load-balanced instances on Unix (no Kubernetes). Each instance keeps the full tree (minus payload) in memory for fast structural reads. Mutations are persisted to the database first, then applied to the local cache, then broadcast to peer instances via Solace.

### Performance posture

- Mostly-read workload; reads must be very fast.
- Writes are infrequent but must be durable.
- Concurrent reads on the cache must not block on each other.
- The service must handle many requests in parallel.

---

## 2. Data Model

### Database table: `ITEMTREE`

| Column         | Oracle Type    | Notes |
|----------------|----------------|-------|
| ITEMTREEID     | NUMBER(10)     | Primary key. Populated from sequence `ITEMTREE_ID_SQN`. |
| PARENTID       | NUMBER(10)     | Always populated; never null. `0` for the root node. |
| NAME           | VARCHAR2(70)   |       |
| TYPE           | VARCHAR2(30)   | String. Open-ended; new types may be introduced. |
| XML            | CLOB           | Legacy column. Read for fallback during transition; may also be written for select types (see §10). |
| JSON           | CLOB           | Canonical payload. Null for `Folder` and other types without data. |
| LASTUPDATE     | DATE           | UTC by convention (see §14). |
| LASTUPDATEUSER | VARCHAR2(20)   |       |

### Constants

- Root id: `1`
- Root's parentId: `0`
- Folder type literal: `"Folder"` (capital F)
- Oracle sequence: `ITEMTREE_ID_SQN`

### Indexes

- Primary key on `ITEMTREEID` (existing).
- Non-unique index on `PARENTID` (existing).
- **New:** non-unique index on `LASTUPDATE` — required to support delta refresh. DBA-owned.

### Schema mutation policy

Schema is otherwise frozen. The application is read-only with respect to schema definition, except for the LASTUPDATE index above, which has been approved.

### Java type mapping

| Oracle           | Java                       |
|------------------|----------------------------|
| `NUMBER(10)`     | `java.lang.Long`           |
| `VARCHAR2`       | `String`                   |
| `CLOB`           | `String` (Spring JdbcClient default; switch to streaming if outliers appear) |
| `DATE`           | `java.time.Instant` internally; bound as UTC `LocalDateTime` at the JDBC boundary |

### `type` field as `String`, not enum

Rationale: new types are introduced operationally without code changes. An enum would force a code release across all instances per type addition. The service's only type-aware logic is:

- The folder discriminator: `Types.isFolder(String type)`.
- The three configurable behaviour lists in `TypePolicy` (see §10).

All other behaviour treats `type` as an opaque label.

### History

Existing triggers on `ITEMTREE` populate the history table. The application performs no history writes.

---

## 3. REST API

OpenAPI-first. Spec at `src/main/resources/openapi/itemtree-api.yaml`. Generator produces `*Api` interfaces and DTOs into a separate source set under `com.<org>.itemtree.generated.api` / `.model`.

Base path: `/api/v1/itemtree`.

### Common headers (all endpoints)

| Header | Required | Purpose |
|---|---|---|
| `X-Ice-User` | Yes | Authenticated user (1–20 chars). |
| `X-Impersonated-User` | No | User being acted on behalf of (1–20 chars). |

### Endpoints

| Method | Path                                | Purpose                                 |
|--------|-------------------------------------|------------------------------------------|
| POST   | `/items`                            | Create item                              |
| DELETE | `/items/{id}`                       | Delete (cascade if folder)               |
| POST   | `/items/{id}/move`                  | Move to new parent                       |
| POST   | `/items/{id}/rename`                | Rename                                   |
| PUT    | `/items/{id}/data`                  | Full JSON replace                        |
| POST   | `/items/get`                        | Bulk get by id list (POST to avoid URL-length issues) |
| GET    | `/tree`                             | Trimmed tree view per user               |
| GET    | `/tree/{rootId}/subtree`            | Subtree (flat)                           |
| GET    | `/search`                           | Search by id or name                     |
| GET    | `/users/{userName}/home-folder`     | Resolve home folder for given user       |

### Schema summary

- **`ItemNode`** — structural: `itemTreeId`, `parentId`, `name`, `type`, `path` (tree endpoints only), `lastUpdate`, `lastUpdateUser`.
- **`ItemNodeWithData`** — extends `ItemNode` with `dataJson` (object, nullable), `dataXml` (string, nullable), `children` (array of `ItemNodeWithData`, populated only when node is a folder).
- **`SearchHit`** — `itemTreeId`, `name`, `type`.
- **`CreateItemRequest`** — `parentId`, `name`, `type`, optional `data`.
- **`Problem`** — RFC 7807 with extensions `errorCode` and `traceId`.

### Response shapes

- **`/tree` and `/tree/{rootId}/subtree`** — flat list of `ItemNode`, each with `path` (root-anchored, slash-separated, e.g. `root/Folder1/IceReport`). `/tree` shape may be revisited later to switch to nested; flat is the contract for now.
- **`/search`** — flat list of `SearchHit`. Optional `limit` query parameter; no default cap.
- **`/items/get`** — list of `ItemNodeWithData`. Folder nodes include `children` (one level deep, including each child's `dataJson` / `dataXml` where applicable). Non-folder nodes include `dataJson` or `dataXml` (at most one populated). **Missing ids are silently omitted** from the response.
- **`/users/{userName}/home-folder`** — single `ItemNode`. 404 with `errorCode = HOME_FOLDER_NOT_FOUND` if not found.

### Identity rules

- **`lastUpdateUser` stamping**: impersonated user if present, else iceUser.
- **Home-folder resolution on `/tree`**: impersonated user if present, else iceUser.
- **Solace events**: carry both `iceUser` and `impersonatedUser`.
- **`/users/{userName}/home-folder`**: the `userName` in the path is the subject of the lookup; the headers identify the caller.

### Validation rules

- `move` validates the new parent exists, is a folder, is not the moved node itself, and is not a descendant of the moved node.
- `create` rejects non-null `data` when `type` is in `types-without-data` (returns 400, `errorCode = TYPE_CANNOT_HAVE_DATA`).
- `update` rejects calls against folders (returns 400, `errorCode = FOLDER_CANNOT_HAVE_DATA`).
- Cascade delete is unbounded; permissions are enforced UI-side.

### Error model

RFC 7807 `application/problem+json` for all error responses. Standard fields (`type`, `title`, `status`, `detail`, `instance`) plus extensions:
- `errorCode` — machine-readable (e.g. `PARENT_NOT_FOUND`, `MOVE_INTO_DESCENDANT`).
- `traceId` — from Micrometer Tracing.

---

## 4. Caching

### Goal

Serve all read endpoints (`/tree`, `/tree/{rootId}/subtree`, `/search`, `/users/{userName}/home-folder`) entirely from memory. Only `/items/get` and write endpoints touch the DB.

### Cached state

Each instance holds the full structural tree minus the `XML` and `JSON` columns. The cache is encapsulated in `TreeCache`.

### Internal indexes

Three indexes, all kept consistent under the write lock:

```
byId:             Map<Long, CachedNode>
childrenByParent: Map<Long, Set<Long>>
foldersByName:    Map<String, Set<Long>>
```

Maps are `ConcurrentHashMap`; child sets use `ConcurrentHashMap.newKeySet()`.

### `CachedNode`

```java
public record CachedNode(
    long itemTreeId,
    Long parentId,           // never null; 0 for root
    String name,
    String type,
    Instant lastUpdate,      // UTC
    String lastUpdateUser
) {}
```

### `TreeSnapshot`

```java
public record TreeSnapshot(
    Map<Long, CachedNode> byId,
    Map<Long, Set<Long>> childrenByParent,
    Map<String, Set<Long>> foldersByName
) {}
```

The snapshot is the unit swapped in by full reload.

### Concurrency model

A single `ReentrantReadWriteLock` guards the three indexes. Rationale:

- Writes are rare relative to reads.
- A mutation must update multiple indexes atomically.
- Alternatives (per-subtree locks, lock-free structures) introduce significant bug surface around moves without measurable benefit at this volume.

Lock holds are short:

- Reads: in-memory map lookups + result construction.
- Writes: in-memory pointer updates only. No I/O under the lock.

### Memory estimate

~350k nodes × ~200 bytes/node (including index overhead) ≈ 70–80 MB primary, ~100–150 MB total with all indexes. Trivial for a modern JVM.

### `TreeCache` interface (final)

```java
public interface TreeCache {
    // ── Reads ────────────────────────────────────────────────
    Optional<CachedNode> getById(long id);
    List<CachedNode>     getChildren(long parentId);
    List<CachedNode>     getSubtreeFlat(long rootId);
    List<CachedNode>     getTreeView(long homeFolderId);
    Optional<CachedNode> findHomeFolder(String userName);
    Optional<CachedNode> searchById(long id);
    List<CachedNode>     searchByName(String needle, OptionalInt limit);
    boolean              isAncestor(long candidateAncestorId, long nodeId);
    boolean              exists(long id);
    boolean              isFolder(long id);
    int                  size();

    // ── Mutations ────────────────────────────────────────────
    void applyCreate(CachedNode node);
    void applyMetadataUpdate(long id, Instant lastUpdate, String lastUpdateUser);
    void applyMove(long id, long newParentId, Instant lastUpdate, String lastUpdateUser);
    void applyRename(long id, String newName, Instant lastUpdate, String lastUpdateUser);
    void applyDelete(Set<Long> ids);
    void replaceAll(TreeSnapshot newSnapshot);
}
```

### Invariants (enforced under the write lock)

1. Every node's `parentId` is `0` (root) or references an existing folder.
2. `byId` keyset == root id ∪ all values across `childrenByParent`.
3. `foldersByName` keys are exactly the names of nodes with `Types.isFolder(type)`.

The Solace consumer calls the `apply*` methods directly. It does not re-validate; the originating instance is the authority for the mutation.

### `apply*` idempotency contract

Reconnect-driven event delivery may produce duplicate or out-of-order messages. `apply*` methods are **tolerant**, never throw on weird input:

| Method | Behaviour on weird input |
|---|---|
| `applyCreate(node)` | If id already present: upsert (overwrite). The event payload is the latest authoritative structural view from the source instance. |
| `applyMetadataUpdate(id, ...)` | If id missing: log + skip. Next refresh creates the row. |
| `applyMove(id, newParentId, ...)` | If id missing or newParentId missing: log + skip. |
| `applyRename(id, ...)` | If id missing: log + skip. |
| `applyDelete(ids)` | Remove what's present; ignore missing. |

The cache stays internally consistent; missing data converges at the next refresh.

---

## 5. Write Path & Consistency

### Mutation order on the receiving instance

```
1. Validate against cache (e.g. move-into-descendant check)
2. Persist to DB (transactional)
3. Apply to local cache (under write lock)
4. Broadcast event via Solace (fire-and-forget)
5. Return HTTP response
```

### Rationale for DB-first

- DB is the **golden source of data**. If DB write fails, no cache update, no event.
- Cache update is pure in-memory work; effectively cannot fail.
- Broadcast is best-effort; failure does not invalidate the DB write or the local cache.

### Consistency model

Eventual consistency across instances. The DB is always authoritative; cache lag on peer instances is acceptable. Periodic refresh (§7) reconciles drift.

### Concurrent edit of the same item

**Out of scope.** No optimistic locking, no version column. Treated as an unlikely event.

---

## 6. Distribution (Solace)

### Topic

Single topic: `BC/ICE/ITEMTREE`. Best-effort (non-guaranteed) delivery. Operation type is carried in the payload, not the topic.

### Library — `com.barcap.ice.service.jms`

The internal `com.barcap.ice.service.jms` library is used. It is built on:

- **Jakarta JMS** (`jakarta.jms.*`) — `MessageListener`, `ExceptionListener`, `Destination`, `TextMessage`.
- **Spring JMS** — listener side uses `org.springframework.jms.listener.DefaultMessageListenerContainer` (DMLC).
- **Solace JMS provider** — `com.solacesystems.jms.SolConnectionFactory` underneath; library is provider-agnostic and could also wrap TIBCO.

Key wrapper types:

| Type | Role |
|---|---|
| `JMSAbstractService` | Encapsulates the JMS connection factory + topic; identified by `getServiceName()`. |
| `JMSListenerServiceImpl extends DefaultMessageListenerContainer implements JMSListenerService` | Listener side. DMLC handles auto-reconnect, auto-resubscribe, and session recovery. |
| `JMSPublisherService` | Publisher side. `reliablePublish(String message) throws ServiceException` retries internally up to `max_attempts`. |
| `RecoveryAgent` | Configured recovery interval; backoff strategy for reconnect attempts. |
| `ConnectionExceptionListener implements jakarta.jms.ExceptionListener` | On `JMSException`, loops `refreshConnectionUntilSuccessful()`. Augmented (see below) with a `ConnectionRecoveryListener` callback so we know when recovery succeeds. |

The library beans are owned by the deployment config; this service consumes them as injected dependencies.

### Why this gives us the resilience we need

- **Listener side** — DMLC re-establishes sessions and subscriptions automatically when the broker comes back. The `MessageListener` we register stays attached across reconnects.
- **Publisher side** — `ConnectionExceptionListener.refreshConnectionUntilSuccessful()` rebuilds the connection on `JMSException`. `reliablePublish` retries up to `max_attempts` per call.
- **Visibility** — the augmented `ConnectionRecoveryListener` (added to `ConnectionExceptionListener`) emits `onConnectionLost(serviceName)` and `onConnectionRecovered(serviceName)`. Our `ConnectionStateTracker` registers for these and feeds `ReconnectReconciler`.

### Connection recovery callback

The library's `ConnectionExceptionListener` is augmented with:

```java
public interface ConnectionRecoveryListener {
    void onConnectionLost(String serviceName);
    void onConnectionRecovered(String serviceName);
}

// On ConnectionExceptionListener:
void addRecoveryListener(ConnectionRecoveryListener listener);
```

`onConnectionLost` fires at the start of `onException(JMSException)`; `onConnectionRecovered` fires after the recovery loop breaks successfully. Listener exceptions inside callbacks are swallowed.

### Concurrency setting

`JMSListenerServiceImpl` accepts a `concurrentConsumers` parameter. **We set `concurrentConsumers = 1`** to preserve event order — with >1, DMLC dispatches to multiple threads and a CREATE could be applied after its child's UPDATE. The `apply*` methods are tolerant of weird order (see §4), but single-consumer keeps logs clean and minimises transient drift.

### Publisher retry setting

`max_attempts = 2` on `JMSPublisherService`. One retry is enough; we don't want to block the request handler waiting for Solace recovery. Failed publishes are repaired by periodic refresh.

### Event envelope

```json
{
  "eventId": "uuid",
  "instanceId": "uuid of originator",
  "sequence": 12345,
  "occurredAt": "2026-05-13T14:30:00Z",
  "iceUser": "string",
  "impersonatedUser": "string|null",
  "operationType": "CREATE|UPDATE|MOVE|RENAME|DELETE",
  "payload": { ... }
}
```

Serialised as JSON; delivered as a JMS `TextMessage`, UTF-8.

### Payloads by operation

| Op       | Payload |
|----------|---------|
| `CREATE` | `{ itemTreeId, parentId, name, type, lastUpdate, lastUpdateUser }` |
| `UPDATE` | `{ itemTreeId, lastUpdate, lastUpdateUser }` (metadata only; JSON not broadcast) |
| `MOVE`   | `{ itemTreeId, oldParentId, newParentId, lastUpdate, lastUpdateUser }` |
| `RENAME` | `{ itemTreeId, newName, lastUpdate, lastUpdateUser }` |
| `DELETE` | `{ deletedIds: [long, ...] }` (root + all descendants in one event) |

The JSON payload (`data`) is **never broadcast**. Peer caches do not store it.

### Self-echo suppression

Each instance generates a UUID `instanceId` at startup. Every published event carries it. The consumer drops events whose `instanceId` matches the local instance.

### Sequence number

Per-instance `AtomicLong`. Consumers may log sequence gaps for observability; recovery happens via periodic refresh.

### Reconnect reconciliation

When `ConnectionStateTracker` receives `onConnectionRecovered` after a prior `onConnectionLost`, it computes outage duration and `ReconnectReconciler` decides:

```
outage < short-threshold (default PT1M)  → no extra action; scheduled delta covers it
outage < long-threshold  (default PT1H)  → trigger immediate delta refresh
outage ≥ long-threshold                  → trigger immediate full reload
```

The triggered refresh queues through `RefreshOrchestrator`'s single-threaded scheduler — never concurrent with scheduled refreshes.

**First-connect special case:** the very first `onConnectionRecovered` after startup has no prior `onConnectionLost`. Tracker treats this as "first connect" and skips reconciliation; the cache is already fresh from bootstrap.

### Beans the deployment config provides

```java
@Bean JMSConnectionFactory itemTreeConnectionFactory(...)
@Bean RecoveryAgent itemTreeRecoveryAgent(...)
@Bean ConnectionExceptionListener itemTreeExceptionListener(...)
@Bean JMSSingleConnectionFactory itemTreeSingleConnectionFactory(...)
@Bean JMSAbstractService itemTreeTopicService(...)        // wraps BC/ICE/ITEMTREE
@Bean JMSListenerService  itemTreeListener(...)           // with concurrentConsumers=1, our MessageListener
@Bean JMSPublisherService itemTreePublisher(...)          // max_attempts=2
```

### Our beans (in this service)

- `EventConsumerService implements jakarta.jms.MessageListener` — extracts `TextMessage`, deserialises, drops self-echoes, dispatches.
- `EventDispatcher` — routes by `operationType` to `TreeCache.apply*`.
- `ConnectionStateTracker implements ConnectionRecoveryListener` — registers with `itemTreeExceptionListener`.
- `ReconnectReconciler` — outage duration → delta or full reload via `RefreshOrchestrator`.
- `SolaceEventPublisher` — wraps `itemTreePublisher.reliablePublish(json)` with metrics and try/catch on `ServiceException`.
- `SequenceGenerator` — `AtomicLong` per JVM.

### Failure handling

| Failure | Behaviour |
|---|---|
| DB write fails | Operation fails; client gets error. No cache change, no event. |
| Cache update fails (in-memory; shouldn't happen) | Logged + metric. DB committed; next refresh repairs. |
| `reliablePublish` exhausts `max_attempts` → throws `ServiceException` | Logged + counter. DB committed, local cache correct; peers catch up at next refresh. HTTP request still succeeds (client sees DB-correct state). |
| Deserialise fails on consume | Logged with payload prefix + counter; message dropped. |
| Apply fails on consume (e.g. missing reference) | Logged + counter; cache unchanged for this event; next refresh repairs. |
| Solace blip <1 min | DMLC + `ConnectionExceptionListener` recover. No app-side action; scheduled delta covers any missed events. |
| Outage 1 min – 1 h | Recovery callback fires → immediate delta refresh queued via `RefreshOrchestrator`. |
| Outage > 1 h | Recovery callback fires → immediate **full reload** queued. |
| Outage > 4 h | `SolaceHealthIndicator` flips DOWN → LB removes instance from rotation. Manual intervention. |
| Solace down at startup | Bootstrap completes from DB. `listener.start()` returns immediately (DMLC retries in background). T4 fires; instance serves reads. First `onConnectionRecovered` later is treated as first-connect (no reconcile). |
| App shutdown | Spring lifecycle calls `listener.stop()` (DMLC drains in-flight `onMessage` calls). |

---

## 7. Bootstrap & Refresh

### Components

| Component | Role |
|---|---|
| `TreeCacheBootstrap` | `ApplicationRunner @Order(1)` — loads cache on startup. |
| `SolaceConsumerStarter` | `ApplicationRunner @Order(2)` — subscribes to Solace topic. |
| `RefreshScheduler` | `@Scheduled` entrypoints for delta and full reload. |
| `RefreshOrchestrator` | Body of delta and full reload logic. |
| `DeltaReconciler` | Per-row diff logic for delta refresh. |
| `RefreshActuatorEndpoint` | Manual trigger via Actuator. |
| `SnapshotBuilder` | Shared between bootstrap and full reload. |

### Startup ordering

```
T0  Spring context starts; ApplicationAvailability = REFUSING_TRAFFIC
T1  Beans constructed
T2  TreeCacheBootstrap runs:
     - repository.streamAllStructural(...) → SnapshotBuilder → TreeSnapshot
     - cache.replaceAll(snapshot)
     - cacheReady = true
T3  SolaceConsumerStarter runs (non-blocking):
     - itemTreeListener.start()      // DMLC; registers subscription, connects asynchronously
     - registers ConnectionStateTracker with ConnectionExceptionListener
     // Returns immediately. If Solace is down, DMLC retries in the background.
T4  AvailabilityChangeEvent → ACCEPTING_TRAFFIC   // happens regardless of Solace state
T5  LB readiness probe sees UP; instance enters rotation
```

**Critical resilience property:** T4 does NOT wait for Solace. If the broker is unreachable at startup, the cache still serves reads. Writes still succeed (DB + cache); their broadcasts may fail until the broker is back, after which `onConnectionRecovered` fires and peers reconcile via their next refresh. See §6 for the full reconnect-reconciliation flow.

### Bootstrap reliability

Bootstrap wraps the DB call in a retry policy: 3 attempts, exponential backoff (1s, 5s, 25s). If all attempts fail, the application stays REFUSING_TRAFFIC and the CI/CD readiness check fails the deployment.

Long startup time is acceptable; rollouts happen off-hours at start of week.

### Readiness gating (two layers)

1. **Spring `ApplicationAvailability` ReadinessState** → reflected in `/actuator/health/readiness`. The LB probe gates traffic on this.
2. **`CacheReadinessFilter`** — short-circuits with 503 + Problem if `!cacheReady`. Defence in depth.

### Delta refresh

- **Cron:** every 30 minutes (configurable).
- **Query:** `SELECT ... FROM ITEMTREE WHERE LASTUPDATE > :since` with 60-second overlap to cover clock skew.
- **For each row**, diff against cache and dispatch to `applyCreate`, `applyMove`, `applyRename`, or `applyMetadataUpdate`.
- **Cannot detect deletes** — those are caught by full reload.
- **`lastRefreshInstant` advances only on success** — failures retry from the same point.

### Full reload

- **Cron:** nightly weekdays at 02:00 UTC (configurable).
- Build new `TreeSnapshot` outside the cache write lock.
- Compute drift summary (created / deleted / mutated since last reload) for observability.
- `cache.replaceAll(newSnapshot)` — atomic swap under brief write lock.
- **Drift counters are the key health signal**: non-zero values indicate event-stream gaps.

### Scheduling concurrency

Single-threaded `ThreadPoolTaskScheduler` (`poolSize=1`) serialises all refreshes within an instance. Bootstrap, delta refresh, full reload, and manual triggers never overlap.

### Across-instances coordination

None needed. Each instance refreshes independently from the DB. Total load: ~12 structural reads per hour across 6 instances; negligible.

### Manual trigger

```
POST /actuator/itemtree-refresh?type=delta|full
```

Gated to the management port and trusted CIDR via Spring Security's actuator config.

---

## 8. `getTreeView` Algorithm

### Inputs

`getTreeView(homeFolderId: long)` — called after `findHomeFolder(userName)` succeeded. Throws `IllegalArgumentException` if home folder not present in cache.

### Sources of nodes (union, deduplicated)

1. **Skeleton** — all folders at depth 0, 1, and 2 (root + its folder children + their folder grandchildren).
2. **Ancestor chain** — every node on the path from root down to the home folder (emitted root → home).
3. **Home folder's direct children** — all types, including subfolders, one level only.

### Algorithm (under read lock)

```
skeleton = empty Set<Long>
for node where parentId == ROOT_PARENT_ID:        // the single root, id 1
    if isFolder(node):
        skeleton.add(node.itemTreeId)
        for child in childrenByParent[node.itemTreeId]:
            if isFolder(child):
                skeleton.add(child.itemTreeId)
                for grand in childrenByParent[child.itemTreeId]:
                    if isFolder(grand):
                        skeleton.add(grand.itemTreeId)

chain = empty List<Long>
cursor = byId[homeFolderId]
while cursor != null:
    chain.add(cursor.itemTreeId)
    if cursor.parentId == ROOT_PARENT_ID:
        break
    cursor = byId[cursor.parentId]
Collections.reverse(chain)                         // root → home

homeChildren = childrenByParent[homeFolderId]

result = new LinkedHashSet<Long>
result.addAll(skeleton)
result.addAll(chain)
result.addAll(homeChildren)

return result.stream()
             .map(byId::get)
             .filter(Objects::nonNull)
             .toList()
```

### Edge cases

- **Home folder is the root** — chain has one element; skeleton covers it; children added normally.
- **Home folder within skeleton depth** — chain fully overlaps; harmless.
- **Home folder deep (e.g. depth 7)** — chain contributes depths 3–7.
- **Home folder empty** — no children added; skeleton + chain returned.
- **Cycle in parentId chain** — defensive cap of 100 iterations; if hit, log + metric + flag for unscheduled full reload.
- **Missing ancestor mid-walk** — stop walk, log warning, same metric.

### Complexity

Sub-millisecond at typical sizes. Result is bounded (~50–200 nodes).

### Output ordering

LinkedHashSet insertion order: skeleton first, then chain (root → home), then home children. No server-side sorting within siblings.

---

## 9. Path Computation

### Approach

**Lazy compute at response time** for `/tree` and `/tree/{rootId}/subtree`. Not stored on `CachedNode`.

### Rationale

Pre-computing `path` on each node and storing it would:

- Add ~25 MB cache memory (acceptable).
- Add ~1s to bootstrap (acceptable).
- **Force O(subtree) write-lock-held recomputation on every rename and every move.** Renaming a near-root folder containing 100k descendants would block reads for hundreds of milliseconds. This violates the cache's latency goal.

Lazy compute costs ~1μs per node walked, ~200μs per `/tree` call. Negligible. No invalidation needed.

### `PathResolver`

```java
public interface PathResolver {
    String pathOf(long itemTreeId);
    Map<Long, String> pathsOf(Collection<Long> ids);  // memoises ancestors within the call
}
```

Called by `TreeService` after `TreeCache` returns the node list. Path format: `"root/Folder1/IceReport"`.

### Where path appears

| Endpoint | Path field populated? |
|---|---|
| `/tree` | Yes |
| `/tree/{rootId}/subtree` | Yes |
| `/items/get` | No |
| `/search` | No |
| Create / update / move / rename / delete responses | No |

---

## 10. Type-driven Data Policy

### Three configurable type lists

```yaml
itemtree:
  data:
    # Types that structurally have no payload.
    # Reject create/update with non-null data (400 TYPE_CANNOT_HAVE_DATA).
    types-without-data:
      - Folder
      - Shortcut
      - Shortcut.Report
      - Shortcut.Filter
      - Shortcut.Filter.Nested

    # Legacy types whose writes populate both columns:
    # JSON canonical, XML mirrored (converted from JSON via the library).
    types-also-persisted-as-xml-on-write:
      - DrillDown.Set
      - Report
      - Filter
      - Details.Column.Collection
      - Numeric.Bucket.Collection
      - Discrete.Bucket.Collection
      - Bucket.Collection

    # Types whose payload is shipped to the UI as raw XML.
    # Empty in ICEX; kept as a config knob for future legacy bridges.
    types-sent-as-xml-to-ui: []

    # New-format types — not in any list — default to JSON-only on persist
    # and JSON to UI. Examples: View, UDF.Context, Eval.
```

### `TypePolicy`

```java
package com.myxcomp.ice.xtree.policy;

public interface TypePolicy {
    boolean hasData(String type);                       // !types-without-data
    boolean isAlsoPersistedAsXmlOnWrite(String type);
    boolean isSentAsXmlToUi(String type);
    boolean isKnown(String type);                       // diagnostics / metrics
}
```

Consulted by `ItemService` (write validation + DB column fan-out) and the response mappers (`/items/get` payload shaping).

### Implementation

`ConfigurableTypePolicy` builds three `Set<String>` fields once at construction from `DataProperties`. The maps are immutable thereafter; thread-safety is structural. No locks, no refresh — type list changes require a restart, which is acceptable given the change frequency and off-hours rollouts.

### Startup validation (hard-fails the bean if violated)

1. **`Folder` must appear in `types-without-data`.** Structural invariant — folders never carry data.
2. **No conflicting overlaps.** A type in `types-without-data` cannot appear in either of the other two lists. (`types-sent-as-xml-to-ui` and `types-also-persisted-as-xml-on-write` *may* coexist for a single type — they govern independent dimensions.)
3. **Trimmed strings only.** Entries with leading/trailing whitespace are rejected (defensive against the "no whitespace in type names" rule).
4. **Sanity log at startup.** Run `SELECT DISTINCT TYPE FROM ITEMTREE`. INFO-log types in DB that fall through to default policy; WARN-log types configured here but absent from DB.

Failures on rules 1–3 prevent bean construction → context fails to start → instance refuses traffic → CI/CD deployment fails. Misconfigured type policy is a data-integrity risk, not something to silently work around.

### Validation on create / update

| Condition | Result |
|---|---|
| `!hasData(type)` && `request.data != null` | 400 `TYPE_CANNOT_HAVE_DATA` |
| `hasData(type)` && `request.data == null` | 400 `DATA_REQUIRED` |
| `request.type` is not in any list (unknown) | Accepted; default policy applied (has-data, JSON-only, JSON to UI); metric `itemtree.policy.unknown_type{type=...}` increments |

### Write fan-out matrix

| `types-without-data`? | `types-also-persisted-as-xml-on-write`? | DB write |
|---|---|---|
| Yes | — | Reject if data present; otherwise insert structural row only (both JSON and XML columns null) |
| No  | No | Persist JSON column only |
| No  | Yes | Persist JSON column **and** XML column (converted from JSON) |

### Read shaping matrix (`/items/get` on a non-folder, non-`types-without-data` node)

ICEX state — `types-sent-as-xml-to-ui` empty — collapses the matrix to three rows:

| DB JSON | DB XML | Response | Side effect |
|---|---|---|---|
| present | * | `dataJson = parsed JSON` | — |
| null | present | `dataJson = converted from XML` | **Async backfill JSON column** (since `!isSentAsXmlToUi(type)`) |
| null | null | `dataJson = null` | — |

For `types-without-data` types, payload columns are not queried at all.

Full matrix (including the currently-empty XML-to-UI path, for future reference):

| `types-sent-as-xml-to-ui`? | DB JSON | DB XML | Response | Side effect |
|---|---|---|---|---|
| No  | present | * | `dataJson` populated | — |
| No  | null | present | `dataJson` (converted from XML) | **Async backfill JSON column** |
| No  | null | null | both null | — |
| Yes | * | present | `dataXml` populated | — |
| Yes | * | null + JSON present | `dataXml` (converted from JSON, no backfill) | — |
| Yes | null | null | both null | — |

### Runtime metrics

| Metric | Type |
|---|---|
| `itemtree.policy.unknown_type{type}` | Counter — type seen in cache or request that isn't in any list |
| `itemtree.policy.validation_rejection{reason}` | Counter — TYPE_CANNOT_HAVE_DATA / DATA_REQUIRED |

---

## 11. XML / JSON Conversion

### Library

Provided in-house library supporting **both directions**: XML → JSON and JSON → XML. Wrapped behind `XmlJsonConverter` interface to allow substitution.

### XML → JSON fallback on read

When `/items/get` encounters a non-folder, non-XML-UI type with `JSON IS NULL AND XML IS NOT NULL`:

1. Convert XML to JSON.
2. Include JSON in response.
3. **Asynchronously backfill** the JSON column.

#### Backfill semantics

- SQL: `UPDATE ITEMTREE SET JSON = :converted WHERE ITEMTREEID = :id AND JSON IS NULL`
- **Silent**: does NOT touch `LASTUPDATE` or `LASTUPDATEUSER`. No history row produced. No Solace event emitted.
- Conditional `WHERE` clause makes racing instances idempotent.
- Fires after the response is sent, via a small single-threaded `TaskExecutor`.
- Batched per request (single transaction, batched JdbcClient updates).
- Failure: logged + metric; next read of the same items will retry.

### JSON → XML on write

For types in `types-also-persisted-as-xml-on-write`:

1. Receive JSON in create / update request.
2. Convert JSON to XML using the library.
3. Persist both columns in the same `INSERT` / `UPDATE`.

The legacy application is responsible for keeping XML and JSON in sync going forward for these types.

---

## 12. Persistence Layer

### Library

Spring **JdbcClient** (Spring 6.1+/Boot 3.2+).

Rejected:
- **JPA/Hibernate** — overhead for single-table, CLOB-heavy access.
- **jOOQ** — extra code generation step not justified for this surface.

### Connection pool

HikariCP. `maximum-pool-size: 10` per instance.

### `ItemTreeRepository` interface

```java
public interface ItemTreeRepository {
    // ── Structural reads ─────────────────────────────────────
    void                 streamAllStructural(Consumer<StructuralRow> rowHandler);
    List<StructuralRow>  findStructuralChangedSince(Instant since);

    // ── Payload reads ────────────────────────────────────────
    List<PayloadRow>     findPayloadByIds(Collection<Long> ids);

    // ── Silent backfill ──────────────────────────────────────
    int                  backfillJsonWhereNull(Collection<JsonBackfillRow> rows);

    // ── Writes ───────────────────────────────────────────────
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

### Row types

```java
public record StructuralRow(
    long itemTreeId, Long parentId, String name, String type,
    Instant lastUpdate, String lastUpdateUser
) {}

public record PayloadRow(
    long itemTreeId, String json, String xml
) {}

public record JsonBackfillRow(long itemTreeId, String json) {}
```

### SQL summary

**Bootstrap / full reload — structural only:**
```sql
SELECT ITEMTREEID, PARENTID, NAME, TYPE, LASTUPDATE, LASTUPDATEUSER
FROM ITEMTREE
```
Streamed via `RowCallbackHandler`, `fetchSize=1000`.

**Delta refresh:**
```sql
SELECT ITEMTREEID, PARENTID, NAME, TYPE, LASTUPDATE, LASTUPDATEUSER
FROM ITEMTREE
WHERE LASTUPDATE > :since
```
Uses the new LASTUPDATE index.

**Payload fetch:**
```sql
SELECT ITEMTREEID, JSON, XML
FROM ITEMTREE
WHERE ITEMTREEID IN (:ids)
```
Chunked at 1000 ids per execution (Oracle IN-list limit).

**Backfill:**
```sql
UPDATE ITEMTREE SET JSON = :json
WHERE ITEMTREEID = :id AND JSON IS NULL
```
Batched.

**Insert:**
```sql
INSERT INTO ITEMTREE
  (ITEMTREEID, PARENTID, NAME, TYPE, JSON, XML, LASTUPDATE, LASTUPDATEUSER)
VALUES
  (ITEMTREE_ID_SQN.NEXTVAL, :parentId, :name, :type,
   :json, :xml, :lastUpdate, :lastUpdateUser)
RETURNING ITEMTREEID INTO :outId
```

**Update JSON (logical update — bumps LASTUPDATE):**
```sql
UPDATE ITEMTREE
   SET JSON = :json, XML = :xml,
       LASTUPDATE = :lastUpdate, LASTUPDATEUSER = :lastUpdateUser
 WHERE ITEMTREEID = :id
```

**Update parent (move):**
```sql
UPDATE ITEMTREE
   SET PARENTID = :newParentId,
       LASTUPDATE = :lastUpdate, LASTUPDATEUSER = :lastUpdateUser
 WHERE ITEMTREEID = :id
```

**Update name (rename):**
```sql
UPDATE ITEMTREE
   SET NAME = :newName,
       LASTUPDATE = :lastUpdate, LASTUPDATEUSER = :lastUpdateUser
 WHERE ITEMTREEID = :id
```

**Cascade delete (single transaction):**
```sql
-- Step 1: collect ids
WITH sub(id) AS (
  SELECT ITEMTREEID FROM ITEMTREE WHERE ITEMTREEID = :rootId
  UNION ALL
  SELECT t.ITEMTREEID FROM ITEMTREE t JOIN sub ON t.PARENTID = sub.id
)
SELECT id FROM sub;

-- Step 2: delete (chunked at 1000)
DELETE FROM ITEMTREE WHERE ITEMTREEID IN (:ids)
```
Returns the full descendant id set for the cascade `DELETE` event payload.

### Transaction policy

- Write operations: `@Transactional` at the service layer.
- Cascade delete: single transaction wrapping CTE + chunked DELETEs.
- Payload reads: non-transactional.

---

## 13. Identity (Two-User Model)

### Headers

| Header | Required | Length |
|---|---|---|
| `X-Ice-User` | Yes | 1–20 |
| `X-Impersonated-User` | No | 1–20 |

### Resolution rules

- **`lastUpdateUser` stamping**: impersonated user if present, else iceUser.
- **Home-folder resolution on `/tree`**: impersonated user if present, else iceUser.
- **Solace events**: carry both `iceUser` and `impersonatedUser`.
- **`/users/{userName}/home-folder`**: `userName` from path is the subject; headers identify the caller.

### `UserContext`

Plain value object built by `UserContextInterceptor` from the headers. Passed explicitly to service methods (recommended) rather than as a request-scoped bean — clearer dependencies, more testable.

---

## 14. Time Handling — UTC Throughout

Instances run in **3 different timezones** against **one database**. Oracle `DATE` has no timezone field. The convention is **UTC for every stored value**, enforced at one place: `TimeMapper`.

### Rules

1. **Internal canonical type:** `java.time.Instant` everywhere (DTOs, cache, events).
2. **Write boundary:** convert `Instant` → UTC `LocalDateTime` via `instant.atOffset(ZoneOffset.UTC).toLocalDateTime()`. The Oracle driver writes the value verbatim.
3. **Read boundary:** `ResultSet.getObject(col, LocalDateTime.class)` returns the value as stored; attach `ZoneOffset.UTC` to construct an `Instant`.
4. **Wire format:** ISO-8601 with `Z` (e.g. `2026-05-13T14:30:00Z`). Jackson serialises `Instant` this way by default.
5. **Delta refresh `:since`:** UTC `LocalDateTime`. Consistent across all instances regardless of JVM timezone.
6. **Defence in depth:** launch all instances with `-Duser.timezone=UTC`.

`TimeMapper` is the **only** class allowed to call the bare conversion methods. All other code uses `Instant`.

---

## 15. Tech Stack

| Concern | Choice |
|---|---|
| JDK | Java 21 |
| Framework | Spring Boot (latest compatible with Java 21) |
| HTTP | Spring MVC on Tomcat (WebFlux deferred until volume demands) |
| Persistence | Spring JdbcClient + HikariCP |
| Messaging | `com.barcap.ice.service.jms` (Spring JMS DMLC + Solace JMS provider) |
| Boilerplate | Lombok |
| Build | Gradle Kotlin DSL (managed externally to this design) |
| Contract | OpenAPI 3.0.3, openapi-generator |
| Observability | Micrometer + Prometheus |
| Tracing | Micrometer Tracing |
| Logging | Logback (plain text for now) |
| Deployment | Unix via CI/CD pipeline (no Kubernetes) |
| Instances | Up to 6, load-balanced |

---

## 16. Module / Package Layout

Single Gradle module.

```
com.myxcomp.ice.xtree
│
├── ItemTreeApplication                       # @SpringBootApplication
│
├── api/                                      # HTTP layer
│   ├── controller/                           # ItemController, TreeController,
│   │                                         # SearchController, UserController
│   ├── advice/                               # GlobalExceptionHandler, ProblemFactory
│   ├── mapper/                               # ItemNodeMapper,
│   │                                         # ItemNodeWithDataMapper
│   └── filter/                               # CacheReadinessFilter,
│                                             # UserContextInterceptor
│
├── service/                                  # ItemService, TreeService, SearchService,
│                                             # HomeFolderService, PathResolver
│
├── cache/                                    # TreeCache, DefaultTreeCache,
│                                             # CachedNode, TreeSnapshot,
│                                             # SnapshotBuilder, CacheReadinessGate
│
├── persistence/                              # ItemTreeRepository,
│   │                                         # JdbcItemTreeRepository,
│   │                                         # StructuralRow, PayloadRow,
│   │                                         # JsonBackfillRow
│   └── rowmapper/
│
├── messaging/                                # EventPublisher, SolaceEventPublisher,
│   │                                         # EventConsumerService, EventDispatcher,
│   │                                         # ConnectionStateTracker,
│   │                                         # ReconnectReconciler, SequenceGenerator
│   └── event/                                # TreeMutationEvent, OperationType,
│       └── payload/                          # Create/Update/Move/Rename/DeletePayload
│
├── bootstrap/                                # TreeCacheBootstrap,
│                                             # SolaceConsumerStarter
│
├── refresh/                                  # RefreshScheduler, RefreshOrchestrator,
│                                             # DeltaReconciler, RefreshResult,
│                                             # RefreshActuatorEndpoint
│
├── conversion/                               # XmlJsonConverter,
│                                             # DefaultXmlJsonConverter
│
├── policy/                                   # TypePolicy, ConfigurableTypePolicy
│
├── config/                                   # SolaceProperties, CacheProperties,
│                                             # DataProperties, ScheduleConfig,
│                                             # SolaceConfig, ObjectMapperConfig,
│                                             # WebConfig
│
└── common/                                   # TreeConstants, InstanceIdProvider,
                                              # TimeMapper, UserContext
```

OpenAPI-generated code lives in `build/generated/openapi/...`, in package `com.myxcomp.ice.xtree.generated`. Hand-written code never imports from `.generated.*` except via `api/mapper/`.

### Test layout

Mirrors main packages, with an additional `e2e/` package for `@SpringBootTest` + Testcontainers-based end-to-end tests. The `JdbcItemTreeRepositoryIT` runs against a real Oracle (Testcontainers) for CLOB handling, IN-list chunking, and the recursive CTE.

### Resources

```
src/main/resources/
├── application.yml
├── application-local.yml
├── application-prod.yml
├── openapi/itemtree-api.yaml
└── logback-spring.xml
```

Flyway is not used. Schema is DBA-owned. Application logs a startup warning if the `LASTUPDATE` index is missing.

---

## 17. Configuration Surface

```yaml
itemtree:
  cache:
    refresh:
      delta-cron:             "0 */30 * * * *"
      delta-overlap-seconds:  60
      full-reload-cron:       "0 0 2 * * MON-FRI"
      bootstrap-retries:      3
      bootstrap-backoff:      "PT1S,PT5S,PT25S"
    name-search:
      default-limit:          null            # no cap
  solace:
    # Broker host/VPN/credentials are configured in the library bean wiring
    # (com.barcap.ice.service.jms.JMSConnectionFactory + RecoveryAgent),
    # owned by deployment config — not here.
    topic:                    "BC/ICE/ITEMTREE"
    concurrent-consumers:     1                # preserves event order
    publisher-max-attempts:   2                # passed to JMSPublisherService
    reconnect:
      short-threshold:        PT1M             # below: rely on scheduled delta
      long-threshold:         PT1H             # above: trigger full reload on reconnect
    health:
      mark-down-after:        PT4H             # outage beyond which HealthIndicator → DOWN
  oracle:
    sequence-name:            "ITEMTREE_ID_SQN"
  data:
    types-without-data:
      - Folder
      - Shortcut
      - Shortcut.Report
      - Shortcut.Filter
      - Shortcut.Filter.Nested
    types-also-persisted-as-xml-on-write:
      - DrillDown.Set
      - Report
      - Filter
      - Details.Column.Collection
      - Numeric.Bucket.Collection
      - Discrete.Bucket.Collection
      - Bucket.Collection
    types-sent-as-xml-to-ui: []

spring:
  datasource:
    hikari:
      maximum-pool-size: 10
  task:
    scheduling:
      pool:
        size: 1

server:
  tomcat:
    threads:
      max: 200

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,itemtree-refresh
```

JVM args (per instance): `-Duser.timezone=UTC` (defence in depth).

---

## 18. Observability

### Health

- **Liveness** — JVM health (always UP unless catastrophic).
- **Readiness** — `cacheReady && consumerSubscribed`.

### Metrics (Micrometer; all tagged with `instanceId`)

#### Cache lifecycle

| Metric | Type |
|---|---|
| `itemtree.cache.size` | Gauge |
| `itemtree.cache.bootstrap.duration` | Timer |
| `itemtree.cache.bootstrap.rows` | Gauge |
| `itemtree.cache.bootstrap.attempts` | Counter |
| `itemtree.cache.refresh.delta.duration` | Timer |
| `itemtree.cache.refresh.delta.rows{change=created\|moved\|renamed\|meta}` | Counter |
| `itemtree.cache.refresh.delta.failure` | Counter |
| `itemtree.cache.refresh.full.duration` | Timer |
| `itemtree.cache.refresh.full.drift{type=created\|deleted\|mutated}` | Counter (**key health signal**) |
| `itemtree.cache.refresh.full.failure` | Counter |
| `itemtree.cache.last_refresh_age_seconds` | Gauge |

#### Messaging

| Metric | Type |
|---|---|
| `itemtree.event.published{op}` | Counter |
| `itemtree.event.consumed{op}` | Counter |
| `itemtree.event.self_dropped` | Counter |
| `itemtree.event.publish.failure` | Counter |
| `itemtree.event.publish.serialization_failure` | Counter |
| `itemtree.event.consume.deserialize.failure` | Counter |
| `itemtree.event.consume.apply.failure` | Counter |
| `itemtree.event.sequence.gap` | Counter |
| `itemtree.solace.connected` | Gauge (0 / 1) |
| `itemtree.solace.outage_seconds` | Gauge (0 when connected) |
| `itemtree.solace.connection_lost_total` | Counter |
| `itemtree.solace.connection_recovered_total` | Counter |
| `itemtree.solace.last_event_age_seconds` | Gauge |
| `itemtree.solace.reconnect_reconcile{type=delta\|full}` | Counter |

#### Other

| Metric | Type |
|---|---|
| `itemtree.delete.cascade.size` | Distribution summary |
| `itemtree.policy.unknown_type{type}` | Counter |
| `itemtree.policy.validation_rejection{reason}` | Counter |
| `itemtree.conversion.xml_to_json.failure{type}` | Counter |
| `itemtree.conversion.json_to_xml.failure{type}` | Counter |
| HikariCP pool metrics | (built-in) |
| Per-endpoint timers | (Spring auto-instrumentation) |

### Logging

Plain text via Logback (for now). Option to switch to a structured JSON encoder later if log aggregation requires it. `traceId` propagated via Micrometer Tracing remains available in plain-text output.

---

## 19. Out of Scope

These were considered and deliberately deferred:

- Concurrent edit of the same item (optimistic locking, version column).
- Per-user visibility filtering — permissions are UI-side.
- Bulk move.
- Real-time push to UI (WebSocket / SSE).
- Search inside JSON payload.
- Search response pagination cap (no default limit).
- Cascade delete safety cap (UI enforces permissions).
- WebFlux (deferred until traffic demands).
- Flyway-managed schema migrations (DBA-owned schema).

---

## 20. Open Items Pending Confirmation

1. **Final segment of Solace topic** — currently `BC/ICE/ITEMTREE`; consider versioning suffix (e.g. `/V1`).
2. **Operational alerting thresholds** for drift counters and consume failures.

### Resolved

- **Generated OpenAPI package name** → `com.myxcomp.ice.xtree.generated`.
- **`UserContext` lifecycle** → passed explicitly as a service method parameter.
- **Logging format** → plain text via Logback (for now).
- **Schema management** → DBA-owned; no Flyway. App logs warning at startup if `LASTUPDATE` index is missing.
- **Messaging library** → `com.barcap.ice.service.jms` (Spring JMS DMLC + Solace JMS provider).
- **`ConnectionRecoveryListener` hook** → being added to `ConnectionExceptionListener` to expose `onConnectionLost` / `onConnectionRecovered` callbacks.
- **`concurrentConsumers`** → 1, for event-order preservation.
- **`publisher max_attempts`** → 2.
- **Listener startup** → non-blocking; readiness is decoupled from broker availability.
- **Type policy model** → 3-list config (no per-type structure needed); `MultiReport` out of scope for ICEX.
- **`DATA_REQUIRED` validation** → create/update on a `hasData` type with null data is rejected.
- **Type literals** → no whitespace; `UDF.Context` (not `UDF Context`) used as the literal, pending final confirmation.

---

## 21. Glossary

| Term | Definition |
|---|---|
| **ICE** | Internal system the user authenticates against (origin of `X-Ice-User`). |
| **Home folder** | Folder whose `NAME` exactly matches the resolved username and whose `TYPE == "Folder"`. Exactly one expected per user. |
| **Skeleton** | Top-2-level folder structure returned by `/tree`. |
| **Drift** | Divergence between cache and DB beyond what the event stream has reconciled. Surfaced by the nightly full-reload drift counters. |
| **Backfill** | Silent write of converted JSON to a row whose JSON column was null on first read. Does not touch `LASTUPDATE` / `LASTUPDATEUSER`. |
| **Self-echo** | A Solace event whose `instanceId` matches the receiving instance. Dropped before reaching the cache. |
| **Snapshot** | A `TreeSnapshot` value object containing the three internal index maps; the unit swapped in by full reload and bootstrap. |
| **Delta refresh** | Periodic incremental cache reconciliation based on `WHERE LASTUPDATE > :since`. Catches creates, moves, renames, metadata updates. Does NOT catch deletes. |
| **Full reload** | Nightly complete cache rebuild from DB. Catches everything including deletes; reports drift since last reload. |

---

*End of design specification.*

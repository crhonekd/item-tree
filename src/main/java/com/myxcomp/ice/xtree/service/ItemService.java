package com.myxcomp.ice.xtree.service;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.TreeCache;
import com.myxcomp.ice.xtree.common.InstanceIdProvider;
import com.myxcomp.ice.xtree.common.TimeMapper;
import com.myxcomp.ice.xtree.common.TreeConstants;
import com.myxcomp.ice.xtree.common.Types;
import com.myxcomp.ice.xtree.common.UserContext;
import com.myxcomp.ice.xtree.conversion.XmlJsonConverter;
import com.myxcomp.ice.xtree.messaging.EventPublisher;
import com.myxcomp.ice.xtree.service.OwnershipChecker;
import com.myxcomp.ice.xtree.messaging.SequenceGenerator;
import com.myxcomp.ice.xtree.messaging.event.OperationType;
import com.myxcomp.ice.xtree.messaging.event.TreeMutationEvent;
import com.myxcomp.ice.xtree.config.CopyProperties;
import com.myxcomp.ice.xtree.messaging.event.payload.CopyPayload;
import com.myxcomp.ice.xtree.messaging.event.payload.CreatePayload;
import com.myxcomp.ice.xtree.messaging.event.payload.DeletePayload;
import com.myxcomp.ice.xtree.messaging.event.payload.EventPayload;
import com.myxcomp.ice.xtree.messaging.event.payload.MovePayload;
import com.myxcomp.ice.xtree.messaging.event.payload.RenamePayload;
import com.myxcomp.ice.xtree.messaging.event.payload.UpdatePayload;
import com.myxcomp.ice.xtree.persistence.ItemTreeFullRow;
import com.myxcomp.ice.xtree.persistence.ItemTreeRepository;
import com.myxcomp.ice.xtree.persistence.JsonBackfillRow;
import com.myxcomp.ice.xtree.persistence.PayloadRow;
import com.myxcomp.ice.xtree.policy.TypePolicy;
import com.myxcomp.ice.xtree.service.exception.CopyTooLargeException;
import com.myxcomp.ice.xtree.service.exception.ErrorCode;
import com.myxcomp.ice.xtree.service.exception.ForbiddenException;
import com.myxcomp.ice.xtree.service.exception.NotFoundException;
import com.myxcomp.ice.xtree.service.exception.ValidationException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class ItemService {

    private static final Logger log = LoggerFactory.getLogger(ItemService.class);

    private final TreeCache cache;
    private final ItemTreeRepository repository;
    private final TypePolicy policy;
    private final XmlJsonConverter converter;
    private final EventPublisher publisher;
    private final TimeMapper timeMapper;
    private final InstanceIdProvider instanceIdProvider;
    private final SequenceGenerator sequenceGenerator;
    private final TaskExecutor backfillExecutor;
    private final MeterRegistry meterRegistry;
    private final CopyProperties copyProperties;
    private final OwnershipChecker ownershipChecker;

    public ItemService(TreeCache cache,
                       ItemTreeRepository repository,
                       TypePolicy policy,
                       XmlJsonConverter converter,
                       EventPublisher publisher,
                       TimeMapper timeMapper,
                       InstanceIdProvider instanceIdProvider,
                       SequenceGenerator sequenceGenerator,
                       @Qualifier("backfillExecutor") TaskExecutor backfillExecutor,
                       MeterRegistry meterRegistry,
                       CopyProperties copyProperties,
                       OwnershipChecker ownershipChecker) {
        this.cache = cache;
        this.repository = repository;
        this.policy = policy;
        this.converter = converter;
        this.publisher = publisher;
        this.timeMapper = timeMapper;
        this.instanceIdProvider = instanceIdProvider;
        this.sequenceGenerator = sequenceGenerator;
        this.backfillExecutor = backfillExecutor;
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        this.copyProperties = Objects.requireNonNull(copyProperties, "copyProperties");
        this.ownershipChecker = Objects.requireNonNull(ownershipChecker, "ownershipChecker");
    }

    /**
     * Creates a new node under {@code parentId}. Order: validate → DB → cache → event.
     *
     * @throws NotFoundException   {@code PARENT_NOT_FOUND} when {@code parentId} is unknown to the cache
     * @throws ValidationException {@code PARENT_NOT_FOLDER} / {@code TYPE_CANNOT_HAVE_DATA} / {@code DATA_REQUIRED}
     */
    @Transactional
    public CachedNode createItem(long parentId, String name, String type, String dataJson,
                                 UserContext userContext) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(userContext, "userContext");

        CachedNode parent = cache.getById(parentId).orElseThrow(() -> new NotFoundException(
                ErrorCode.PARENT_NOT_FOUND, "Parent " + parentId + " not found"));
        if (!Types.isFolder(parent.type())) {
            throw new ValidationException(ErrorCode.PARENT_NOT_FOLDER,
                    "Parent " + parentId + " is not a folder (type=" + parent.type() + ")");
        }

        String effectiveUser = userContext.effectiveUser();
        CachedNode homeFolder = ownershipChecker.requireHomeFolderExists(effectiveUser);
        ownershipChecker.requireOwned(parentId, homeFolder, effectiveUser, "Parent");

        boolean hasData = policy.hasData(type);
        if (!policy.isKnown(type)) {
            meterRegistry.counter("itemtree.policy.unknown_type", "type", type).increment();
        }
        if (!hasData && dataJson != null) {
            meterRegistry.counter("itemtree.policy.validation_rejection",
                    "reason", ErrorCode.TYPE_CANNOT_HAVE_DATA.name()).increment();
            throw new ValidationException(ErrorCode.TYPE_CANNOT_HAVE_DATA,
                    "Type '" + type + "' cannot carry data");
        }
        if (hasData && dataJson == null) {
            meterRegistry.counter("itemtree.policy.validation_rejection",
                    "reason", ErrorCode.DATA_REQUIRED.name()).increment();
            throw new ValidationException(ErrorCode.DATA_REQUIRED,
                    "Type '" + type + "' requires data");
        }

        String xmlOrNull = null;
        if (hasData && policy.isAlsoPersistedAsXmlOnWrite(type)) {
            try {
                xmlOrNull = converter.jsonToXml(dataJson);
            } catch (RuntimeException e) {
                meterRegistry.counter("itemtree.conversion.json_to_xml.failure",
                        "type", type).increment();
                throw e;
            }
        }

        Instant now = timeMapper.now();
        String stampUser = effectiveUser;

        long id = repository.insert(parentId, name, type, dataJson, xmlOrNull, now, stampUser);

        CachedNode node = new CachedNode(id, parentId, name, type, now, stampUser);
        cache.applyCreate(node);

        try {
            publisher.publish(buildEvent(userContext, OperationType.CREATE,
                    new CreatePayload(id, parentId, name, type, now, stampUser), now));
        } catch (RuntimeException e) {
            log.error("EventPublisher threw on {}; event dropped", OperationType.CREATE, e);
        }

        return node;
    }

    /**
     * Cascade-deletes {@code id} and all descendants. Silent no-op if {@code id} is absent
     * from the cache (no auth check, no DB call). Ownership is enforced after the cache probe.
     * Order: cache probe → ownership check → DB cascade → cache.applyDelete → event.
     */
    @Transactional
    public void deleteItem(long id, UserContext userContext) {
        Objects.requireNonNull(userContext, "userContext");

        if (cache.getById(id).isEmpty()) {
            log.info("deleteItem: id={} not present in cache; no-op", id);
            return;
        }

        String effectiveUser = userContext.effectiveUser();
        CachedNode homeFolder = ownershipChecker.requireHomeFolderExists(effectiveUser);
        ownershipChecker.requireOwned(id, homeFolder, effectiveUser, "Item");

        List<Long> deletedIds = repository.cascadeDeleteSubtree(id);
        if (deletedIds.isEmpty()) {
            log.info("deleteItem: id={} present in cache but not DB (drift); no-op", id);
            return;
        }
        meterRegistry.summary("itemtree.delete.cascade.size").record(deletedIds.size());
        cache.applyDelete(new HashSet<>(deletedIds));
        Instant now = timeMapper.now();
        try {
            publisher.publish(buildEvent(userContext, OperationType.DELETE,
                    new DeletePayload(List.copyOf(deletedIds)), now));
        } catch (RuntimeException e) {
            log.error("EventPublisher threw on {}; event dropped", OperationType.DELETE, e);
        }
    }

    /**
     * Renames {@code id} to {@code newName}.
     *
     * @throws NotFoundException {@code ITEM_NOT_FOUND} if {@code id} is unknown
     */
    @Transactional
    public CachedNode renameItem(long id, String newName, UserContext userContext) {
        Objects.requireNonNull(newName, "newName");
        Objects.requireNonNull(userContext, "userContext");

        if (cache.getById(id).isEmpty()) {
            throw new NotFoundException(ErrorCode.ITEM_NOT_FOUND, "Item " + id + " not found");
        }

        String effectiveUser = userContext.effectiveUser();
        CachedNode homeFolder = ownershipChecker.requireHomeFolderExists(effectiveUser);
        ownershipChecker.requireOwned(id, homeFolder, effectiveUser, "Item");

        Instant now = timeMapper.now();
        String stampUser = effectiveUser;

        repository.updateName(id, newName, now, stampUser);
        cache.applyRename(id, newName, now, stampUser);

        try {
            publisher.publish(buildEvent(userContext, OperationType.RENAME,
                    new RenamePayload(id, newName, now, stampUser), now));
        } catch (RuntimeException e) {
            log.error("EventPublisher threw on {}; event dropped", OperationType.RENAME, e);
        }

        return cache.getById(id).orElseThrow(() -> new IllegalStateException(
                "Cache lost id " + id + " after applyRename"));
    }

    /**
     * Moves {@code id} under {@code newParentId}. Validation order:
     * ITEM_NOT_FOUND, MOVE_INTO_DESCENDANT (self), NEW_PARENT_NOT_FOUND, NEW_PARENT_NOT_FOLDER, MOVE_INTO_DESCENDANT (ancestor walk).
     */
    @Transactional
    public CachedNode moveItem(long id, long newParentId, UserContext userContext) {
        Objects.requireNonNull(userContext, "userContext");

        CachedNode item = cache.getById(id).orElseThrow(() -> new NotFoundException(
                ErrorCode.ITEM_NOT_FOUND, "Item " + id + " not found"));

        if (id == newParentId) {
            throw new ValidationException(ErrorCode.MOVE_INTO_DESCENDANT,
                    "Cannot move item into itself (id=" + id + ")");
        }

        CachedNode newParent = cache.getById(newParentId).orElseThrow(() -> new NotFoundException(
                ErrorCode.NEW_PARENT_NOT_FOUND, "New parent " + newParentId + " not found"));

        if (!Types.isFolder(newParent.type())) {
            throw new ValidationException(ErrorCode.NEW_PARENT_NOT_FOLDER,
                    "New parent " + newParentId + " is not a folder (type=" + newParent.type() + ")");
        }

        if (cache.isAncestor(id, newParentId)) {
            throw new ValidationException(ErrorCode.MOVE_INTO_DESCENDANT,
                    "Cannot move id=" + id + " under its own descendant " + newParentId);
        }

        String effectiveUser = userContext.effectiveUser();
        CachedNode homeFolder = ownershipChecker.requireHomeFolderExists(effectiveUser);
        ownershipChecker.requireOwned(id, homeFolder, effectiveUser, "Source");
        ownershipChecker.requireOwned(newParentId, homeFolder, effectiveUser, "New parent");

        Instant now = timeMapper.now();
        String stampUser = effectiveUser;
        long oldParentId = item.parentId();

        repository.updateParent(id, newParentId, now, stampUser);
        cache.applyMove(id, newParentId, now, stampUser);

        try {
            publisher.publish(buildEvent(userContext, OperationType.MOVE,
                    new MovePayload(id, oldParentId, newParentId, now, stampUser), now));
        } catch (RuntimeException e) {
            log.error("EventPublisher threw on {}; event dropped", OperationType.MOVE, e);
        }

        return cache.getById(id).orElseThrow(() -> new IllegalStateException(
                "Cache lost id " + id + " after applyMove"));
    }

    /**
     * Replaces the JSON payload on {@code id}. Cache stores no payload, only the metadata
     * stamp; the JSON is broadcast as metadata-only in the {@code UPDATE} event (§6).
     */
    @Transactional
    public CachedNode updateItemData(long id, String dataJson, UserContext userContext) {
        Objects.requireNonNull(userContext, "userContext");

        CachedNode existing = cache.getById(id).orElseThrow(() -> new NotFoundException(
                ErrorCode.ITEM_NOT_FOUND, "Item " + id + " not found"));

        if (!policy.isKnown(existing.type())) {
            meterRegistry.counter("itemtree.policy.unknown_type", "type", existing.type()).increment();
        }
        if (Types.isFolder(existing.type())) {
            meterRegistry.counter("itemtree.policy.validation_rejection",
                    "reason", ErrorCode.FOLDER_CANNOT_HAVE_DATA.name()).increment();
            throw new ValidationException(ErrorCode.FOLDER_CANNOT_HAVE_DATA,
                    "Folder " + id + " cannot carry data");
        }
        if (!policy.hasData(existing.type())) {
            meterRegistry.counter("itemtree.policy.validation_rejection",
                    "reason", ErrorCode.TYPE_CANNOT_HAVE_DATA.name()).increment();
            throw new ValidationException(ErrorCode.TYPE_CANNOT_HAVE_DATA,
                    "Type '" + existing.type() + "' cannot carry data");
        }
        if (dataJson == null) {
            meterRegistry.counter("itemtree.policy.validation_rejection",
                    "reason", ErrorCode.DATA_REQUIRED.name()).increment();
            throw new ValidationException(ErrorCode.DATA_REQUIRED,
                    "Update of id=" + id + " requires data");
        }

        String effectiveUser = userContext.effectiveUser();
        CachedNode homeFolder = ownershipChecker.requireHomeFolderExists(effectiveUser);
        ownershipChecker.requireOwned(id, homeFolder, effectiveUser, "Item");

        String xmlOrNull = null;
        if (policy.isAlsoPersistedAsXmlOnWrite(existing.type())) {
            try {
                xmlOrNull = converter.jsonToXml(dataJson);
            } catch (RuntimeException e) {
                meterRegistry.counter("itemtree.conversion.json_to_xml.failure",
                        "type", existing.type()).increment();
                throw e;
            }
        }

        Instant now = timeMapper.now();
        String stampUser = effectiveUser;

        repository.updateJson(id, dataJson, xmlOrNull, now, stampUser);
        cache.applyMetadataUpdate(id, now, stampUser);

        try {
            publisher.publish(buildEvent(userContext, OperationType.UPDATE,
                    new UpdatePayload(id, now, stampUser), now));
        } catch (RuntimeException e) {
            log.error("EventPublisher threw on {}; event dropped", OperationType.UPDATE, e);
        }

        return cache.getById(id).orElseThrow(() -> new IllegalStateException(
                "Cache lost id " + id + " after applyMetadataUpdate"));
    }

    /**
     * Bulk fetch by id (POST /items/get). Missing ids are silently omitted. Folders are
     * expanded one level (children carry their own shaped payload). Non-folder data-bearing
     * nodes consult the DB; rows with {@code JSON IS NULL AND XML IS NOT NULL} are converted
     * for the response and queued for async backfill (design §11).
     */
    @Transactional(readOnly = true)
    public List<ItemWithData> getItemsWithData(List<Long> ids) {
        Objects.requireNonNull(ids, "ids");
        if (ids.isEmpty()) return List.of();

        List<CachedNode> requested = new ArrayList<>();
        Map<Long, List<CachedNode>> folderChildren = new LinkedHashMap<>();
        for (Long id : ids) {
            if (id == null) continue;
            Optional<CachedNode> opt = cache.getById(id);
            if (opt.isEmpty()) continue;
            CachedNode n = opt.get();
            requested.add(n);
            if (Types.isFolder(n.type())) {
                folderChildren.put(n.itemTreeId(), cache.getChildren(n.itemTreeId()));
            }
        }

        LinkedHashSet<Long> payloadIds = new LinkedHashSet<>();
        for (CachedNode n : requested) {
            if (!Types.isFolder(n.type()) && policy.hasData(n.type())) {
                payloadIds.add(n.itemTreeId());
            }
        }
        for (List<CachedNode> children : folderChildren.values()) {
            for (CachedNode c : children) {
                if (!Types.isFolder(c.type()) && policy.hasData(c.type())) {
                    payloadIds.add(c.itemTreeId());
                }
            }
        }

        Map<Long, PayloadRow> payloadById = new HashMap<>();
        if (!payloadIds.isEmpty()) {
            for (PayloadRow row : repository.findPayloadByIds(List.copyOf(payloadIds))) {
                payloadById.put(row.itemTreeId(), row);
            }
        }

        List<JsonBackfillRow> backfillBatch = new ArrayList<>();
        List<ItemWithData> out = new ArrayList<>(requested.size());
        for (CachedNode n : requested) {
            if (Types.isFolder(n.type())) {
                List<ItemWithData> shapedChildren = new ArrayList<>();
                for (CachedNode c : folderChildren.get(n.itemTreeId())) {
                    shapedChildren.add(shape(c, payloadById, backfillBatch, null));
                }
                out.add(shape(n, payloadById, backfillBatch, List.copyOf(shapedChildren)));
            } else {
                out.add(shape(n, payloadById, backfillBatch, null));
            }
        }

        if (!backfillBatch.isEmpty()) {
            List<JsonBackfillRow> snapshot = List.copyOf(backfillBatch);
            try {
                backfillExecutor.execute(() -> {
                    try {
                        repository.backfillJsonWhereNull(snapshot);
                    } catch (RuntimeException e) {
                        log.warn("Backfill failed for {} rows: {}", snapshot.size(), e.getMessage());
                    }
                });
            } catch (TaskRejectedException e) {
                log.warn("Backfill queue saturated; dropped {} rows: {}", snapshot.size(), e.getMessage());
            }
        }

        return List.copyOf(out);
    }

    private ItemWithData shape(CachedNode n,
                               Map<Long, PayloadRow> payloadById,
                               List<JsonBackfillRow> backfillBatch,
                               List<ItemWithData> children) {
        if (Types.isFolder(n.type()) || !policy.hasData(n.type())) {
            return new ItemWithData(n.itemTreeId(), n.parentId(), n.name(), n.type(),
                    n.lastUpdate(), n.lastUpdateUser(), null, null, children);
        }

        PayloadRow row = payloadById.get(n.itemTreeId());
        String json = row != null ? row.json() : null;
        String xml  = row != null ? row.xml()  : null;

        if (policy.isSentAsXmlToUi(n.type())) {
            String shippedXml;
            if (xml != null) {
                shippedXml = xml;
            } else if (json != null) {
                try {
                    shippedXml = converter.jsonToXml(json);
                } catch (RuntimeException e) {
                    meterRegistry.counter("itemtree.conversion.json_to_xml.failure",
                            "type", n.type()).increment();
                    throw e;
                }
            } else {
                shippedXml = null;
            }
            return new ItemWithData(n.itemTreeId(), n.parentId(), n.name(), n.type(),
                    n.lastUpdate(), n.lastUpdateUser(), null, shippedXml, children);
        }

        if (json != null) {
            return new ItemWithData(n.itemTreeId(), n.parentId(), n.name(), n.type(),
                    n.lastUpdate(), n.lastUpdateUser(), json, null, children);
        }
        if (xml != null) {
            String convertedJson;
            try {
                convertedJson = converter.xmlToJson(xml);
            } catch (RuntimeException e) {
                meterRegistry.counter("itemtree.conversion.xml_to_json.failure",
                        "type", n.type()).increment();
                throw e;
            }
            backfillBatch.add(new JsonBackfillRow(n.itemTreeId(), convertedJson));
            return new ItemWithData(n.itemTreeId(), n.parentId(), n.name(), n.type(),
                    n.lastUpdate(), n.lastUpdateUser(), convertedJson, null, children);
        }
        return new ItemWithData(n.itemTreeId(), n.parentId(), n.name(), n.type(),
                n.lastUpdate(), n.lastUpdateUser(), null, null, children);
    }

    /**
     * Copies the subtree rooted at {@code sourceId} under {@code destinationFolderId}.
     * Validation order: ITEM_NOT_FOUND, CANNOT_COPY_ROOT, DESTINATION_NOT_FOUND,
     * DESTINATION_NOT_FOLDER, HOME_FOLDER_NOT_FOUND, NOT_IN_USER_FOLDER,
     * COPY_INTO_DESCENDANT, COPY_TOO_LARGE. Write order: DB → cache → event.
     */
    @Transactional
    public List<CachedNode> copyItem(long sourceId, long destinationFolderId, UserContext userContext) {
        Objects.requireNonNull(userContext, "userContext");

        // 1. ITEM_NOT_FOUND
        CachedNode source = cache.getById(sourceId).orElseThrow(() -> {
            recordCopyRejection(ErrorCode.ITEM_NOT_FOUND);
            return new NotFoundException(ErrorCode.ITEM_NOT_FOUND,
                    "Item " + sourceId + " not found");
        });

        // 2. CANNOT_COPY_ROOT
        if (sourceId == TreeConstants.ROOT_ID) {
            recordCopyRejection(ErrorCode.CANNOT_COPY_ROOT);
            throw new ValidationException(ErrorCode.CANNOT_COPY_ROOT,
                    "Cannot copy the root folder");
        }

        // 3. DESTINATION_NOT_FOUND
        CachedNode destination = cache.getById(destinationFolderId).orElseThrow(() -> {
            recordCopyRejection(ErrorCode.DESTINATION_NOT_FOUND);
            return new NotFoundException(ErrorCode.DESTINATION_NOT_FOUND,
                    "Destination folder " + destinationFolderId + " not found");
        });

        // 4. DESTINATION_NOT_FOLDER
        if (!Types.isFolder(destination.type())) {
            recordCopyRejection(ErrorCode.DESTINATION_NOT_FOLDER);
            throw new ValidationException(ErrorCode.DESTINATION_NOT_FOLDER,
                    "Destination " + destinationFolderId + " is not a folder (type="
                            + destination.type() + ")");
        }

        // 5. HOME_FOLDER_NOT_FOUND
        String effectiveUser = userContext.effectiveUser();
        CachedNode homeFolder;
        try {
            homeFolder = ownershipChecker.requireHomeFolderExists(effectiveUser);
        } catch (NotFoundException e) {
            recordCopyRejection(ErrorCode.HOME_FOLDER_NOT_FOUND);
            throw e;
        }

        // 6. NOT_IN_USER_FOLDER
        try {
            ownershipChecker.requireOwned(destinationFolderId, homeFolder, effectiveUser, "Destination");
        } catch (ForbiddenException e) {
            recordCopyRejection(ErrorCode.NOT_IN_USER_FOLDER);
            throw e;
        }

        // 7. COPY_INTO_DESCENDANT
        if (sourceId == destinationFolderId || cache.isAncestor(sourceId, destinationFolderId)) {
            recordCopyRejection(ErrorCode.COPY_INTO_DESCENDANT);
            throw new ValidationException(ErrorCode.COPY_INTO_DESCENDANT,
                    "Cannot copy id=" + sourceId + " into itself or a descendant");
        }

        int cap = copyProperties.maxNodes();

        // 8a. Pre-flight cap check (cache)
        List<CachedNode> cachePreview = cache.getSubtreeFlat(sourceId);
        if (cachePreview.size() > cap) {
            recordCopyRejection(ErrorCode.COPY_TOO_LARGE);
            throw new CopyTooLargeException(
                    "Source subtree has " + cachePreview.size() + " nodes (cache); cap is " + cap);
        }

        // 8b. DB snapshot — authoritative
        List<ItemTreeFullRow> sourceRows = repository.findRowsForCopy(sourceId, cap + 1);
        if (sourceRows.isEmpty()) {
            recordCopyRejection(ErrorCode.ITEM_NOT_FOUND);
            throw new NotFoundException(ErrorCode.ITEM_NOT_FOUND,
                    "Item " + sourceId + " not found in DB");
        }
        if (sourceRows.size() > cap) {
            recordCopyRejection(ErrorCode.COPY_TOO_LARGE);
            throw new CopyTooLargeException(
                    "Source subtree has more than " + cap + " nodes (DB)");
        }

        // Allocate ids and build oldId→newId map
        List<Long> newIds = repository.allocateIds(sourceRows.size());
        Map<Long, Long> idMap = new HashMap<>();
        for (int i = 0; i < sourceRows.size(); i++) {
            idMap.put(sourceRows.get(i).itemTreeId(), newIds.get(i));
        }

        // Compute top-level name (suffix on collision)
        String newRootName = chooseRootName(source.name(), destinationFolderId);

        Instant now = timeMapper.now();
        String stampUser = effectiveUser;

        // Build new rows
        List<ItemTreeFullRow> newRows = new ArrayList<>(sourceRows.size());
        List<CachedNode> newCacheNodes = new ArrayList<>(sourceRows.size());
        for (int i = 0; i < sourceRows.size(); i++) {
            ItemTreeFullRow src = sourceRows.get(i);
            long newId = newIds.get(i);
            long newParent = (i == 0)
                    ? destinationFolderId
                    : idMap.get(src.parentId());
            String name = (i == 0) ? newRootName : src.name();

            newRows.add(new ItemTreeFullRow(
                    newId, newParent, name, src.type(),
                    src.json(), src.xml(), now, stampUser));
            newCacheNodes.add(new CachedNode(
                    newId, newParent, name, src.type(), now, stampUser));
        }

        repository.insertBatch(newRows);
        cache.applyCopy(newCacheNodes);

        // Publish event
        List<CopyPayload.CopiedNode> payloadNodes = new ArrayList<>(newCacheNodes.size());
        for (CachedNode n : newCacheNodes) {
            payloadNodes.add(new CopyPayload.CopiedNode(
                    n.itemTreeId(), n.parentId(), n.name(), n.type(),
                    n.lastUpdate(), n.lastUpdateUser()));
        }
        try {
            publisher.publish(buildEvent(userContext, OperationType.COPY,
                    new CopyPayload(payloadNodes), now));
        } catch (RuntimeException e) {
            log.error("EventPublisher threw on {}; event dropped", OperationType.COPY, e);
        }

        meterRegistry.counter("itemtree.copy.requests", "result", "success").increment();
        meterRegistry.summary("itemtree.copy.subtree.size").record(newCacheNodes.size());

        return newCacheNodes;
    }

    private String chooseRootName(String sourceName, long destinationFolderId) {
        Set<String> sibs = new HashSet<>();
        for (CachedNode c : cache.getChildren(destinationFolderId)) sibs.add(c.name());
        if (!sibs.contains(sourceName)) return sourceName;
        String first = sourceName + " (copy)";
        if (!sibs.contains(first)) return first;
        int n = 2;
        while (sibs.contains(sourceName + " (copy " + n + ")")) {
            n++;
        }
        return sourceName + " (copy " + n + ")";
    }

    private void recordCopyRejection(ErrorCode reason) {
        meterRegistry.counter("itemtree.copy.requests", "result", "rejected").increment();
        meterRegistry.counter("itemtree.copy.rejected", "reason", reason.name()).increment();
    }

    private TreeMutationEvent buildEvent(UserContext ctx, OperationType op,
                                         EventPayload payload, Instant occurredAt) {
        return TreeMutationEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .instanceId(instanceIdProvider.getInstanceId())
                .sequence(sequenceGenerator.next())
                .occurredAt(occurredAt)
                .iceUser(ctx.iceUser())
                .impersonatedUser(ctx.impersonatedUser())
                .operationType(op)
                .payload(payload)
                .build();
    }
}

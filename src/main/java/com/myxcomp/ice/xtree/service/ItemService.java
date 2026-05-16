package com.myxcomp.ice.xtree.service;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.TreeCache;
import com.myxcomp.ice.xtree.common.InstanceIdProvider;
import com.myxcomp.ice.xtree.common.TimeMapper;
import com.myxcomp.ice.xtree.common.Types;
import com.myxcomp.ice.xtree.common.UserContext;
import com.myxcomp.ice.xtree.conversion.XmlJsonConverter;
import com.myxcomp.ice.xtree.messaging.EventPublisher;
import com.myxcomp.ice.xtree.messaging.SequenceGenerator;
import com.myxcomp.ice.xtree.messaging.event.OperationType;
import com.myxcomp.ice.xtree.messaging.event.TreeMutationEvent;
import com.myxcomp.ice.xtree.messaging.event.payload.CreatePayload;
import com.myxcomp.ice.xtree.messaging.event.payload.DeletePayload;
import com.myxcomp.ice.xtree.messaging.event.payload.EventPayload;
import com.myxcomp.ice.xtree.messaging.event.payload.MovePayload;
import com.myxcomp.ice.xtree.messaging.event.payload.RenamePayload;
import com.myxcomp.ice.xtree.persistence.ItemTreeRepository;
import com.myxcomp.ice.xtree.policy.TypePolicy;
import com.myxcomp.ice.xtree.service.exception.ErrorCode;
import com.myxcomp.ice.xtree.service.exception.NotFoundException;
import com.myxcomp.ice.xtree.service.exception.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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

    public ItemService(TreeCache cache,
                       ItemTreeRepository repository,
                       TypePolicy policy,
                       XmlJsonConverter converter,
                       EventPublisher publisher,
                       TimeMapper timeMapper,
                       InstanceIdProvider instanceIdProvider,
                       SequenceGenerator sequenceGenerator,
                       @Qualifier("backfillExecutor") TaskExecutor backfillExecutor) {
        this.cache = cache;
        this.repository = repository;
        this.policy = policy;
        this.converter = converter;
        this.publisher = publisher;
        this.timeMapper = timeMapper;
        this.instanceIdProvider = instanceIdProvider;
        this.sequenceGenerator = sequenceGenerator;
        this.backfillExecutor = backfillExecutor;
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

        boolean hasData = policy.hasData(type);
        if (!hasData && dataJson != null) {
            throw new ValidationException(ErrorCode.TYPE_CANNOT_HAVE_DATA,
                    "Type '" + type + "' cannot carry data");
        }
        if (hasData && dataJson == null) {
            throw new ValidationException(ErrorCode.DATA_REQUIRED,
                    "Type '" + type + "' requires data");
        }

        String xmlOrNull = (hasData && policy.isAlsoPersistedAsXmlOnWrite(type))
                ? converter.jsonToXml(dataJson)
                : null;

        Instant now = timeMapper.now();
        String stampUser = userContext.effectiveUser();

        long id = repository.insert(parentId, name, type, dataJson, xmlOrNull, now, stampUser);

        CachedNode node = new CachedNode(id, parentId, name, type, now, stampUser);
        cache.applyCreate(node);

        publisher.publish(buildEvent(userContext, OperationType.CREATE,
                new CreatePayload(id, parentId, name, type, now, stampUser), now));

        return node;
    }

    /**
     * Cascade-deletes {@code id} and all descendants. Silent no-op if {@code id} is unknown.
     * Order: DB cascade → cache.applyDelete → event.
     */
    @Transactional
    public void deleteItem(long id, UserContext userContext) {
        Objects.requireNonNull(userContext, "userContext");
        List<Long> deletedIds = repository.cascadeDeleteSubtree(id);
        if (deletedIds.isEmpty()) {
            log.info("deleteItem: id={} not present in DB; no-op", id);
            return;
        }
        cache.applyDelete(new HashSet<>(deletedIds));
        Instant now = timeMapper.now();
        publisher.publish(buildEvent(userContext, OperationType.DELETE,
                new DeletePayload(List.copyOf(deletedIds)), now));
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

        Instant now = timeMapper.now();
        String stampUser = userContext.effectiveUser();

        repository.updateName(id, newName, now, stampUser);
        cache.applyRename(id, newName, now, stampUser);

        publisher.publish(buildEvent(userContext, OperationType.RENAME,
                new RenamePayload(id, newName, now, stampUser), now));

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

        Instant now = timeMapper.now();
        String stampUser = userContext.effectiveUser();
        long oldParentId = item.parentId();

        repository.updateParent(id, newParentId, now, stampUser);
        cache.applyMove(id, newParentId, now, stampUser);

        publisher.publish(buildEvent(userContext, OperationType.MOVE,
                new MovePayload(id, oldParentId, newParentId, now, stampUser), now));

        return cache.getById(id).orElseThrow(() -> new IllegalStateException(
                "Cache lost id " + id + " after applyMove"));
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

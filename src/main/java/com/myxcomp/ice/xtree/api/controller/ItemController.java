package com.myxcomp.ice.xtree.api.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxcomp.ice.xtree.api.mapper.ItemNodeMapper;
import com.myxcomp.ice.xtree.api.mapper.ItemNodeWithDataMapper;
import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.common.UserContext;
import com.myxcomp.ice.xtree.generated.api.ItemsApi;
import com.myxcomp.ice.xtree.service.exception.ErrorCode;
import com.myxcomp.ice.xtree.service.exception.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.myxcomp.ice.xtree.generated.model.CreateItemRequest;
import com.myxcomp.ice.xtree.generated.model.GetItemsRequest;
import com.myxcomp.ice.xtree.generated.model.ItemNode;
import com.myxcomp.ice.xtree.generated.model.ItemNodeWithData;
import com.myxcomp.ice.xtree.generated.model.MoveRequest;
import com.myxcomp.ice.xtree.generated.model.RenameRequest;
import com.myxcomp.ice.xtree.generated.model.UpdateDataRequest;
import com.myxcomp.ice.xtree.service.ItemService;
import com.myxcomp.ice.xtree.service.ItemWithData;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class ItemController implements ItemsApi {

    private static final Logger log = LoggerFactory.getLogger(ItemController.class);

    private final ItemService itemService;
    private final ItemNodeMapper itemNodeMapper;
    private final ItemNodeWithDataMapper itemNodeWithDataMapper;
    private final ObjectMapper objectMapper;

    public ItemController(ItemService itemService,
                          ItemNodeMapper itemNodeMapper,
                          ItemNodeWithDataMapper itemNodeWithDataMapper,
                          ObjectMapper objectMapper) {
        this.itemService = itemService;
        this.itemNodeMapper = itemNodeMapper;
        this.itemNodeWithDataMapper = itemNodeWithDataMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public ResponseEntity<ItemNode> createItem(String xIceUser, CreateItemRequest req, String xImpersonatedUser) {
        UserContext ctx = new UserContext(xIceUser, xImpersonatedUser);
        String dataJson = serializeOrNull(req.getData(), "data");
        CachedNode created = itemService.createItem(
                req.getParentId(), req.getName(), req.getType(), dataJson, ctx);
        return ResponseEntity.status(HttpStatus.CREATED).body(itemNodeMapper.toDto(created));
    }

    @Override
    public ResponseEntity<Void> deleteItem(Long id, String xIceUser, String xImpersonatedUser) {
        UserContext ctx = new UserContext(xIceUser, xImpersonatedUser);
        itemService.deleteItem(id, ctx);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<List<ItemNodeWithData>> getItems(String xIceUser, GetItemsRequest req, String xImpersonatedUser) {
        List<ItemWithData> shaped = itemService.getItemsWithData(req.getIds());
        return ResponseEntity.ok(itemNodeWithDataMapper.toDtos(shaped));
    }

    @Override
    public ResponseEntity<ItemNode> moveItem(Long id, String xIceUser, MoveRequest req, String xImpersonatedUser) {
        UserContext ctx = new UserContext(xIceUser, xImpersonatedUser);
        CachedNode moved = itemService.moveItem(id, req.getNewParentId(), ctx);
        return ResponseEntity.ok(itemNodeMapper.toDto(moved));
    }

    @Override
    public ResponseEntity<ItemNode> renameItem(Long id, String xIceUser, RenameRequest req, String xImpersonatedUser) {
        UserContext ctx = new UserContext(xIceUser, xImpersonatedUser);
        CachedNode renamed = itemService.renameItem(id, req.getNewName(), ctx);
        return ResponseEntity.ok(itemNodeMapper.toDto(renamed));
    }

    @Override
    public ResponseEntity<ItemNode> updateItemData(Long id, String xIceUser, UpdateDataRequest req, String xImpersonatedUser) {
        UserContext ctx = new UserContext(xIceUser, xImpersonatedUser);
        String dataJson = serializeOrNull(req.getData(), "data");
        CachedNode updated = itemService.updateItemData(id, dataJson, ctx);
        return ResponseEntity.ok(itemNodeMapper.toDto(updated));
    }

    private String serializeOrNull(Map<String, Object> map, String fieldName) {
        if (map == null) return null;
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialise request field '{}'", fieldName, e);
            throw new ValidationException(ErrorCode.DATA_NOT_SERIALISABLE,
                    "Request 'data' field could not be serialised");
        }
    }
}

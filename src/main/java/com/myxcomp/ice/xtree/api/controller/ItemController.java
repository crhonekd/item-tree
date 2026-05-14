package com.myxcomp.ice.xtree.api.controller;

import com.myxcomp.ice.xtree.generated.api.ItemsApi;
import com.myxcomp.ice.xtree.generated.model.CreateItemRequest;
import com.myxcomp.ice.xtree.generated.model.GetItemsRequest;
import com.myxcomp.ice.xtree.generated.model.ItemNode;
import com.myxcomp.ice.xtree.generated.model.ItemNodeWithData;
import com.myxcomp.ice.xtree.generated.model.MoveRequest;
import com.myxcomp.ice.xtree.generated.model.RenameRequest;
import com.myxcomp.ice.xtree.generated.model.UpdateDataRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class ItemController implements ItemsApi {

    @Override
    public ResponseEntity<ItemNode> createItem(String xIceUser, CreateItemRequest createItemRequest, String xImpersonatedUser) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ResponseEntity<Void> deleteItem(Long id, String xIceUser, String xImpersonatedUser) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ResponseEntity<List<ItemNodeWithData>> getItems(String xIceUser, GetItemsRequest getItemsRequest, String xImpersonatedUser) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ResponseEntity<ItemNode> moveItem(Long id, String xIceUser, MoveRequest moveRequest, String xImpersonatedUser) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ResponseEntity<ItemNode> renameItem(Long id, String xIceUser, RenameRequest renameRequest, String xImpersonatedUser) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ResponseEntity<ItemNode> updateItemData(Long id, String xIceUser, UpdateDataRequest updateDataRequest, String xImpersonatedUser) {
        throw new UnsupportedOperationException();
    }
}

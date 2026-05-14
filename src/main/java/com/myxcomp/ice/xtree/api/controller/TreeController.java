package com.myxcomp.ice.xtree.api.controller;

import com.myxcomp.ice.xtree.generated.api.TreeApi;
import com.myxcomp.ice.xtree.generated.model.ItemNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class TreeController implements TreeApi {

    @Override
    public ResponseEntity<List<ItemNode>> getSubtree(Long rootId, String xIceUser, String xImpersonatedUser) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ResponseEntity<List<ItemNode>> getTree(String xIceUser, String xImpersonatedUser) {
        throw new UnsupportedOperationException();
    }
}

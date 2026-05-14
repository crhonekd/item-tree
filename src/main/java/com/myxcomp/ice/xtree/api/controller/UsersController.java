package com.myxcomp.ice.xtree.api.controller;

import com.myxcomp.ice.xtree.generated.api.UsersApi;
import com.myxcomp.ice.xtree.generated.model.ItemNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UsersController implements UsersApi {

    @Override
    public ResponseEntity<ItemNode> getHomeFolder(String userName, String xIceUser, String xImpersonatedUser) {
        throw new UnsupportedOperationException();
    }
}

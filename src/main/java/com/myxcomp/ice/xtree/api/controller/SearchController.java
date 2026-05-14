package com.myxcomp.ice.xtree.api.controller;

import com.myxcomp.ice.xtree.generated.api.SearchApi;
import com.myxcomp.ice.xtree.generated.model.SearchHit;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class SearchController implements SearchApi {

    @Override
    public ResponseEntity<List<SearchHit>> search(String xIceUser, String xImpersonatedUser, Long id, String name, Integer limit) {
        throw new UnsupportedOperationException();
    }
}

package com.myxcomp.ice.xtree.api.controller;

import com.myxcomp.ice.xtree.api.mapper.SearchHitMapper;
import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.generated.api.SearchApi;
import com.myxcomp.ice.xtree.generated.model.SearchHit;
import com.myxcomp.ice.xtree.service.SearchService;
import com.myxcomp.ice.xtree.service.exception.ErrorCode;
import com.myxcomp.ice.xtree.service.exception.ValidationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

@RestController
public class SearchController implements SearchApi {

    private final SearchService searchService;
    private final SearchHitMapper searchHitMapper;

    public SearchController(SearchService searchService, SearchHitMapper searchHitMapper) {
        this.searchService = searchService;
        this.searchHitMapper = searchHitMapper;
    }

    @Override
    public ResponseEntity<List<SearchHit>> search(String xIceUser, String xImpersonatedUser,
                                                  Long id, String name, Integer limit) {
        boolean hasId = id != null;
        boolean hasName = name != null && !name.isEmpty();
        if (hasId == hasName) {
            throw new ValidationException(ErrorCode.INVALID_SEARCH_PARAMS,
                    "Search requires exactly one of 'id' or 'name'");
        }
        if (hasId) {
            Optional<CachedNode> found = searchService.searchById(id);
            return ResponseEntity.ok(found.map(searchHitMapper::toDto)
                    .map(List::of).orElseGet(List::of));
        }
        OptionalInt limitOpt = limit != null ? OptionalInt.of(limit) : OptionalInt.empty();
        List<CachedNode> hits = searchService.searchByName(name, limitOpt);
        return ResponseEntity.ok(searchHitMapper.toDtos(hits));
    }
}

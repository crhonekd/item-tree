package com.myxcomp.ice.xtree.service;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.TreeCache;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

@Service
public class SearchService {

    private final TreeCache cache;

    public SearchService(TreeCache cache) {
        this.cache = cache;
    }

    public Optional<CachedNode> searchById(long id) {
        return cache.searchById(id);
    }

    public List<CachedNode> searchByName(String needle, OptionalInt limit) {
        Objects.requireNonNull(needle, "needle");
        Objects.requireNonNull(limit, "limit");
        return cache.searchByName(needle, limit);
    }
}

package com.myxcomp.ice.xtree.service;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.TreeCache;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

@Service
public class SearchService {

    private final TreeCache cache;
    private final PathResolver pathResolver;

    public SearchService(TreeCache cache, PathResolver pathResolver) {
        this.cache = cache;
        this.pathResolver = pathResolver;
    }

    /**
     * Numeric-or-name search. If {@code q} parses as a Long and an item with
     * that id is cached, returns that single item. Otherwise (parse fails or
     * id not present) returns a case-insensitive substring match on name.
     * Blank / null {@code q} returns an empty list without touching the cache.
     * Each hit is paired with its lazily-resolved path via a single
     * memoised {@link PathResolver#pathsOf} call.
     */
    public List<SearchHitView> search(String q, OptionalInt limit) {
        Objects.requireNonNull(limit, "limit");
        if (q == null) {
            return List.of();
        }
        String trimmed = q.trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }

        List<CachedNode> hits;
        Long parsed = tryParseLong(trimmed);
        if (parsed != null) {
            Optional<CachedNode> byId = cache.searchById(parsed);
            if (byId.isPresent()) {
                hits = List.of(byId.get());
            } else {
                hits = cache.searchByName(trimmed, limit);
            }
        } else {
            hits = cache.searchByName(trimmed, limit);
        }

        if (hits.isEmpty()) return List.of();
        List<Long> ids = hits.stream().map(CachedNode::itemTreeId).toList();
        Map<Long, String> paths = pathResolver.pathsOf(ids);
        Map<Long, List<CachedNode>> ancestors = pathResolver.ancestorsOf(ids);
        List<SearchHitView> out = new ArrayList<>(hits.size());
        for (CachedNode n : hits) {
            out.add(new SearchHitView(
                    n,
                    paths.getOrDefault(n.itemTreeId(), ""),
                    ancestors.getOrDefault(n.itemTreeId(), List.of())));
        }
        return List.copyOf(out);
    }

    private static Long tryParseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

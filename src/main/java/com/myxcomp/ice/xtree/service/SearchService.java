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

    /**
     * Numeric-or-name search. If {@code q} parses as a Long and an item with
     * that id is cached, returns that single item. Otherwise (parse fails or
     * id not present) returns a case-insensitive substring match on name.
     * Blank / null {@code q} returns an empty list without touching the cache.
     */
    public List<CachedNode> search(String q, OptionalInt limit) {
        Objects.requireNonNull(limit, "limit");
        if (q == null) {
            return List.of();
        }
        String trimmed = q.trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        Long parsed = tryParseLong(trimmed);
        if (parsed != null) {
            Optional<CachedNode> byId = cache.searchById(parsed);
            if (byId.isPresent()) {
                return List.of(byId.get());
            }
        }
        return cache.searchByName(trimmed, limit);
    }

    private static Long tryParseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

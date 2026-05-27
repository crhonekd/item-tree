package com.myxcomp.ice.xtree.service;

import com.myxcomp.ice.xtree.cache.CachedNode;

/**
 * Service-layer pairing of a search hit and its lazily-resolved path. Mirrors the
 * {@link TreeNodeView} pattern: keeps the cache node intact, attaches a path computed
 * by {@link PathResolver} at response time, and lets {@link
 * com.myxcomp.ice.xtree.api.mapper.SearchHitMapper} project to the generated DTO.
 */
public record SearchHitView(CachedNode node, String path) {}

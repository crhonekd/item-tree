package com.myxcomp.ice.xtree.service;

import com.myxcomp.ice.xtree.cache.CachedNode;

import java.util.List;

/**
 * Service-layer pairing of a search hit, its lazily-resolved path, and its root&rarr;parent
 * ancestor chain (exclusive of the hit). Lets {@link
 * com.myxcomp.ice.xtree.api.mapper.SearchHitMapper} project to the generated DTO so the UI can
 * embed the hit in the tree without extra round-trips.
 */
public record SearchHitView(CachedNode node, String path, List<CachedNode> ancestors) {}

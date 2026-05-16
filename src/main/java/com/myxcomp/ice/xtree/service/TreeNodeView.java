package com.myxcomp.ice.xtree.service;

import com.myxcomp.ice.xtree.cache.CachedNode;

/**
 * Service-layer pairing of a node and its lazily-resolved path used by {@code /tree}
 * and {@code /tree/{rootId}/subtree}. Phase 8 mappers will project this to the
 * generated {@code ItemNode} DTO with the {@code path} field populated.
 */
public record TreeNodeView(CachedNode node, String path) {}

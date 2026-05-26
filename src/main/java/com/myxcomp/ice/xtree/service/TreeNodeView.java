package com.myxcomp.ice.xtree.service;

import com.myxcomp.ice.xtree.cache.CachedNode;

/**
 * Service-layer pairing of a node and its lazily-resolved path used by {@code /tree},
 * {@code /tree/{rootId}/subtree} (root + immediate children), and
 * {@code /tree/{rootId}/subtree-full} (root + all descendants). Phase 8 mappers
 * project this to the generated {@code ItemNode} DTO with the {@code path} field populated.
 */
public record TreeNodeView(CachedNode node, String path) {}

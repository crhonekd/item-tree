package com.myxcomp.ice.xtree.service;

import java.time.Instant;
import java.util.List;

/**
 * Service-layer shape for {@code POST /items/get} response items.
 * For folder nodes: {@code dataJson} and {@code dataXml} are null, and {@code children} is
 * a non-null list populated one level deep (each child's {@code children} is null, because
 * child-of-folder nodes are never themselves expanded).
 * For non-folder, data-bearing nodes: at most one of {@code dataJson}/{@code dataXml} is
 * populated, and {@code children} is {@code null} (never an empty list).
 * Callers must use {@code children() == null} to distinguish non-folder nodes from
 * empty-folder nodes.
 */
public record ItemWithData(
        long itemTreeId,
        Long parentId,
        String name,
        String type,
        Instant lastUpdate,
        String lastUpdateUser,
        String dataJson,
        String dataXml,
        List<ItemWithData> children
) {}

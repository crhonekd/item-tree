package com.myxcomp.ice.xtree.service;

import java.time.Instant;
import java.util.List;

/**
 * Service-layer shape for {@code POST /items/get} response items.
 * For folder nodes: {@code dataJson} and {@code dataXml} are null, and {@code children} is
 * populated one level deep (each child's {@code children} is empty list).
 * For non-folder, data-bearing nodes: at most one of {@code dataJson}/{@code dataXml} is
 * populated, and {@code children} is empty list.
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

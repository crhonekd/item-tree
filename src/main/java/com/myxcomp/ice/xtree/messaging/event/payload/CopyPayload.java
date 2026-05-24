package com.myxcomp.ice.xtree.messaging.event.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;

/**
 * COPY event payload (design §6). Sent by the originating instance after a
 * successful copy. {@code newNodes} is BFS-ordered (new root first, then
 * depth-by-depth) so peers can apply via
 * {@link com.myxcomp.ice.xtree.cache.TreeCache#applyCopy} under one write lock.
 *
 * <p>JSON/XML payloads are not broadcast (design §6).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CopyPayload(List<CopiedNode> newNodes) implements EventPayload {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CopiedNode(
            long itemTreeId,
            Long parentId,
            String name,
            String type,
            Instant lastUpdate,
            String lastUpdateUser
    ) {}
}

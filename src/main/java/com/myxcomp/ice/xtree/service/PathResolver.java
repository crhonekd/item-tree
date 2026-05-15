package com.myxcomp.ice.xtree.service;

import java.util.Collection;
import java.util.Map;

/**
 * Lazily computes root-anchored, slash-separated paths for cache nodes (e.g. {@code "root/Users/testuser1"}).
 *
 * <p>Per design §9, paths are not stored on {@link com.myxcomp.ice.xtree.cache.CachedNode} — they are
 * recomputed from the parent chain at response-time on the {@code /tree} and {@code /tree/{rootId}/subtree}
 * endpoints. Walks are bounded; cycles and missing ancestors degrade to a partial path with a WARN log
 * rather than throwing.
 *
 * <p>Behaviour for unknown / orphan inputs:
 * <ul>
 *   <li>If the input id is not in the cache, {@link #pathOf(long)} returns the empty string and
 *       {@link #pathsOf(Collection)} maps it to the empty string.</li>
 *   <li>If a parent in the chain disappears mid-walk, the partial path collected so far (without the
 *       missing root prefix) is returned and a WARN line is logged.</li>
 *   <li>If the walk reaches an internal depth cap (suspected cycle), the partial path is returned
 *       and a WARN line is logged.</li>
 * </ul>
 */
public interface PathResolver {

    /** Returns the path for {@code itemTreeId}. See class Javadoc for partial-result semantics. */
    String pathOf(long itemTreeId);

    /**
     * Returns the path for each id in {@code ids}. Implementations memoise ancestor walks within a
     * single call so that a shared ancestor chain is walked once regardless of how many input ids
     * share it. Each input id appears as a key in the returned map; duplicate ids are collapsed.
     */
    Map<Long, String> pathsOf(Collection<Long> ids);
}

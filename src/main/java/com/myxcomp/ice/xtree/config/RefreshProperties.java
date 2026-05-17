package com.myxcomp.ice.xtree.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Refresh / bootstrap tuning knobs (design §17 → {@code itemtree.cache.refresh.*}).
 *
 * @param deltaCron              Spring 6-field cron — when to fire {@code runDelta}.
 * @param deltaOverlapSeconds    {@code since} marker is decremented by this many seconds before
 *                               each delta query, covering clock-skew between app and DB.
 * @param fullReloadCron         Spring 6-field cron — when to fire {@code runFullReload}.
 * @param bootstrapRetries       Total attempts the bootstrap will make before giving up.
 *                               Must be {@code >= 1}.
 * @param bootstrapBackoff       Sleeps between successive attempts. List size must be at least
 *                               {@code bootstrapRetries - 1}; entries beyond that are ignored.
 */
@ConfigurationProperties("itemtree.cache.refresh")
public record RefreshProperties(
        String deltaCron,
        int deltaOverlapSeconds,
        String fullReloadCron,
        int bootstrapRetries,
        List<Duration> bootstrapBackoff
) {
    public RefreshProperties {
        bootstrapBackoff = bootstrapBackoff == null ? List.of() : List.copyOf(bootstrapBackoff);
    }
}

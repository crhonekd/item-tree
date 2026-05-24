package com.myxcomp.ice.xtree.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for the copy-item operation.
 *
 * @param maxNodes hard cap on subtree size per copy request; requests exceeding
 *                 this fail with COPY_TOO_LARGE (HTTP 413)
 */
@ConfigurationProperties("itemtree.copy")
public record CopyProperties(@DefaultValue("100") int maxNodes) {

    @PostConstruct
    void validate() {
        if (maxNodes < 1) {
            throw new IllegalStateException(
                    "itemtree.copy.max-nodes must be >= 1, got " + maxNodes);
        }
    }
}

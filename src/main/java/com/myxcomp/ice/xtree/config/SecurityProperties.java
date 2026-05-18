package com.myxcomp.ice.xtree.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties("itemtree.security")
public record SecurityProperties(List<String> trustedCidrs) {
    public SecurityProperties {
        if (trustedCidrs == null) trustedCidrs = List.of();
    }
}

package com.myxcomp.ice.xtree.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Solace JMS knobs (design §17 → {@code itemtree.solace.*}).
 *
 * @param topic JMS topic name. Drives both publisher destination and consumer subscription.
 */
@Validated
@ConfigurationProperties("itemtree.solace")
public record SolaceProperties(
        @NotBlank String topic
) {}

package com.myxcomp.ice.xtree.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Solace JMS knobs (design §17 → {@code itemtree.solace.*}).
 *
 * @param topic     JMS topic name. Drives both publisher destination and consumer subscription.
 * @param reconnect Thresholds that classify a broker outage (Phase 11).
 * @param health    Threshold beyond which {@code MessagingHealthIndicator} flips DOWN.
 */
@Validated
@ConfigurationProperties("itemtree.solace")
public record SolaceProperties(
        @NotBlank String topic,
        @NotNull @Valid Reconnect reconnect,
        @NotNull @Valid Health health
) {

    /**
     * @param shortThreshold Outage below this duration is a no-op (scheduled delta covers it).
     * @param longThreshold  Outage at or above this duration triggers a full reload on reconnect;
     *                       outage below it (but at or above {@code shortThreshold}) triggers a delta.
     */
    public record Reconnect(
            @NotNull Duration shortThreshold,
            @NotNull Duration longThreshold
    ) {}

    /**
     * @param markDownAfter Outage at or above this duration flips the messaging health indicator DOWN.
     */
    public record Health(
            @NotNull Duration markDownAfter
    ) {}
}

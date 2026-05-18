package com.myxcomp.ice.xtree.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SolacePropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(EnableConfig.class);

    @EnableConfigurationProperties(SolaceProperties.class)
    static class EnableConfig {}

    @Test
    void bindsTopicReconnectAndHealthFromYaml() {
        contextRunner
                .withPropertyValues(
                        "itemtree.solace.topic=BC/ICE/ITEMTREE",
                        "itemtree.solace.reconnect.short-threshold=PT1M",
                        "itemtree.solace.reconnect.long-threshold=PT1H",
                        "itemtree.solace.health.mark-down-after=PT4H")
                .run(ctx -> {
                    SolaceProperties props = ctx.getBean(SolaceProperties.class);
                    assertThat(props.topic()).isEqualTo("BC/ICE/ITEMTREE");
                    assertThat(props.reconnect().shortThreshold()).isEqualTo(Duration.ofMinutes(1));
                    assertThat(props.reconnect().longThreshold()).isEqualTo(Duration.ofHours(1));
                    assertThat(props.health().markDownAfter()).isEqualTo(Duration.ofHours(4));
                });
    }
}

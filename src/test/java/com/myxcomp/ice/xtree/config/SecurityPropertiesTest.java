package com.myxcomp.ice.xtree.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(EnableConfig.class);

    @org.springframework.boot.context.properties.EnableConfigurationProperties(SecurityProperties.class)
    static class EnableConfig {}

    @Test
    void bindsTrustedCidrsFromProperties() {
        contextRunner
                .withPropertyValues(
                        "itemtree.security.trusted-cidrs[0]=127.0.0.1/32",
                        "itemtree.security.trusted-cidrs[1]=::1/128"
                )
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    SecurityProperties props = ctx.getBean(SecurityProperties.class);
                    assertThat(props.trustedCidrs()).containsExactly("127.0.0.1/32", "::1/128");
                });
    }

    @Test
    void emptyTrustedCidrsWhenNoneConfigured() {
        contextRunner
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    SecurityProperties props = ctx.getBean(SecurityProperties.class);
                    assertThat(props.trustedCidrs()).isEmpty();
                });
    }
}

package com.myxcomp.ice.xtree.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class CopyPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EnableProps.class);

    @Test
    void defaultMaxNodesIs100() {
        runner.run(ctx -> assertThat(ctx.getBean(CopyProperties.class).maxNodes()).isEqualTo(100));
    }

    @Test
    void explicitValueOverridesDefault() {
        runner.withPropertyValues("itemtree.copy.max-nodes=250")
              .run(ctx -> assertThat(ctx.getBean(CopyProperties.class).maxNodes()).isEqualTo(250));
    }

    @Test
    void zeroIsRejectedAtStartup() {
        runner.withPropertyValues("itemtree.copy.max-nodes=0")
              .run(ctx -> {
                  assertThat(ctx).hasFailed();
                  var throwable = ctx.getStartupFailure();
                  var message = throwable.toString() + " " + (throwable.getCause() != null ? throwable.getCause().toString() : "");
                  assertThat(message).contains("itemtree.copy.max-nodes must be >= 1");
              });
    }

    @Test
    void negativeIsRejectedAtStartup() {
        runner.withPropertyValues("itemtree.copy.max-nodes=-5")
              .run(ctx -> {
                  assertThat(ctx).hasFailed();
                  var throwable = ctx.getStartupFailure();
                  var message = throwable.toString() + " " + (throwable.getCause() != null ? throwable.getCause().toString() : "");
                  assertThat(message).contains("itemtree.copy.max-nodes must be >= 1");
              });
    }

    @Configuration
    @EnableConfigurationProperties(CopyProperties.class)
    static class EnableProps {}
}

package com.myxcomp.ice.xtree.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = RefreshPropertiesTest.Config.class)
@EnableConfigurationProperties(RefreshProperties.class)
@TestPropertySource(properties = {
        "itemtree.cache.refresh.delta-cron=0 */30 * * * *",
        "itemtree.cache.refresh.delta-overlap-seconds=60",
        "itemtree.cache.refresh.full-reload-cron=0 0 2 * * MON-FRI",
        "itemtree.cache.refresh.bootstrap-retries=3",
        "itemtree.cache.refresh.bootstrap-backoff=PT1S,PT5S,PT25S"
})
class RefreshPropertiesTest {

    @org.springframework.boot.SpringBootConfiguration
    static class Config {}

    @Autowired
    private RefreshProperties props;

    @Test
    void allFieldsBindFromProperties() {
        assertThat(props.deltaCron()).isEqualTo("0 */30 * * * *");
        assertThat(props.deltaOverlapSeconds()).isEqualTo(60);
        assertThat(props.fullReloadCron()).isEqualTo("0 0 2 * * MON-FRI");
        assertThat(props.bootstrapRetries()).isEqualTo(3);
        assertThat(props.bootstrapBackoff())
                .containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(25));
    }

    @Test
    void absentBootstrapBackoffDefaultsToEmptyList() {
        // Tests the compact constructor null-guard — no Spring context needed
        RefreshProperties p = new RefreshProperties("0 * * * * *", 60, "0 0 2 * * *", 3, null);
        assertThat(p.bootstrapBackoff()).isEmpty();
    }

    @Test
    void bootstrapRetriesZeroFailsValidation() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(ValidationConfig.class)
                .withPropertyValues(
                        "itemtree.cache.refresh.delta-cron=0 */30 * * * *",
                        "itemtree.cache.refresh.delta-overlap-seconds=60",
                        "itemtree.cache.refresh.full-reload-cron=0 0 2 * * MON-FRI",
                        "itemtree.cache.refresh.bootstrap-retries=0",
                        "itemtree.cache.refresh.bootstrap-backoff=PT1S"
                )
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    // Walk the cause chain — the validation detail is nested inside
                    Throwable t = ctx.getStartupFailure();
                    StringBuilder allMessages = new StringBuilder();
                    while (t != null) {
                        if (t.getMessage() != null) allMessages.append(t.getMessage()).append('\n');
                        t = t.getCause();
                    }
                    String combined = allMessages.toString();
                    assertThat(combined)
                            .as("Expected field name in validation failure")
                            .containsAnyOf("bootstrapRetries", "bootstrap-retries");
                    assertThat(combined)
                            .as("Expected constraint phrase in validation failure")
                            .containsAnyOf("must be", "constraint", "ConstraintViolation");
                });
    }

    @org.springframework.boot.SpringBootConfiguration
    @EnableConfigurationProperties(RefreshProperties.class)
    static class ValidationConfig {}
}

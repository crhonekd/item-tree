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
}

package com.myxcomp.ice.xtree.config;

import com.myxcomp.ice.xtree.common.InstanceIdProvider;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MicrometerConfigTest {

    @Test
    void customizerAddsInstanceIdAsCommonTag() {
        InstanceIdProvider provider = mock(InstanceIdProvider.class);
        when(provider.getInstanceId()).thenReturn("abc-123");

        MeterRegistryCustomizer<MeterRegistry> customizer =
                new MicrometerConfig().commonTagsCustomizer(provider);

        MeterRegistry registry = new SimpleMeterRegistry();
        customizer.customize(registry);

        registry.counter("test.metric").increment();
        assertThat(registry.find("test.metric").tag("instanceId", "abc-123").counter())
                .isNotNull();
    }

    @Test
    void customizerCustomizesAnyMeterRegistry() {
        InstanceIdProvider provider = mock(InstanceIdProvider.class);
        when(provider.getInstanceId()).thenReturn("xyz-789");

        MeterRegistry registry = new SimpleMeterRegistry();
        new MicrometerConfig().commonTagsCustomizer(provider).customize(registry);

        registry.timer("another.metric").record(java.time.Duration.ofMillis(1));
        assertThat(registry.find("another.metric").tag("instanceId", "xyz-789").timer())
                .isNotNull();
    }
}

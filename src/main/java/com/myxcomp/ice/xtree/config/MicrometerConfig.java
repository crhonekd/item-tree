package com.myxcomp.ice.xtree.config;

import com.myxcomp.ice.xtree.common.InstanceIdProvider;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Objects;

/**
 * Applies the {@code instanceId} Micrometer common tag (design §18) to every meter
 * created in this application. The customizer runs before any meter is registered.
 */
@Configuration
public class MicrometerConfig {

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> commonTagsCustomizer(InstanceIdProvider provider) {
        Objects.requireNonNull(provider, "provider");
        String instanceId = provider.getInstanceId();
        return registry -> registry.config().commonTags("instanceId", instanceId);
    }
}

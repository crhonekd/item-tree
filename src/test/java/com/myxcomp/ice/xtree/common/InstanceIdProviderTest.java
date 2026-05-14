package com.myxcomp.ice.xtree.common;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class InstanceIdProviderTest {

    @Test
    void instanceId_is_stable_across_repeated_calls() {
        InstanceIdProvider provider = new InstanceIdProvider();
        String first = provider.getInstanceId();
        String second = provider.getInstanceId();
        assertThat(first).isEqualTo(second);
    }

    @Test
    void instanceId_is_a_valid_uuid() {
        InstanceIdProvider provider = new InstanceIdProvider();
        assertThatCode(() -> UUID.fromString(provider.getInstanceId()))
                .doesNotThrowAnyException();
    }

    @Test
    void two_separate_providers_have_different_ids() {
        // Models two JVM instances each creating their own InstanceIdProvider
        InstanceIdProvider p1 = new InstanceIdProvider();
        InstanceIdProvider p2 = new InstanceIdProvider();
        assertThat(p1.getInstanceId()).isNotEqualTo(p2.getInstanceId());
    }
}

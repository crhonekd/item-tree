package com.myxcomp.ice.xtree.e2e;

import com.myxcomp.ice.xtree.common.TimeMapper;
import com.myxcomp.ice.xtree.messaging.dev.InMemoryEventBus;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;

import static org.mockito.Mockito.when;

/**
 * Test-only Spring configuration imported into each E2E-test context. Replaces two beans:
 * <ul>
 *   <li>{@link InMemoryEventBus} → shared singleton via {@link SharedBusHolder}</li>
 *   <li>{@link TimeMapper} → Mockito mock, pre-stubbed to a fixed {@code Instant}</li>
 * </ul>
 * Requires {@code spring.main.allow-bean-definition-overriding=true}.
 */
@TestConfiguration
public class E2ETestConfig {

    public static final Instant DEFAULT_TEST_INSTANT = Instant.parse("2026-05-18T10:00:00Z");

    @Bean
    @Primary
    public InMemoryEventBus sharedInMemoryEventBus() {
        return SharedBusHolder.get();
    }

    @Bean
    @Primary
    public TimeMapper mockTimeMapper() {
        TimeMapper mock = Mockito.mock(TimeMapper.class);
        when(mock.now()).thenReturn(DEFAULT_TEST_INSTANT);
        when(mock.toOffsetDateTime(Mockito.any())).thenCallRealMethod();
        return mock;
    }
}

package com.myxcomp.ice.xtree.e2e;

import com.myxcomp.ice.xtree.ItemTreeApplication;
import com.myxcomp.ice.xtree.common.TimeMapper;
import com.myxcomp.ice.xtree.messaging.dev.InMemoryEventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class E2ETestConfigTest {

    private ConfigurableApplicationContext ctx;

    @BeforeEach
    void resetBus() {
        SharedBusHolder.reset();
    }

    @AfterEach
    void closeCtx() {
        if (ctx != null) ctx.close();
    }

    @Test
    void primaryBusOverrideWiresSharedInstance() {
        ctx = new SpringApplicationBuilder(ItemTreeApplication.class, E2ETestConfig.class)
                .profiles("dev")
                .properties("spring.main.allow-bean-definition-overriding=true")
                .web(WebApplicationType.NONE)
                .run();

        InMemoryEventBus injected = ctx.getBean(InMemoryEventBus.class);
        assertThat(injected).isSameAs(SharedBusHolder.get());
    }

    @Test
    void primaryTimeMapperOverrideWiresMock() {
        ctx = new SpringApplicationBuilder(ItemTreeApplication.class, E2ETestConfig.class)
                .profiles("dev")
                .properties("spring.main.allow-bean-definition-overriding=true")
                .web(WebApplicationType.NONE)
                .run();

        TimeMapper injected = ctx.getBean(TimeMapper.class);
        assertThat(Mockito.mockingDetails(injected).isMock()).isTrue();
        assertThat(injected.now()).isEqualTo(Instant.parse("2026-05-18T10:00:00Z"));
    }
}

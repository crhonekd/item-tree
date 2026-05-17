package com.myxcomp.ice.xtree.refresh;

import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RefreshSchedulerTest {

    @Test
    void scheduledDeltaInvokesOrchestratorDelta() {
        RefreshOrchestrator orchestrator = mock(RefreshOrchestrator.class);
        RefreshScheduler scheduler = new RefreshScheduler(orchestrator);

        scheduler.scheduledDelta();

        verify(orchestrator).runDelta();
        verify(orchestrator, never()).runFullReload();
    }

    @Test
    void scheduledFullReloadInvokesOrchestratorFull() {
        RefreshOrchestrator orchestrator = mock(RefreshOrchestrator.class);
        RefreshScheduler scheduler = new RefreshScheduler(orchestrator);

        scheduler.scheduledFullReload();

        verify(orchestrator).runFullReload();
        verify(orchestrator, never()).runDelta();
    }

    @Test
    void cronExpressionsAreWiredFromConfig() {
        Method deltaMethod = ReflectionUtils.findMethod(RefreshScheduler.class, "scheduledDelta");
        Scheduled deltaScheduled = AnnotationUtils.findAnnotation(deltaMethod, Scheduled.class);
        assertThat(deltaScheduled).isNotNull();
        assertThat(deltaScheduled.cron()).isEqualTo("${itemtree.cache.refresh.delta-cron}");

        Method fullReloadMethod = ReflectionUtils.findMethod(RefreshScheduler.class, "scheduledFullReload");
        Scheduled fullReloadScheduled = AnnotationUtils.findAnnotation(fullReloadMethod, Scheduled.class);
        assertThat(fullReloadScheduled).isNotNull();
        assertThat(fullReloadScheduled.cron()).isEqualTo("${itemtree.cache.refresh.full-reload-cron}");
    }
}

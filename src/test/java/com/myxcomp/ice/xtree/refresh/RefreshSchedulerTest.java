package com.myxcomp.ice.xtree.refresh;

import org.junit.jupiter.api.Test;

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
}

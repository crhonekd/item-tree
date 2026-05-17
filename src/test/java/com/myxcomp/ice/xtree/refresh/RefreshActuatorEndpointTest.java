package com.myxcomp.ice.xtree.refresh;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class RefreshActuatorEndpointTest {

    private RefreshOrchestrator orchestrator;
    private RefreshActuatorEndpoint endpoint;

    @BeforeEach
    void setUp() {
        orchestrator = mock(RefreshOrchestrator.class);
        endpoint = new RefreshActuatorEndpoint(orchestrator);
    }

    @Test
    void deltaTypeRunsDelta() {
        DeltaCounters c = new DeltaCounters();
        when(orchestrator.runDelta()).thenReturn(RefreshResult.deltaSuccess(123L, c));

        RefreshResult result = endpoint.refresh("delta");

        assertThat(result.type()).isEqualTo(RefreshResult.Type.DELTA);
        verify(orchestrator).runDelta();
        verify(orchestrator, never()).runFullReload();
    }

    @Test
    void fullTypeRunsFullReload() {
        DriftCounters c = new DriftCounters();
        when(orchestrator.runFullReload()).thenReturn(RefreshResult.fullSuccess(456L, c));

        RefreshResult result = endpoint.refresh("full");

        assertThat(result.type()).isEqualTo(RefreshResult.Type.FULL);
        verify(orchestrator).runFullReload();
    }

    @Test
    void unknownTypeThrows() {
        assertThatThrownBy(() -> endpoint.refresh("nonsense"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nonsense");
        verifyNoInteractions(orchestrator);
    }

    @Test
    void typeMatchingIsCaseInsensitive() {
        when(orchestrator.runDelta()).thenReturn(RefreshResult.deltaSuccess(1L, new DeltaCounters()));
        endpoint.refresh("DELTA");
        verify(orchestrator).runDelta();
    }
}

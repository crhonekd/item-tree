package com.myxcomp.ice.xtree.messaging;

import com.myxcomp.ice.xtree.common.TimeMapper;
import com.myxcomp.ice.xtree.messaging.dev.StubConnectionExceptionListener;
import com.myxcomp.ice.xtree.refresh.RefreshOrchestrator;
import com.myxcomp.ice.xtree.refresh.RefreshResult;
import com.myxcomp.ice.xtree.refresh.DeltaCounters;
import com.myxcomp.ice.xtree.refresh.DriftCounters;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dev")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class MessagingResilienceIT {

    private static final Instant T0 = Instant.parse("2026-05-18T10:00:00Z");

    @Autowired StubConnectionExceptionListener stub;
    @Autowired ConnectionStateTracker tracker;
    @MockitoBean RefreshOrchestrator orchestrator;
    @MockitoBean TimeMapper timeMapper;
    @Autowired MeterRegistry meterRegistry;

    @BeforeEach
    void freezeTimeAndStubOrchestrator() {
        when(timeMapper.now()).thenReturn(T0);
        doReturn(RefreshResult.deltaSuccess(0, new DeltaCounters()))
                .when(orchestrator).runDelta();
        doReturn(RefreshResult.fullSuccess(0, new DriftCounters()))
                .when(orchestrator).runFullReload();
    }

    @Test
    void firstConnectDoesNotTriggerRefresh() {
        stub.simulateRecovery();

        assertThat(tracker.isConnected()).isTrue();
        assertThat(meterRegistry.find("itemtree.solace.reconnect_reconcile").counters()).isEmpty();
        verify(orchestrator, never()).runDelta();
        verify(orchestrator, never()).runFullReload();
    }

    @Test
    void shortOutageDoesNotTriggerRefresh() {
        stub.simulateRecovery();           // first connect
        stub.simulateDisconnect();
        when(timeMapper.now()).thenReturn(T0.plusSeconds(30));
        stub.simulateRecovery();           // reconnect after 30s

        assertThat(meterRegistry.find("itemtree.solace.reconnect_reconcile").counters()).isEmpty();
        verify(orchestrator, never()).runDelta();
        verify(orchestrator, never()).runFullReload();
    }

    @Test
    void mediumOutageTriggersDeltaRefresh() {
        stub.simulateRecovery();           // first connect
        stub.simulateDisconnect();
        when(timeMapper.now()).thenReturn(T0.plus(Duration.ofMinutes(10)));
        stub.simulateRecovery();           // reconnect after 10 min

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> verify(orchestrator).runDelta());
        assertThat(meterRegistry.counter("itemtree.solace.reconnect_reconcile", "type", "delta").count())
                .isOne();
    }

    @Test
    void longOutageTriggersFullReload() {
        stub.simulateRecovery();           // first connect
        stub.simulateDisconnect();
        when(timeMapper.now()).thenReturn(T0.plus(Duration.ofHours(2)));
        stub.simulateRecovery();           // reconnect after 2h

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> verify(orchestrator).runFullReload());
        assertThat(meterRegistry.counter("itemtree.solace.reconnect_reconcile", "type", "full").count())
                .isOne();
    }
}

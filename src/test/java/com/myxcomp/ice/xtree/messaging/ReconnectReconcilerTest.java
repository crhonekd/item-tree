package com.myxcomp.ice.xtree.messaging;

import com.myxcomp.ice.xtree.common.TimeMapper;
import com.myxcomp.ice.xtree.config.SolaceProperties;
import com.myxcomp.ice.xtree.refresh.RefreshOrchestrator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReconnectReconcilerTest {

    private static final Instant NOW = Instant.parse("2026-05-18T10:00:00Z");

    private RefreshOrchestrator orchestrator;
    private TaskScheduler taskScheduler;
    private TimeMapper timeMapper;
    private SimpleMeterRegistry meterRegistry;
    private ReconnectReconciler reconciler;

    @BeforeEach
    void setUp() {
        orchestrator = mock(RefreshOrchestrator.class);
        taskScheduler = mock(TaskScheduler.class);
        timeMapper = mock(TimeMapper.class);
        meterRegistry = new SimpleMeterRegistry();
        when(timeMapper.now()).thenReturn(NOW);
        SolaceProperties props = new SolaceProperties(
                "BC/ICE/ITEMTREE",
                new SolaceProperties.Reconnect(Duration.ofMinutes(1), Duration.ofHours(1)),
                new SolaceProperties.Health(Duration.ofHours(4)));
        reconciler = new ReconnectReconciler(orchestrator, taskScheduler, timeMapper, meterRegistry, props);
    }

    @ParameterizedTest(name = "{0} → no-op")
    @MethodSource("shortOutages")
    void outagesBelowShortThresholdAreNoOps(Duration outage) {
        reconciler.reconcile(outage);

        verifyNoInteractions(taskScheduler, orchestrator);
        assertThat(meterRegistry.find("itemtree.solace.reconnect_reconcile").counters()).isEmpty();
    }

    static Stream<Arguments> shortOutages() {
        return Stream.of(
                Arguments.of(Duration.ZERO),
                Arguments.of(Duration.ofSeconds(30)),
                Arguments.of(Duration.ofSeconds(59)));
    }

    @ParameterizedTest(name = "{0} → delta")
    @MethodSource("mediumOutages")
    void outagesBetweenThresholdsTriggerDelta(Duration outage) {
        reconciler.reconcile(outage);

        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(taskCaptor.capture(), any(Instant.class));
        verify(orchestrator, never()).runDelta();

        taskCaptor.getValue().run();
        verify(orchestrator).runDelta();
        verify(orchestrator, never()).runFullReload();

        assertThat(meterRegistry.counter("itemtree.solace.reconnect_reconcile", "type", "delta").count())
                .isOne();
    }

    static Stream<Arguments> mediumOutages() {
        return Stream.of(
                Arguments.of(Duration.ofMinutes(1)),
                Arguments.of(Duration.ofMinutes(10)),
                Arguments.of(Duration.ofMinutes(59).plusSeconds(59)));
    }

    @ParameterizedTest(name = "{0} → full reload")
    @MethodSource("longOutages")
    void outagesAtOrAboveLongThresholdTriggerFullReload(Duration outage) {
        reconciler.reconcile(outage);

        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(taskCaptor.capture(), any(Instant.class));

        taskCaptor.getValue().run();
        verify(orchestrator).runFullReload();
        verify(orchestrator, never()).runDelta();

        assertThat(meterRegistry.counter("itemtree.solace.reconnect_reconcile", "type", "full").count())
                .isOne();
    }

    static Stream<Arguments> longOutages() {
        return Stream.of(
                Arguments.of(Duration.ofHours(1)),
                Arguments.of(Duration.ofHours(2)),
                Arguments.of(Duration.ofHours(6)));
    }

    @Test
    void counterIncrementsAtSubmissionBeforeTaskRuns() {
        reconciler.reconcile(Duration.ofMinutes(10));

        assertThat(meterRegistry.counter("itemtree.solace.reconnect_reconcile", "type", "delta").count())
                .isOne();
        verify(orchestrator, never()).runDelta();
    }

    @Test
    void nullOutageThrows() {
        assertThatNullPointerException()
                .isThrownBy(() -> reconciler.reconcile(null))
                .withMessageContaining("outage");
    }
}

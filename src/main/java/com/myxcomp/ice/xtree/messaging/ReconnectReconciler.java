package com.myxcomp.ice.xtree.messaging;

import com.myxcomp.ice.xtree.config.SolaceProperties;
import com.myxcomp.ice.xtree.refresh.RefreshOrchestrator;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Classifies a broker outage by duration and submits the matching refresh to the shared
 * {@link TaskScheduler} (design §6 "Reconnect reconciliation"):
 * <pre>
 *   outage &lt; short-threshold (PT1M) → no-op
 *   outage &lt; long-threshold  (PT1H) → delta refresh
 *   outage ≥ long-threshold           → full reload
 * </pre>
 *
 * <p>Tasks are submitted for immediate execution by passing {@code Instant.EPOCH} as the
 * scheduler trigger — a past instant fires immediately on the next available thread.
 *
 * <p>The counter {@code itemtree.solace.reconnect_reconcile{type}} increments at submission time.
 * Submitted-task outcomes are counted by {@link RefreshOrchestrator}'s own failure counters.
 */
@Component
public class ReconnectReconciler {

    private static final Logger log = LoggerFactory.getLogger(ReconnectReconciler.class);

    private final RefreshOrchestrator orchestrator;
    private final TaskScheduler taskScheduler;
    private final MeterRegistry meterRegistry;
    private final SolaceProperties props;

    public ReconnectReconciler(RefreshOrchestrator orchestrator,
                               TaskScheduler taskScheduler,
                               MeterRegistry meterRegistry,
                               SolaceProperties props) {
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
        this.taskScheduler = Objects.requireNonNull(taskScheduler, "taskScheduler");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        this.props = Objects.requireNonNull(props, "props");
    }

    public void reconcile(Duration outage) {
        Objects.requireNonNull(outage, "outage");
        Duration shortT = props.reconnect().shortThreshold();
        Duration longT = props.reconnect().longThreshold();

        if (outage.compareTo(shortT) < 0) {
            log.info("Reconnect outage={}s below short-threshold={}s — no reconcile",
                    outage.toSeconds(), shortT.toSeconds());
            return;
        }

        if (outage.compareTo(longT) < 0) {
            log.info("Reconnect outage={}s below long-threshold={}s — queueing delta",
                    outage.toSeconds(), longT.toSeconds());
            meterRegistry.counter("itemtree.solace.reconnect_reconcile", "type", "delta").increment();
            taskScheduler.schedule(orchestrator::runDelta, Instant.EPOCH);
            return;
        }

        log.info("Reconnect outage={}s at-or-above long-threshold={}s — queueing full reload",
                outage.toSeconds(), longT.toSeconds());
        meterRegistry.counter("itemtree.solace.reconnect_reconcile", "type", "full").increment();
        taskScheduler.schedule(orchestrator::runFullReload, Instant.EPOCH);
    }
}

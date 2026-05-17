package com.myxcomp.ice.xtree.refresh;

import com.myxcomp.ice.xtree.cache.SnapshotBuilder;
import com.myxcomp.ice.xtree.cache.TreeCache;
import com.myxcomp.ice.xtree.cache.TreeSnapshot;
import com.myxcomp.ice.xtree.common.TimeMapper;
import com.myxcomp.ice.xtree.config.RefreshProperties;
import com.myxcomp.ice.xtree.persistence.ItemTreeRepository;
import com.myxcomp.ice.xtree.persistence.StructuralRow;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class RefreshOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(RefreshOrchestrator.class);

    private final ItemTreeRepository repository;
    private final TreeCache cache;
    private final DeltaReconciler reconciler;
    private final TimeMapper timeMapper;
    private final MeterRegistry meterRegistry;
    private final RefreshProperties props;

    private final AtomicReference<Instant> lastRefreshInstant = new AtomicReference<>(Instant.EPOCH);

    public RefreshOrchestrator(ItemTreeRepository repository,
                               TreeCache cache,
                               DeltaReconciler reconciler,
                               TimeMapper timeMapper,
                               MeterRegistry meterRegistry,
                               RefreshProperties props) {
        this.repository = repository;
        this.cache = cache;
        this.reconciler = reconciler;
        this.timeMapper = timeMapper;
        this.meterRegistry = meterRegistry;
        this.props = props;
    }

    public RefreshResult runDelta() {
        Timer.Sample sample = Timer.start(meterRegistry);
        long startNs = System.nanoTime();
        try {
            Instant since = lastRefreshInstant.get().minusSeconds(props.deltaOverlapSeconds());
            List<StructuralRow> rows = repository.findStructuralChangedSince(since);
            DeltaCounters counters = new DeltaCounters();
            for (StructuralRow row : rows) {
                reconciler.reconcileRow(row, counters);
            }
            lastRefreshInstant.set(timeMapper.now());
            recordDeltaCounters(counters);
            long durationMs = Duration.ofNanos(System.nanoTime() - startNs).toMillis();
            sample.stop(meterRegistry.timer("itemtree.cache.refresh.delta.duration"));
            log.info("Delta refresh ok in {}ms: {}", durationMs, counters);
            return RefreshResult.deltaSuccess(durationMs, counters);
        } catch (RuntimeException e) {
            long durationMs = Duration.ofNanos(System.nanoTime() - startNs).toMillis();
            sample.stop(meterRegistry.timer("itemtree.cache.refresh.delta.duration"));
            meterRegistry.counter("itemtree.cache.refresh.delta.failure").increment();
            log.warn("Delta refresh failed after {}ms", durationMs, e);
            return RefreshResult.deltaFailure(durationMs, e.toString());
        }
    }

    public RefreshResult runFullReload() {
        Timer.Sample sample = Timer.start(meterRegistry);
        long startNs = System.nanoTime();
        try {
            SnapshotBuilder builder = new SnapshotBuilder();
            repository.streamAllStructural(builder::accept);
            TreeSnapshot newSnap = builder.build();
            // Snapshot the cache immediately before the swap to minimise the concurrent-write
            // window that would appear as false drift (gap is now nanoseconds, not seconds).
            TreeSnapshot oldSnap = cache.snapshot();
            DriftCounters drift = SnapshotDiff.diff(oldSnap, newSnap);
            cache.replaceAll(newSnap);
            lastRefreshInstant.set(timeMapper.now());
            recordDriftCounters(drift);
            long durationMs = Duration.ofNanos(System.nanoTime() - startNs).toMillis();
            sample.stop(meterRegistry.timer("itemtree.cache.refresh.full.duration"));
            log.info("Full reload ok in {}ms: {}", durationMs, drift);
            return RefreshResult.fullSuccess(durationMs, drift);
        } catch (RuntimeException e) {
            long durationMs = Duration.ofNanos(System.nanoTime() - startNs).toMillis();
            sample.stop(meterRegistry.timer("itemtree.cache.refresh.full.duration"));
            meterRegistry.counter("itemtree.cache.refresh.full.failure").increment();
            log.warn("Full reload failed after {}ms", durationMs, e);
            return RefreshResult.fullFailure(durationMs, e.toString());
        }
    }

    /** Package-private accessor used by tests only. */
    Instant lastRefreshInstant() {
        return lastRefreshInstant.get();
    }

    private void recordDeltaCounters(DeltaCounters c) {
        if (c.created() > 0) meterRegistry.counter("itemtree.cache.refresh.delta.rows", "change", "created").increment(c.created());
        if (c.moved()   > 0) meterRegistry.counter("itemtree.cache.refresh.delta.rows", "change", "moved").increment(c.moved());
        if (c.renamed() > 0) meterRegistry.counter("itemtree.cache.refresh.delta.rows", "change", "renamed").increment(c.renamed());
        if (c.meta()    > 0) meterRegistry.counter("itemtree.cache.refresh.delta.rows", "change", "meta").increment(c.meta());
    }

    private void recordDriftCounters(DriftCounters d) {
        if (d.created() > 0) meterRegistry.counter("itemtree.cache.refresh.full.drift", "type", "created").increment(d.created());
        if (d.deleted() > 0) meterRegistry.counter("itemtree.cache.refresh.full.drift", "type", "deleted").increment(d.deleted());
        if (d.mutated() > 0) meterRegistry.counter("itemtree.cache.refresh.full.drift", "type", "mutated").increment(d.mutated());
    }
}

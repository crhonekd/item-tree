package com.myxcomp.ice.xtree.bootstrap;

import com.myxcomp.ice.xtree.cache.CacheReadinessGate;
import com.myxcomp.ice.xtree.cache.SnapshotBuilder;
import com.myxcomp.ice.xtree.cache.TreeCache;
import com.myxcomp.ice.xtree.cache.TreeSnapshot;
import com.myxcomp.ice.xtree.config.RefreshProperties;
import com.myxcomp.ice.xtree.persistence.ItemTreeRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Order(1)
public class TreeCacheBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TreeCacheBootstrap.class);

    private final ItemTreeRepository repository;
    private final TreeCache cache;
    private final CacheReadinessGate gate;
    private final Sleeper sleeper;
    private final MeterRegistry meterRegistry;
    private final RefreshProperties props;

    private final AtomicLong rowsLoaded = new AtomicLong(0L);

    public TreeCacheBootstrap(ItemTreeRepository repository,
                              TreeCache cache,
                              CacheReadinessGate gate,
                              Sleeper sleeper,
                              MeterRegistry meterRegistry,
                              RefreshProperties props) {
        this.repository = repository;
        this.cache = cache;
        this.gate = gate;
        this.sleeper = sleeper;
        this.meterRegistry = meterRegistry;
        this.props = props;
        meterRegistry.gauge("itemtree.cache.bootstrap.rows", rowsLoaded);
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        int maxAttempts = Math.max(1, props.bootstrapRetries());
        List<Duration> backoff = props.bootstrapBackoff();
        Timer timer = meterRegistry.timer("itemtree.cache.bootstrap.duration");

        Throwable last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            meterRegistry.counter("itemtree.cache.bootstrap.attempts").increment();
            long start = System.nanoTime();
            try {
                SnapshotBuilder builder = new SnapshotBuilder();
                AtomicInteger count = new AtomicInteger();
                repository.streamAllStructural(row -> {
                    builder.accept(row);
                    count.incrementAndGet();
                });
                TreeSnapshot snapshot = builder.build();
                cache.replaceAll(snapshot);
                rowsLoaded.set(count.get());
                long elapsedNs = System.nanoTime() - start;
                timer.record(Duration.ofNanos(elapsedNs));
                log.info("Cache bootstrap succeeded on attempt {}/{}: rows={}, elapsedMs={}",
                        attempt, maxAttempts, count.get(), Duration.ofNanos(elapsedNs).toMillis());
                checkIndex();
                gate.markReady();
                return;
            } catch (RuntimeException e) {
                last = e;
                log.warn("Cache bootstrap attempt {}/{} failed: {}", attempt, maxAttempts, e.toString());
                if (attempt < maxAttempts) {
                    Duration sleep = attempt - 1 < backoff.size()
                            ? backoff.get(attempt - 1) : Duration.ofSeconds(25);
                    log.info("Sleeping {} before next bootstrap attempt", sleep);
                    try {
                        sleeper.sleep(sleep);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Cache bootstrap interrupted during backoff sleep", ie);
                    }
                }
            }
        }
        throw new IllegalStateException("Cache bootstrap failed after " + maxAttempts + " attempts", last);
    }

    private void checkIndex() {
        try {
            boolean present = repository.lastUpdateIndexExists();
            if (!present) {
                log.warn("ITEMTREE.LASTUPDATE index is missing — delta refresh will full-scan");
            } else {
                log.info("ITEMTREE.LASTUPDATE index confirmed present");
            }
        } catch (RuntimeException e) {
            log.warn("Index-presence check raised exception: {}", e.toString());
        }
    }
}

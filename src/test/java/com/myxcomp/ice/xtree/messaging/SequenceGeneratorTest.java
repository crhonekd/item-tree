package com.myxcomp.ice.xtree.messaging;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class SequenceGeneratorTest {

    @Test
    void firstCallReturnsOne() {
        SequenceGenerator gen = new SequenceGenerator();
        assertThat(gen.next()).isEqualTo(1L);
    }

    @Test
    void sequentialCallsIncrementMonotonically() {
        SequenceGenerator gen = new SequenceGenerator();
        assertThat(gen.next()).isEqualTo(1L);
        assertThat(gen.next()).isEqualTo(2L);
        assertThat(gen.next()).isEqualTo(3L);
    }

    @Test
    void concurrentCallsProduceUniqueContiguousValues() throws InterruptedException {
        SequenceGenerator gen = new SequenceGenerator();
        int threads = 16;
        int perThread = 1_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<Long> values = new HashSet<>();
        Object guard = new Object();

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    long v = gen.next();
                    synchronized (guard) { values.add(v); }
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        int total = threads * perThread;
        assertThat(values).hasSize(total);
        assertThat(values).contains(1L);
        assertThat(values).contains((long) total);
    }
}

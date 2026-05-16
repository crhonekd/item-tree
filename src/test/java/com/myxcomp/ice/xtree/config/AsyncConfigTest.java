package com.myxcomp.ice.xtree.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncConfigTest {

    @Test
    void backfillExecutorIsSingleThreadedWithBoundedQueueAndAbortPolicy() {
        AsyncConfig config = new AsyncConfig();
        ThreadPoolTaskExecutor exec = config.backfillExecutor();

        assertThat(exec.getCorePoolSize()).isEqualTo(1);
        assertThat(exec.getMaxPoolSize()).isEqualTo(1);
        assertThat(exec.getThreadNamePrefix()).isEqualTo("backfill-");
        assertThat(exec.getThreadPoolExecutor().getRejectedExecutionHandler())
                .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
        assertThat(exec.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(100);
    }
}

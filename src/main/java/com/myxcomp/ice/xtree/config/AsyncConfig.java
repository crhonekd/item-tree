package com.myxcomp.ice.xtree.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Async machinery used by service-layer fire-and-forget work.
 *
 * <p>Only bean for Phase 7: {@code backfillExecutor}, a single-threaded bounded-queue executor
 * used by {@link com.myxcomp.ice.xtree.service.ItemService#getItemsWithData} to schedule the
 * silent JSON-column backfill (design §11). Bounded queue + AbortPolicy gives us bounded memory
 * and visible backpressure: a saturated executor throws {@code RejectedExecutionException}
 * rather than silently piling work up.
 */
@Configuration
public class AsyncConfig {

    @Bean("backfillExecutor")
    public ThreadPoolTaskExecutor backfillExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(1);
        exec.setMaxPoolSize(1);
        exec.setQueueCapacity(100);
        exec.setThreadNamePrefix("backfill-");
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        exec.initialize();
        return exec;
    }
}

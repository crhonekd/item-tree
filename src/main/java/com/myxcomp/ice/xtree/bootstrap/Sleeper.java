package com.myxcomp.ice.xtree.bootstrap;

import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Test seam for {@code Thread.sleep(...)}. The default {@link DefaultSleeper} simply delegates
 * to {@link Thread#sleep(long)}; unit tests can pass a no-op or recording double instead.
 */
public interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;

    @Component
    class DefaultSleeper implements Sleeper {
        @Override
        public void sleep(Duration duration) throws InterruptedException {
            Thread.sleep(duration.toMillis());
        }
    }
}

package com.myxcomp.ice.xtree.messaging;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-JVM monotonic sequence number stamped on every outgoing {@code TreeMutationEvent}.
 * First call returns {@code 1L}. Resets across JVM restarts — recovery happens via periodic
 * refresh (design §6).
 */
@Component
public class SequenceGenerator {

    private final AtomicLong counter = new AtomicLong(0L);

    public long next() {
        return counter.incrementAndGet();
    }
}

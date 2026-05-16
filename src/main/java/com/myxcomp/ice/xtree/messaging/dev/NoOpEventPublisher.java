package com.myxcomp.ice.xtree.messaging.dev;

import com.myxcomp.ice.xtree.messaging.EventPublisher;
import com.myxcomp.ice.xtree.messaging.event.TreeMutationEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Phase-A placeholder. Phase 10 supplants this with {@code LocalLoopbackEventPublisher}.
 */
@Component
@Profile("dev")
public class NoOpEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(NoOpEventPublisher.class);

    @Override
    public void publish(TreeMutationEvent event) {
        log.debug("NoOpEventPublisher dropped event: {}", event.getOperationType());
    }
}

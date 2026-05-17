package com.myxcomp.ice.xtree.refresh;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(RefreshScheduler.class);

    private final RefreshOrchestrator orchestrator;

    public RefreshScheduler(RefreshOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Scheduled(cron = "${itemtree.cache.refresh.delta-cron}", zone = "UTC")
    public void scheduledDelta() {
        log.debug("scheduledDelta fired");
        orchestrator.runDelta();
    }

    @Scheduled(cron = "${itemtree.cache.refresh.full-reload-cron}", zone = "UTC")
    public void scheduledFullReload() {
        log.debug("scheduledFullReload fired");
        orchestrator.runFullReload();
    }
}

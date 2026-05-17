package com.myxcomp.ice.xtree.refresh;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Manual refresh trigger (design §7 "Manual trigger").
 *
 * <p>Reached via {@code POST /actuator/itemtree-refresh/{type}} where {@code type} is
 * {@code delta} or {@code full}.
 */
@Component
@Endpoint(id = "itemtree-refresh")
public class RefreshActuatorEndpoint {

    private final RefreshOrchestrator orchestrator;

    public RefreshActuatorEndpoint(RefreshOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @WriteOperation
    public RefreshResult refresh(@Selector String type) {
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "delta" -> orchestrator.runDelta();
            case "full"  -> orchestrator.runFullReload();
            default      -> throw new IllegalArgumentException(
                    "Unknown refresh type: " + type + " (expected 'delta' or 'full')");
        };
    }
}

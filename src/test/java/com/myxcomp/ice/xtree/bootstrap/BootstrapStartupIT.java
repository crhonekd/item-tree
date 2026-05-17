package com.myxcomp.ice.xtree.bootstrap;

import com.myxcomp.ice.xtree.cache.CacheReadinessGate;
import com.myxcomp.ice.xtree.cache.TreeCache;
import com.myxcomp.ice.xtree.persistence.ItemTreeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dev")
class BootstrapStartupIT {

    @Autowired
    private TreeCache cache;

    @Autowired
    private CacheReadinessGate gate;

    @Autowired
    private ApplicationAvailability availability;

    @Autowired
    private ItemTreeRepository repository;

    @Test
    void cacheIsPopulatedAndGateIsReadyAfterStartup() {
        assertThat(cache.size()).isGreaterThan(0);
        assertThat(gate.isReady()).isTrue();
    }

    @Test
    void applicationReadinessStateIsAcceptingTraffic() {
        assertThat(availability.getReadinessState()).isEqualTo(ReadinessState.ACCEPTING_TRAFFIC);
    }

    @Test
    void lastUpdateIndexIsPresentInSeedSchema() {
        assertThat(repository.lastUpdateIndexExists()).isTrue();
    }
}

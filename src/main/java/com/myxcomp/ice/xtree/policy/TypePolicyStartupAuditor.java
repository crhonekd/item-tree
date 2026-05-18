package com.myxcomp.ice.xtree.policy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class TypePolicyStartupAuditor implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TypePolicyStartupAuditor.class);
    private static final String SELECT_DISTINCT_TYPES = "SELECT DISTINCT TYPE FROM ITEMTREE";

    private final JdbcClient jdbcClient;
    private final TypePolicy typePolicy;
    private final DataProperties dataProperties;
    private final MeterRegistry meterRegistry;

    public TypePolicyStartupAuditor(JdbcClient jdbcClient,
                                    TypePolicy typePolicy,
                                    DataProperties dataProperties,
                                    MeterRegistry meterRegistry) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
        this.typePolicy = Objects.requireNonNull(typePolicy, "typePolicy");
        this.dataProperties = Objects.requireNonNull(dataProperties, "dataProperties");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Type policy loaded: types-without-data={}, types-also-persisted-as-xml-on-write={}, types-sent-as-xml-to-ui={}",
                dataProperties.typesWithoutData(),
                dataProperties.typesAlsoPersistedAsXmlOnWrite(),
                dataProperties.typesSentAsXmlToUi());

        Set<String> typesInDb = new LinkedHashSet<>(
                jdbcClient.sql(SELECT_DISTINCT_TYPES)
                          .query(String.class)
                          .list());

        Set<String> unknownInDb = new TreeSet<>();
        for (String t : typesInDb) {
            if (!typePolicy.isKnown(t)) {
                unknownInDb.add(t);
                meterRegistry.counter("itemtree.policy.unknown_type", "type", t).increment();
            }
        }
        if (!unknownInDb.isEmpty()) {
            log.info("itemtree.data: types seen in DB but absent from all configured lists "
                    + "(default policy applies): {}", unknownInDb);
        }

        List<String> configured = new ArrayList<>();
        configured.addAll(dataProperties.typesWithoutData());
        configured.addAll(dataProperties.typesAlsoPersistedAsXmlOnWrite());
        configured.addAll(dataProperties.typesSentAsXmlToUi());

        Set<String> configuredAbsentFromDb = new TreeSet<>();
        for (String t : configured) {
            if (!typesInDb.contains(t)) configuredAbsentFromDb.add(t);
        }
        if (!configuredAbsentFromDb.isEmpty()) {
            log.warn("itemtree.data: configured types absent from DB: {}", configuredAbsentFromDb);
        }
    }
}

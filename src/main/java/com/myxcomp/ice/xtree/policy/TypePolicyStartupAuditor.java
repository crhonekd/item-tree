package com.myxcomp.ice.xtree.policy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class TypePolicyStartupAuditor implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TypePolicyStartupAuditor.class);
    private static final String SELECT_DISTINCT_TYPES = "SELECT DISTINCT TYPE FROM ITEMTREE";

    private final JdbcClient jdbcClient;
    private final TypePolicy typePolicy;
    private final DataProperties dataProperties;

    public TypePolicyStartupAuditor(JdbcClient jdbcClient,
                                    TypePolicy typePolicy,
                                    DataProperties dataProperties) {
        this.jdbcClient = jdbcClient;
        this.typePolicy = typePolicy;
        this.dataProperties = dataProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        Set<String> typesInDb = new LinkedHashSet<>(
                jdbcClient.sql(SELECT_DISTINCT_TYPES)
                          .query(String.class)
                          .list());

        Set<String> unknownInDb = new TreeSet<>();
        for (String t : typesInDb) {
            if (!typePolicy.isKnown(t)) unknownInDb.add(t);
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

package com.myxcomp.ice.xtree.policy;

import java.util.ArrayList;
import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dev")
@Transactional
class TypePolicyStartupAuditorIT {

    @Autowired private JdbcClient jdbcClient;
    @Autowired private TypePolicy typePolicy;
    @Autowired private DataProperties dataProperties;

    private ListAppender<ILoggingEvent> appender;
    private Logger auditorLogger;

    @BeforeEach
    void attachLogAppender() {
        auditorLogger = (Logger) LoggerFactory.getLogger(TypePolicyStartupAuditor.class);
        appender = new ListAppender<>();
        appender.start();
        auditorLogger.addAppender(appender);
    }

    @AfterEach
    void detachLogAppender() {
        auditorLogger.detachAppender(appender);
    }

    @Test
    void logsInfoForUnknownTypesSeenInDb() {
        TypePolicyStartupAuditor auditor =
                new TypePolicyStartupAuditor(jdbcClient, typePolicy, dataProperties);
        auditor.run(null);

        // The dev seed contains View, UDF.Context, Eval — none of which appear in
        // the production lists, so they should show up as INFO.
        List<ILoggingEvent> infoEvents = appender.list.stream()
                .filter(e -> e.getLevel() == Level.INFO)
                .toList();
        assertThat(infoEvents)
                .anyMatch(e -> e.getFormattedMessage().contains("View"))
                .anyMatch(e -> e.getFormattedMessage().contains("UDF.Context"))
                .anyMatch(e -> e.getFormattedMessage().contains("Eval"));
    }

    @Test
    void warnsForConfiguredTypesAbsentFromDb() {
        // Wipe data so every configured type is absent.
        jdbcClient.sql("DELETE FROM ITEMTREE").update();

        TypePolicyStartupAuditor auditor =
                new TypePolicyStartupAuditor(jdbcClient, typePolicy, dataProperties);
        auditor.run(null);

        List<ILoggingEvent> warnEvents = appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .toList();
        assertThat(warnEvents)
                .anyMatch(e -> e.getFormattedMessage().contains("Folder"))
                .anyMatch(e -> e.getFormattedMessage().contains("Report"));
    }

    @Test
    void noWarningsWhenAllConfiguredTypesPresentAndKnown() {
        // Replace seed with exactly one row per configured type, no extras.
        jdbcClient.sql("DELETE FROM ITEMTREE").update();
        List<String> configured = new ArrayList<>();
        configured.addAll(dataProperties.typesWithoutData());
        configured.addAll(dataProperties.typesAlsoPersistedAsXmlOnWrite());
        long id = 1000L;
        for (String t : configured) {
            jdbcClient.sql(
                    "INSERT INTO ITEMTREE (ITEMTREEID, PARENTID, NAME, TYPE, LASTUPDATEUSER, LASTUPDATE) "
                  + "VALUES (:id, 0, :n, :t, 'sys', TIMESTAMP '2026-05-01 10:00:00')")
                .param("id", id++)
                .param("n", "n" + t)
                .param("t", t)
                .update();
        }

        TypePolicyStartupAuditor auditor =
                new TypePolicyStartupAuditor(jdbcClient, typePolicy, dataProperties);
        auditor.run(null);

        assertThat(appender.list).filteredOn(e -> e.getLevel() == Level.WARN).isEmpty();
        assertThat(appender.list).filteredOn(e -> e.getLevel() == Level.INFO).isEmpty();
    }
}

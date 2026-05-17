package com.myxcomp.ice.xtree.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dev")
@DirtiesContext
class JdbcItemTreeRepositoryIndexCheckIT {

    @Autowired
    private JdbcItemTreeRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void restoreIndex() {
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS IDX_ITEMTREE_LASTUPDATE ON ITEMTREE(LASTUPDATE)");
    }

    @Test
    void returnsTrueWhenIndexPresent() {
        assertThat(repository.lastUpdateIndexExists()).isTrue();
    }

    @Test
    void returnsFalseAfterIndexDropped() {
        jdbcTemplate.execute("DROP INDEX IDX_ITEMTREE_LASTUPDATE");
        assertThat(repository.lastUpdateIndexExists()).isFalse();
    }
}

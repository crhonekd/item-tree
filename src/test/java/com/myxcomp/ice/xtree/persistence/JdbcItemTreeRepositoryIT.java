package com.myxcomp.ice.xtree.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dev")
@Transactional
class JdbcItemTreeRepositoryIT {

    @Autowired
    private JdbcItemTreeRepository repository;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void contextLoads() {
        assertThat(repository).isNotNull();
    }
}

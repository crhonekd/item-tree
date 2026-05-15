package com.myxcomp.ice.xtree;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("dev")
class ItemTreeApplicationTests {

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcClient jdbcClient;

    @Test
    void contextLoads() {
        assertThat(dataSource).isNotNull();
        assertThat(jdbcClient).isNotNull();
    }

    @Test
    void seedDataIsPresent() {
        Long count = jdbcClient.sql("SELECT COUNT(*) FROM ITEMTREE")
                .query(Long.class)
                .single();
        assertThat(count).isGreaterThan(0L);
    }

    @Test
    void rootRowHasExpectedShape() {
        record RootRow(Long id, Long parentId, String name, String type) {}

        RootRow root = jdbcClient.sql("""
                        SELECT ITEMTREEID, PARENTID, NAME, TYPE
                        FROM ITEMTREE
                        WHERE ITEMTREEID = 1
                        """)
                .query((rs, n) -> new RootRow(
                        rs.getLong("ITEMTREEID"),
                        rs.getLong("PARENTID"),
                        rs.getString("NAME"),
                        rs.getString("TYPE")))
                .single();

        assertThat(root.id()).isEqualTo(1L);
        assertThat(root.parentId()).isZero();
        assertThat(root.name()).isEqualTo("root");
        assertThat(root.type()).isEqualTo("Folder");
    }
}

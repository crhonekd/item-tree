package com.myxcomp.ice.xtree.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = SolacePropertiesTest.Config.class)
@TestPropertySource(properties = {
        "itemtree.solace.topic=BC/ICE/ITEMTREE"
})
class SolacePropertiesTest {

    @EnableConfigurationProperties(SolaceProperties.class)
    static class Config {}

    @Autowired
    private SolaceProperties props;

    @Test
    void topicIsBound() {
        assertThat(props.topic()).isEqualTo("BC/ICE/ITEMTREE");
    }
}

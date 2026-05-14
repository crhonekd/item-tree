package com.myxcomp.ice.xtree.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures the springdoc-generated OpenAPI document with the title and version
 * declared in the authoritative spec (itemtree-api.yaml §info).
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI itemTreeOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("ItemTree API")
                        .description("REST service fronting the ITEMTREE Oracle table.")
                        .version("1.0.0"));
    }
}

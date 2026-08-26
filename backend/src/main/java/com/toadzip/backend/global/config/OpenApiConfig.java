package com.toadzip.backend.global.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI toadzipOpenApi() {
        Info apiInfo = new Info()
                .title("두꺼비집 API")
                .version("0.0.1");

        return new OpenAPI().info(apiInfo);
    }
}

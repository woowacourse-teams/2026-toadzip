package com.toadzip.backend.admin.service;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AdminBootstrapProperties.class)
public class AdminBootstrapConfiguration {

    @Bean
    public ApplicationRunner adminBootstrapRunner(
            AdminBootstrapProperties properties,
            AdminAuthenticationService adminAuthenticationService
    ) {
        return arguments -> adminAuthenticationService.bootstrap(properties);
    }
}

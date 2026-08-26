package com.toadzip.backend.ingest.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.toadzip.backend.ingest.service.LhSupplyInfoTypeCodeResolver;

@Configuration
public class LhIngestConfiguration {

    @Bean
    LhSupplyInfoTypeCodeResolver lhSupplyInfoTypeCodeResolver() {
        return new LhSupplyInfoTypeCodeResolver();
    }
}

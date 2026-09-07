package com.toadzip.backend.ingest.collection.configuration;

import com.toadzip.backend.ingest.collection.service.LhSupplyInfoTypeCodeResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LhIngestConfiguration {

    @Bean
    LhSupplyInfoTypeCodeResolver lhSupplyInfoTypeCodeResolver() {
        return new LhSupplyInfoTypeCodeResolver();
    }
}

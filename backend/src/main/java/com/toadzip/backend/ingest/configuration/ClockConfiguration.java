package com.toadzip.backend.ingest.configuration;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfiguration {

    @Bean
    Clock externalApiClock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}

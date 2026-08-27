package com.toadzip.backend.geocoding.configuration;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.toadzip.backend.geocoding.repository.RoadAddressCoordinateRepository;
import com.toadzip.backend.geocoding.repository.external.JusoCoordinateRequestRateLimiter;
import com.toadzip.backend.geocoding.repository.external.JusoRoadAddressCoordinateRepository;

@Configuration
@EnableConfigurationProperties(JusoGeocodingProperties.class)
public class GeocodingConfiguration {

    @Bean
    RestClient geocodingRestClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(10));
        requestFactory.setReadTimeout(Duration.ofSeconds(30));
        return RestClient.builder().requestFactory(requestFactory).build();
    }

    @Bean
    RoadAddressCoordinateRepository roadAddressCoordinateRepository(
            @Qualifier("geocodingRestClient") RestClient geocodingRestClient,
            JusoGeocodingProperties properties,
            JusoCoordinateRequestRateLimiter rateLimiter
    ) {
        return new JusoRoadAddressCoordinateRepository(
                geocodingRestClient,
                properties,
                rateLimiter
        );
    }
}

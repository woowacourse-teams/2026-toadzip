package com.toadzip.backend.geocoding.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "geocoding.juso")
public record JusoGeocodingProperties(String baseUrl, String addressKey, String coordinateKey) {
}

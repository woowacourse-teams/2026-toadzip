package com.toadzip.backend.ingest.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ingest.juso")
public record JusoGeocodingProperties(String baseUrl, String addressKey, String coordinateKey) {
}

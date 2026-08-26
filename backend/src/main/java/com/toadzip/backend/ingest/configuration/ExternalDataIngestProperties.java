package com.toadzip.backend.ingest.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ingest")
public record ExternalDataIngestProperties(String serviceKey, BaseUrl baseUrl) {

    public record BaseUrl(String myhomeComplex, String myhomeAnnouncement, String lh) {
    }
}

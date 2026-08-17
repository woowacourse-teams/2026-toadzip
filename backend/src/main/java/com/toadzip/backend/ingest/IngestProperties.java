package com.toadzip.backend.ingest;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ingest")
public record IngestProperties(String serviceKey, BaseUrl baseUrl) {

	public record BaseUrl(String myhomeComplex, String myhomeNotice) {
	}

}

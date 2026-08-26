package com.toadzip.backend.ingest.configuration;

import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.toadzip.backend.ingest.repository.external.DataGoKrOpenApiClient;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableConfigurationProperties(ExternalDataIngestProperties.class)
public class ExternalDataIngestConfiguration {

    @Bean
    RestClient externalDataRestClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(10));
        requestFactory.setReadTimeout(Duration.ofSeconds(30));
        return RestClient.builder().requestFactory(requestFactory).build();
    }

    @Bean("myHomeComplexOpenApiClient")
    DataGoKrOpenApiClient myHomeComplexOpenApiClient(
            RestClient externalDataRestClient,
            ObjectMapper objectMapper,
            ExternalDataIngestProperties properties
    ) {
        return new DataGoKrOpenApiClient(
                externalDataRestClient,
                objectMapper,
                properties.baseUrl().myhomeComplex(),
                properties.serviceKey(),
                "마이홈 단지"
        );
    }

    @Bean("myHomeAnnouncementOpenApiClient")
    DataGoKrOpenApiClient myHomeAnnouncementOpenApiClient(
            RestClient externalDataRestClient,
            ObjectMapper objectMapper,
            ExternalDataIngestProperties properties
    ) {
        return new DataGoKrOpenApiClient(
                externalDataRestClient,
                objectMapper,
                properties.baseUrl().myhomeAnnouncement(),
                properties.serviceKey(),
                "마이홈 공고"
        );
    }

    @Bean("lhOpenApiClient")
    DataGoKrOpenApiClient lhOpenApiClient(
            RestClient externalDataRestClient,
            ObjectMapper objectMapper,
            ExternalDataIngestProperties properties
    ) {
        return new DataGoKrOpenApiClient(
                externalDataRestClient,
                objectMapper,
                properties.baseUrl().lh(),
                properties.serviceKey(),
                "LH"
        );
    }
}

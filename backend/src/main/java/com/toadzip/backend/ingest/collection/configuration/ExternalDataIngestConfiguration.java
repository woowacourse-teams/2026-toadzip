package com.toadzip.backend.ingest.collection.configuration;

import com.toadzip.backend.ingest.collection.repository.external.DataGoKrOpenApiClient;
import com.toadzip.backend.ingest.collection.repository.external.LhResponseStatusValidator;
import com.toadzip.backend.ingest.collection.repository.external.MyHomeResponseStatusValidator;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableConfigurationProperties({ExternalDataIngestProperties.class, LhAnnouncementClientProperties.class})
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
            ExternalDataIngestProperties properties,
            MyHomeResponseStatusValidator responseStatusValidator
    ) {
        return new DataGoKrOpenApiClient(
                externalDataRestClient,
                objectMapper,
                properties.baseUrl().myhomeComplex(),
                properties.serviceKey(),
                "마이홈 단지",
                responseStatusValidator
        );
    }

    @Bean("myHomeAnnouncementOpenApiClient")
    DataGoKrOpenApiClient myHomeAnnouncementOpenApiClient(
            RestClient externalDataRestClient,
            ObjectMapper objectMapper,
            ExternalDataIngestProperties properties,
            MyHomeResponseStatusValidator responseStatusValidator
    ) {
        return new DataGoKrOpenApiClient(
                externalDataRestClient,
                objectMapper,
                properties.baseUrl().myhomeAnnouncement(),
                properties.serviceKey(),
                "마이홈 공고",
                responseStatusValidator
        );
    }

    @Bean("lhAnnouncementOpenApiClient")
    DataGoKrOpenApiClient lhAnnouncementOpenApiClient(
            ObjectMapper objectMapper,
            ExternalDataIngestProperties properties,
            LhAnnouncementClientProperties clientProperties,
            LhResponseStatusValidator responseStatusValidator
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(clientProperties.connectTimeout());
        requestFactory.setReadTimeout(clientProperties.readTimeout());
        RestClient client = RestClient.builder().requestFactory(requestFactory).build();
        return new DataGoKrOpenApiClient(client, objectMapper, properties.baseUrl().lh(),
                properties.serviceKey(), "LH 공고", responseStatusValidator);
    }

    @Bean("lhOpenApiClient")
    DataGoKrOpenApiClient lhOpenApiClient(
            RestClient externalDataRestClient,
            ObjectMapper objectMapper,
            ExternalDataIngestProperties properties,
            LhResponseStatusValidator responseStatusValidator
    ) {
        return new DataGoKrOpenApiClient(
                externalDataRestClient,
                objectMapper,
                properties.baseUrl().lh(),
                properties.serviceKey(),
                "LH",
                responseStatusValidator
        );
    }
}

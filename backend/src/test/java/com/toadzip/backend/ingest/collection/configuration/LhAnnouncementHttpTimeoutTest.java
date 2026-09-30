package com.toadzip.backend.ingest.collection.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.collection.repository.external.LhResponseStatusValidator;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import tools.jackson.databind.json.JsonMapper;

class LhAnnouncementHttpTimeoutTest {

    @Test
    void 응답이_멈추면_LH_전용_읽기_타임아웃으로_연결을_종료한다() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch received = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/slow", exchange -> {
            received.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            finally {
                exchange.close();
            }
        });
        server.start();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var properties = new ExternalDataIngestProperties("test-key",
                    new ExternalDataIngestProperties.BaseUrl("", "",
                            "http://127.0.0.1:" + server.getAddress().getPort()));
            var client = new ExternalDataIngestConfiguration().lhAnnouncementOpenApiClient(
                    JsonMapper.builder().build(), properties,
                    new LhAnnouncementClientProperties(8, Duration.ofSeconds(1), Duration.ofMillis(100)),
                    new LhResponseStatusValidator());
            var request = executor.submit(() -> assertThatThrownBy(
                    () -> client.get("slow", new LinkedMultiValueMap<>()))
                    .isInstanceOfSatisfying(ExternalDataRequestException.class, failure -> {
                        assertThat(failure.isRetryable()).isTrue();
                        assertThat(failure.getMessage()).doesNotContain("test-key");
                    }));
            try {
                assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
                request.get(3, TimeUnit.SECONDS);
            }
            finally {
                release.countDown();
            }
        }
        finally {
            server.stop(0);
        }
    }
}

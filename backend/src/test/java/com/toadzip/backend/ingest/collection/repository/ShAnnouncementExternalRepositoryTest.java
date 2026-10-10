package com.toadzip.backend.ingest.collection.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ShAnnouncementExternalRepositoryTest {

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final ShAnnouncementExternalRepository repository = new ShAnnouncementExternalRepository(builder.build());

    @Test
    void fetchesOnlyFixedOfficialEndpointAndKeepsUtf8Html() {
        server.expect(requestTo(ShAnnouncementExternalRepository.LIST_URL + "&page=2"))
                .andExpect(header("Accept", "text/html"))
                .andRespond(withSuccess("<html>SH 공고</html>", MediaType.TEXT_HTML));

        assertThat(repository.fetchList(2)).isEqualTo("<html>SH 공고</html>");
        server.verify();
    }

    @Test
    void mapsRateLimitWithoutRetryAndServerErrorsToRetryableFailure() {
        server.expect(requestTo(ShAnnouncementExternalRepository.detailUrl("100")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(ShAnnouncementExternalRepository.detailUrl("100")))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> repository.fetchDetail("100"))
                .isInstanceOfSatisfying(ExternalDataRequestException.class, error -> {
                    assertThat(error.isRateLimited()).isTrue();
                    assertThat(error.isRetryable()).isFalse();
                });
        assertThatThrownBy(() -> repository.fetchDetail("100"))
                .isInstanceOfSatisfying(ExternalDataRequestException.class, error ->
                        assertThat(error.isRetryable()).isTrue());
        server.verify();
    }

    @Test
    void rejectsRedirectEmptyBodyAndOversizedResponse() {
        String url = ShAnnouncementExternalRepository.detailUrl("100");
        server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.FOUND).header("Location", "https://other.test"));
        server.expect(requestTo(url)).andRespond(withSuccess("", MediaType.TEXT_HTML));
        server.expect(requestTo(url)).andRespond(withSuccess("x".repeat(2 * 1024 * 1024 + 1), MediaType.TEXT_HTML));

        assertThatThrownBy(() -> repository.fetchDetail("100")).isInstanceOf(ExternalDataRequestException.class);
        assertThatThrownBy(() -> repository.fetchDetail("100")).isInstanceOf(ExternalDataRequestException.class);
        assertThatThrownBy(() -> repository.fetchDetail("100")).isInstanceOf(ExternalDataRequestException.class);
        server.verify();
    }

    @Test
    void rejectsNonNumericIdentifiersBeforeNetworkCall() {
        assertThatThrownBy(() -> repository.fetchDetail("100&seq=200"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.fetchList(0)).isInstanceOf(IllegalArgumentException.class);
        server.verify();
    }
}

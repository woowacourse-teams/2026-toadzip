package com.toadzip.backend.ingest.collection.gh.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.toadzip.backend.ingest.collection.gh.domain.GhAnnouncementBoard;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class GhAnnouncementExternalRepositoryTest {

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final GhAnnouncementExternalRepository repository = new GhAnnouncementExternalRepository(builder.build());

    @Test
    void 게시판별_고정된_공식_목록과_상세_주소만_조회한다() {
        server.expect(requestTo("https://apply.gh.or.kr/sb/sr/sr7155/selectPbancRentHouseList.do?pageIndex=2"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Referer", GhAnnouncementBoard.PURCHASE_RENTAL.listUrl()))
                .andExpect(header("Accept", "text/html"))
                .andRespond(withSuccess("<html>GH 매입임대</html>", MediaType.TEXT_HTML));
        server.expect(requestTo(
                        "https://apply.gh.or.kr/sb/sr/sr7150/selectPbancDetailView.do?pbancNo=801&pbancKndCd=02&previewYn=N"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("<html>GH 상세</html>", MediaType.TEXT_HTML));

        assertThat(repository.fetchList(GhAnnouncementBoard.PURCHASE_RENTAL, 2)).isEqualTo("<html>GH 매입임대</html>");
        assertThat(repository.fetchDetail(GhAnnouncementBoard.RENTAL_HOUSE, "801", "02")).isEqualTo("<html>GH 상세</html>");
        server.verify();
    }

    @Test
    void 호출_제한은_재시도하지_않고_서버_오류는_재시도_대상으로_바꾼다() {
        String url = GhAnnouncementBoard.RENTAL_HOUSE.listUrl(1);
        server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> repository.fetchList(GhAnnouncementBoard.RENTAL_HOUSE, 1))
                .isInstanceOfSatisfying(ExternalDataRequestException.class, error -> {
                    assertThat(error.isRateLimited()).isTrue();
                    assertThat(error.isRetryable()).isFalse();
                });
        assertThatThrownBy(() -> repository.fetchList(GhAnnouncementBoard.RENTAL_HOUSE, 1))
                .isInstanceOfSatisfying(ExternalDataRequestException.class, error ->
                        assertThat(error.isRetryable()).isTrue());
        server.verify();
    }

    @Test
    void 리다이렉트_빈_응답_과대_응답은_실패로_처리한다() {
        String url = GhAnnouncementBoard.RENTAL_HOUSE.detailUrl("801", "01");
        server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.FOUND).header("Location", "https://other.test"));
        server.expect(requestTo(url)).andRespond(withSuccess("", MediaType.TEXT_HTML));
        server.expect(requestTo(url)).andRespond(withSuccess("x".repeat(2 * 1024 * 1024 + 1), MediaType.TEXT_HTML));

        for (int attempt = 0; attempt < 3; attempt++) {
            assertThatThrownBy(() -> repository.fetchDetail(GhAnnouncementBoard.RENTAL_HOUSE, "801", "01"))
                    .isInstanceOf(ExternalDataRequestException.class);
        }
        server.verify();
    }

    @Test
    void 숫자가_아닌_식별자와_잘못된_페이지는_호출_전에_거절한다() {
        assertThatThrownBy(() -> repository.fetchDetail(GhAnnouncementBoard.RENTAL_HOUSE, "801&pbancNo=1", "01"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.fetchDetail(GhAnnouncementBoard.RENTAL_HOUSE, "801", "1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.fetchList(GhAnnouncementBoard.RENTAL_HOUSE, 0))
                .isInstanceOf(IllegalArgumentException.class);
        server.verify();
    }
}

package com.toadzip.backend.ingest.collection.repository.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.ingest.collection.domain.MyHomeAnnouncementSourceSnapshot;
import com.toadzip.backend.ingest.collection.dto.ExternalDataPage;
import com.toadzip.backend.ingest.collection.dto.ExternalDataResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class MyHomeAnnouncementResponseParserTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();
    private final MyHomeAnnouncementResponseParser parser = new MyHomeAnnouncementResponseParser(objectMapper);

    @Test
    @DisplayName("마이홈 공고 응답의 항목과 전체 건수를 파싱한다")
    void parsesItemsAndTotalCount() {
        ExternalDataResponse response = response("""
                {"response":{"header":{"resultCode":"00"},
                "body":{"totalCount":1,"item":[{"pblancId":"A-1","pblancNm":"행복주택"}]}}}
                """);

        ExternalDataPage<MyHomeAnnouncementSourceSnapshot> page = parser.parse(response, 0);

        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.pblancId()).isEqualTo("A-1");
            assertThat(item.pblancNm()).isEqualTo("행복주택");
        });
        assertThat(page.completesCollection(1, 100)).isTrue();
    }

    @Test
    @DisplayName("공고 식별자가 없거나 공백인 항목은 수집에 실패한다")
    void rejectsItemsWithoutAnnouncementIdentifier() {
        ExternalDataResponse missing = response("""
                {"response":{"header":{"resultCode":"00"},
                "body":{"totalCount":2,"item":[{},{}]}}}
                """);
        ExternalDataResponse blank = response("""
                {"response":{"header":{"resultCode":"00"},
                "body":{"totalCount":1,"item":[{"pblancId":"   "}]}}}
                """);

        assertThatThrownBy(() -> parser.parse(missing, 0))
                .isInstanceOf(ExternalDataRequestException.class)
                .hasMessage("마이홈 공고 응답 항목에 공고 식별자가 없습니다.");
        assertThatThrownBy(() -> parser.parse(blank, 0))
                .isInstanceOf(ExternalDataRequestException.class)
                .hasMessage("마이홈 공고 응답 항목에 공고 식별자가 없습니다.");
    }

    @Test
    @DisplayName("성공 응답에 전체 건수가 없으면 실패한다")
    void rejectsMissingTotalCount() {
        ExternalDataResponse response = response("""
                {"response":{"header":{"resultCode":"00"},"body":{"item":[{"pblancId":"A-1"}]}}}
                """);

        assertThatThrownBy(() -> parser.parse(response, 0))
                .isInstanceOf(ExternalDataRequestException.class)
                .hasMessage(
                        "마이홈 공고 응답에 body, item 또는 totalCount 구조가 올바르지 않습니다."
                );
    }

    @Test
    @DisplayName("전체 건수가 0인 성공 응답은 item이 없어도 정상 빈 페이지로 처리한다")
    void parsesExplicitEmptyResponse() {
        ExternalDataPage<MyHomeAnnouncementSourceSnapshot> page = parser.parse(response("""
                {"response":{"header":{"resultCode":"00"},"body":{"totalCount":0}}}
                """), 0);

        assertThat(page.items()).isEmpty();
        assertThat(page.totalCount()).isZero();
    }

    @Test
    @DisplayName("데이터 없음 응답은 정상 빈 페이지로 처리한다")
    void parsesNoDataResponse() {
        ExternalDataPage<MyHomeAnnouncementSourceSnapshot> page = parser.parse(response("""
                {"response":{"header":{"resultCode":"03"}}}
                """), 0);

        assertThat(page.items()).isEmpty();
        assertThat(page.totalCount()).isZero();
    }

    @Test
    @DisplayName("성공 응답에 body가 없으면 실패한다")
    void rejectsMissingBody() {
        ExternalDataResponse response = response("""
                {"response":{"header":{"resultCode":"00"}}}
                """);

        assertThatThrownBy(() -> parser.parse(response, 0))
                .isInstanceOf(ExternalDataRequestException.class)
                .hasMessage(
                        "마이홈 공고 응답에 body, item 또는 totalCount 구조가 올바르지 않습니다."
                );
    }

    @Test
    @DisplayName("성공 응답의 item이 스칼라이면 실패한다")
    void rejectsInvalidItemType() {
        ExternalDataResponse response = response("""
                {"response":{"header":{"resultCode":"00"},"body":{"totalCount":1,"item":"invalid"}}}
                """);

        assertThatThrownBy(() -> parser.parse(response, 0))
                .isInstanceOf(ExternalDataRequestException.class)
                .hasMessage(
                        "마이홈 공고 응답에 body, item 또는 totalCount 구조가 올바르지 않습니다."
                );
    }

    @Test
    @DisplayName("성공 응답의 전체 건수가 음수거나 숫자 타입이 아니면 실패한다")
    void rejectsInvalidTotalCount() {
        ExternalDataResponse negative = response("""
                {"response":{"header":{"resultCode":"00"},"body":{"totalCount":-1,"item":[]}}}
                """);
        ExternalDataResponse booleanValue = response("""
                {"response":{"header":{"resultCode":"00"},"body":{"totalCount":true,"item":[]}}}
                """);

        assertThatThrownBy(() -> parser.parse(negative, 0))
                .isInstanceOf(ExternalDataRequestException.class);
        assertThatThrownBy(() -> parser.parse(booleanValue, 0))
                .isInstanceOf(ExternalDataRequestException.class);
    }

    @Test
    @DisplayName("일부 행을 수집한 뒤 데이터 없음 응답이 오면 실패한다")
    void rejectsNoDataResponseAfterRowsWereCollected() {
        ExternalDataResponse response = response("""
                {"response":{"header":{"resultCode":"03"}}}
                """);

        assertThatThrownBy(() -> parser.parse(response, 1))
                .isInstanceOf(ExternalDataRequestException.class);
    }

    private ExternalDataResponse response(String payload) {
        return new ExternalDataResponse(payload, objectMapper.readTree(payload));
    }
}

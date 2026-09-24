package com.toadzip.backend.ingest.collection.repository.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.ingest.collection.domain.MyHomeComplexSourceSnapshot;
import com.toadzip.backend.ingest.collection.dto.ExternalDataPage;
import com.toadzip.backend.ingest.collection.dto.ExternalDataResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class MyHomeComplexResponseParserTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();
    private final MyHomeComplexResponseParser parser = new MyHomeComplexResponseParser(objectMapper);

    @Test
    @DisplayName("마이홈 단지 응답의 항목과 전체 건수를 파싱한다")
    void parsesItemsAndTotalCount() {
        ExternalDataResponse response = response("""
                {"response":{"header":{"resultCode":"00"},"body":{"totalCount":"1","item":[{"hsmpSn":10,"hsmpNm":"행복 단지"}]}}}
                """);

        MyHomeComplexResponseParser.ValidatedPage page = parser.validate(response, 0);
        ExternalDataPage<MyHomeComplexSourceSnapshot> parsedPage = parser.parseItems(page);

        assertThat(parsedPage.items()).singleElement().satisfies(item -> {
            assertThat(item.hsmpSn()).isEqualTo(10L);
            assertThat(item.hsmpNm()).isEqualTo("행복 단지");
        });
        assertThat(parsedPage.completesCollection(1, 100)).isTrue();
    }

    @Test
    @DisplayName("단지 식별자가 없는 항목은 수집에 실패한다")
    void rejectsItemWithoutComplexIdentifier() {
        ExternalDataResponse response = response("""
                {"response":{"header":{"resultCode":"00"},
                "body":{"totalCount":1,"item":[{"brtcCode":"11","signguCode":"110"}]}}}
                """);

        MyHomeComplexResponseParser.ValidatedPage page = parser.validate(response, 0);

        assertThatThrownBy(() -> parser.parseItems(page))
                .isInstanceOf(ExternalDataRequestException.class)
                .hasMessage("마이홈 단지 응답 항목에 단지 식별자가 없습니다.");
    }

    @Test
    @DisplayName("오류 응답에 빈 body가 있어도 정상 0건으로 처리하지 않는다")
    void rejectsErrorCodeWithEmptyBody() {
        ExternalDataResponse response = response("""
                {"response":{"header":{"resultCode":"99"},"body":{"totalCount":0,"item":[]}}}
                """);

        assertThatThrownBy(() -> parser.validate(response, 0))
                .isInstanceOf(ExternalDataRequestException.class)
                .hasMessage("마이홈 단지 응답 결과 코드가 올바르지 않습니다.");
    }

    @Test
    @DisplayName("첫 페이지 이후 데이터 없음 응답은 불완전한 지역 수집으로 거절한다")
    void rejectsNoDataAfterCollectedRows() {
        ExternalDataResponse response = response("""
                {"response":{"header":{"resultCode":"03"}}}
                """);

        assertThatThrownBy(() -> parser.validate(response, 2))
                .isInstanceOf(ExternalDataRequestException.class);
    }

    @Test
    @DisplayName("전체 건수에 못 미쳤는데 빈 페이지가 오면 불완전한 지역 수집으로 거절한다")
    void rejectsEmptyPageBeforeTotalCount() {
        ExternalDataResponse response = response("""
                {"response":{"header":{"resultCode":"00"},"body":{"totalCount":3,"item":[]}}}
                """);

        assertThatThrownBy(() -> parser.validate(response, 2))
                .isInstanceOf(ExternalDataRequestException.class);
    }

    @Test
    @DisplayName("이전 페이지에 행이 있는데 후속 페이지가 전체 0건으로 응답하면 거절한다")
    void rejectsZeroTotalAfterCollectedRows() {
        ExternalDataResponse response = response("""
                {"response":{"header":{"resultCode":"00"},"body":{"totalCount":0}}}
                """);

        assertThatThrownBy(() -> parser.validate(response, 2))
                .isInstanceOf(ExternalDataRequestException.class);
    }

    @Test
    @DisplayName("응답 행 수가 전체 건수를 넘으면 지역 수집을 거절한다")
    void rejectsRowsBeyondTotalCount() {
        ExternalDataResponse response = response("""
                {"response":{"header":{"resultCode":"00"},"body":{"totalCount":1,"item":[{"hsmpSn":1},{"hsmpSn":2}]}}}
                """);

        assertThatThrownBy(() -> parser.validate(response, 0))
                .isInstanceOf(ExternalDataRequestException.class);
    }

    @Test
    @DisplayName("마이홈 단지 응답의 item 구조가 잘못되면 실패한다")
    void rejectsInvalidItemSchema() {
        ExternalDataResponse response = response("""
                {"response":{"header":{"resultCode":"00"},"body":{"totalCount":1,"item":"invalid"}}}
                """);

        assertThatThrownBy(() -> parser.validate(response, 0))
                .isInstanceOf(ExternalDataRequestException.class)
                .hasMessage("마이홈 단지 응답에 body, item 또는 totalCount 구조가 올바르지 않습니다.");
    }

    private ExternalDataResponse response(String payload) {
        return new ExternalDataResponse(payload, objectMapper.readTree(payload));
    }
}

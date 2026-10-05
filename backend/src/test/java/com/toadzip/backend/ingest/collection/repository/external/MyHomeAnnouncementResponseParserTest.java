package com.toadzip.backend.ingest.collection.repository.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.ingest.collection.myhome.announcement.domain.MyHomeAnnouncementSourceSnapshot;
import com.toadzip.backend.ingest.collection.myhome.announcement.dto.MyHomeAnnouncementCollectionRequest;
import com.toadzip.backend.ingest.collection.myhome.announcement.repository.MyHomeAnnouncementPageParser;
import com.toadzip.backend.ingest.collection.myhome.announcement.service.MyHomeAnnouncementCollectionBuffer;
import com.toadzip.backend.ingest.collection.paging.domain.SourcePage;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class MyHomeAnnouncementResponseParserTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();
    private final MyHomeAnnouncementPageParser parser = new MyHomeAnnouncementPageParser(objectMapper);

    @Test
    @DisplayName("마이홈 공고 응답의 항목과 전체 건수를 파싱한다")
    void parsesItemsAndTotalCount() {
        JsonNode response = response("""
                {"response":{"header":{"resultCode":"00"},
                "body":{"totalCount":1,"item":[{"pblancId":"A-1","houseSn":1,"pblancNm":"행복주택"}]}}}
                """);

        SourcePage<MyHomeAnnouncementSourceSnapshot> page = parseAndValidate(response);

        assertThat(page.rows()).singleElement().satisfies(item -> {
            assertThat(item.pblancId()).isEqualTo("A-1");
            assertThat(item.pblancNm()).isEqualTo("행복주택");
        });
        var buffer = newBuffer();
        buffer.add(page);
        assertThat(buffer.isComplete()).isTrue();
        assertThat(buffer.finish(Instant.parse("2026-10-05T00:00:01Z")).rows()).hasSize(1);
    }

    @Test
    @DisplayName("공고 식별자가 없거나 공백인 항목은 수집에 실패한다")
    void rejectsItemsWithoutAnnouncementIdentifier() {
        JsonNode missing = response("""
                {"response":{"header":{"resultCode":"00"},
                "body":{"totalCount":2,"item":[{},{}]}}}
                """);
        JsonNode blank = response("""
                {"response":{"header":{"resultCode":"00"},
                "body":{"totalCount":1,"item":[{"pblancId":"   "}]}}}
                """);

        assertThatThrownBy(() -> parseAndValidate(missing))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("공고 식별자");
        assertThatThrownBy(() -> parseAndValidate(blank))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("공고 식별자");
    }

    @Test
    @DisplayName("주택 일련번호가 없는 공고 항목은 수집에 실패한다")
    void rejectsItemsWithoutHouseSerialNumber() {
        JsonNode response = response("""
                {"response":{"header":{"resultCode":"00"},
                "body":{"totalCount":2,"item":[{"pblancId":"A-1"},{"pblancId":"A-1"}]}}}
                """);

        assertThatThrownBy(() -> parseAndValidate(response))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("주택 순번");
    }

    @Test
    @DisplayName("성공 응답에 전체 건수가 없으면 실패한다")
    void rejectsMissingTotalCount() {
        JsonNode response = response("""
                {"response":{"header":{"resultCode":"00"},"body":{"item":[{"pblancId":"A-1"}]}}}
                """);

        assertThatThrownBy(() -> parseAndValidate(response))
                .isInstanceOf(ExternalDataRequestException.class)
                .hasMessage(
                        "마이홈 공고 응답의 body, item 또는 totalCount 구조가 올바르지 않습니다."
                );
    }

    @Test
    @DisplayName("전체 건수가 0인 성공 응답은 item이 없어도 정상 빈 페이지로 처리한다")
    void parsesExplicitEmptyResponse() {
        SourcePage<MyHomeAnnouncementSourceSnapshot> page = parseAndValidate(response("""
                {"response":{"header":{"resultCode":"00"},"body":{"totalCount":0}}}
                """));

        assertThat(page.rows()).isEmpty();
        assertThat(page.totalCount()).isZero();
    }

    @Test
    @DisplayName("데이터 없음 응답은 정상 빈 페이지로 처리한다")
    void parsesNoDataResponse() {
        SourcePage<MyHomeAnnouncementSourceSnapshot> page = parseAndValidate(response("""
                {"response":{"header":{"resultCode":"03"}}}
                """));

        assertThat(page.rows()).isEmpty();
        assertThat(page.totalCount()).isZero();
    }

    @Test
    @DisplayName("성공 응답에 body가 없으면 실패한다")
    void rejectsMissingBody() {
        JsonNode response = response("""
                {"response":{"header":{"resultCode":"00"}}}
                """);

        assertThatThrownBy(() -> parseAndValidate(response))
                .isInstanceOf(ExternalDataRequestException.class)
                .hasMessage(
                        "마이홈 공고 응답의 body, item 또는 totalCount 구조가 올바르지 않습니다."
                );
    }

    @Test
    @DisplayName("성공 응답의 item이 스칼라이면 실패한다")
    void rejectsInvalidItemType() {
        JsonNode response = response("""
                {"response":{"header":{"resultCode":"00"},"body":{"totalCount":1,"item":"invalid"}}}
                """);

        assertThatThrownBy(() -> parseAndValidate(response))
                .isInstanceOf(ExternalDataRequestException.class)
                .hasMessage(
                        "마이홈 공고 응답의 body, item 또는 totalCount 구조가 올바르지 않습니다."
                );
    }

    @Test
    @DisplayName("성공 응답의 전체 건수가 음수거나 숫자 타입이 아니면 실패한다")
    void rejectsInvalidTotalCount() {
        JsonNode negative = response("""
                {"response":{"header":{"resultCode":"00"},"body":{"totalCount":-1,"item":[]}}}
                """);
        JsonNode booleanValue = response("""
                {"response":{"header":{"resultCode":"00"},"body":{"totalCount":true,"item":[]}}}
                """);

        assertThatThrownBy(() -> parser.parse(negative))
                .isInstanceOf(ExternalDataRequestException.class);
        assertThatThrownBy(() -> parser.parse(booleanValue))
                .isInstanceOf(ExternalDataRequestException.class);
    }

    @Test
    @DisplayName("일부 행을 수집한 뒤 데이터 없음 응답이 오면 실패한다")
    void rejectsNoDataResponseAfterRowsWereCollected() {
        JsonNode response = response("""
                {"response":{"header":{"resultCode":"03"}}}
                """);

        var buffer = newBuffer();
        buffer.add(parser.parse(response("""
                {"response":{"header":{"resultCode":"00"},"body":{"totalCount":2,
                "item":[{"pblancId":"A-1","houseSn":1}]}}}
                """)));
        assertThat(buffer.isComplete()).isFalse();
        assertThatThrownBy(() -> buffer.add(parser.parse(response)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("전체 건수");
        assertThat(buffer.isComplete()).isFalse();
        assertThatThrownBy(() -> buffer.finish(Instant.parse("2026-10-05T00:00:01Z")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private SourcePage<MyHomeAnnouncementSourceSnapshot> parseAndValidate(JsonNode response) {
        var page = parser.parse(response);
        newBuffer().add(page);
        return page;
    }

    private MyHomeAnnouncementCollectionBuffer newBuffer() {
        return new MyHomeAnnouncementCollectionBuffer(new MyHomeAnnouncementCollectionRequest(
                null, "01", 100, 10, Instant.parse("2026-10-05T00:00:00Z")));
    }

    private JsonNode response(String payload) {
        return objectMapper.readTree(payload);
    }
}

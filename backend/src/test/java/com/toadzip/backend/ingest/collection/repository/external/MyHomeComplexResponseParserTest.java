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
                {"response":{"body":{"totalCount":"1","item":[{"hsmpSn":10,"hsmpNm":"행복 단지"}]}}}
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
    @DisplayName("마이홈 단지 응답의 item 구조가 잘못되면 실패한다")
    void rejectsInvalidItemSchema() {
        ExternalDataResponse response = response("""
                {"response":{"body":{"totalCount":1,"item":"invalid"}}}
                """);

        assertThatThrownBy(() -> parser.validate(response, 0))
                .isInstanceOf(ExternalDataRequestException.class)
                .hasMessage("마이홈 단지 응답에 body, item 또는 totalCount 구조가 올바르지 않습니다.");
    }

    private ExternalDataResponse response(String payload) {
        return new ExternalDataResponse(payload, objectMapper.readTree(payload));
    }
}

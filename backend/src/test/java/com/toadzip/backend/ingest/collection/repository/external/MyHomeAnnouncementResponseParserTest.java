package com.toadzip.backend.ingest.collection.repository.external;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.ingest.collection.dto.ExternalDataPage;
import com.toadzip.backend.ingest.collection.dto.ExternalDataResponse;
import com.toadzip.backend.ingest.collection.dto.MyHomeAnnouncementSourceItem;
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
                {"response":{"body":{"totalCount":1,"item":[{"pblancId":"A-1","pblancNm":"행복주택"}]}}}
                """);

        ExternalDataPage<MyHomeAnnouncementSourceItem> page = parser.parse(response);

        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.pblancId()).isEqualTo("A-1");
            assertThat(item.pblancNm()).isEqualTo("행복주택");
        });
        assertThat(page.completesCollection(1, 100)).isTrue();
    }

    @Test
    @DisplayName("전체 건수가 없으면 페이지 크기로 마지막 페이지를 판단한다")
    void completesByPageSizeWhenTotalCountIsMissing() {
        ExternalDataPage<MyHomeAnnouncementSourceItem> page = parser.parse(response("""
                {"response":{"body":{"item":[{"pblancId":"A-1"}]}}}
                """));

        assertThat(page.completesCollection(1, 2)).isTrue();
    }

    private ExternalDataResponse response(String payload) {
        return new ExternalDataResponse(payload, objectMapper.readTree(payload));
    }
}

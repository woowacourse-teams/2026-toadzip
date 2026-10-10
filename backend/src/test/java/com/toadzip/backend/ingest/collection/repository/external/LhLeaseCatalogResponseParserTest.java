package com.toadzip.backend.ingest.collection.repository.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.ingest.collection.lh.leasecatalog.domain.LhCatalogSourceSnapshot;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.dto.LhLeaseCatalogCollectionRequest;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.repository.LhLeaseCatalogPageParser;
import com.toadzip.backend.ingest.collection.paging.domain.PagedCollectionBuffer;
import com.toadzip.backend.ingest.collection.paging.domain.SourcePage;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class LhLeaseCatalogResponseParserTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();
    private final LhLeaseCatalogPageParser parser = new LhLeaseCatalogPageParser();
    private final LhLeaseCatalogCollectionRequest request = new LhLeaseCatalogCollectionRequest(
            null, 2, 10, Instant.parse("2026-10-05T00:00:00Z"));

    @Test
    @DisplayName("LH 임대 카탈로그 응답을 파싱하고 마지막 페이지를 판단한다")
    void parsesCatalogAndCompletesByTotalCount() {
        SourcePage<LhCatalogSourceSnapshot> page = parseRow("""
                {"ARA_NM":"서울","AIS_TP_CD_NM":"행복주택","SBD_LGO_NM":"가 단지"}
                """);

        assertThat(page.rows()).singleElement().satisfies(item -> {
            assertThat(item.areaName()).isEqualTo("서울");
            assertThat(item.supplyTypeName()).isEqualTo("행복주택");
            assertThat(item.complexLabel()).isEqualTo("가 단지");
            assertThat(item.depositText()).isNull();
        });
        var buffer = new PagedCollectionBuffer<LhCatalogSourceSnapshot>("LH 임대 카탈로그");
        buffer.add(page, LhCatalogSourceSnapshot::validateIdentifiers);
        assertThat(buffer.isComplete()).isTrue();
        assertThat(buffer.finish().rows()).hasSize(1);
    }

    @Test
    @DisplayName("전체 건수를 검증할 수 없는 빈 카탈로그는 거절한다")
    void rejectsUnverifiableEmptyCatalogDataset() {
        JsonNode response = response("""
                [{"resHeader":[{"SS_CODE":"Y"}]},{"dsSch":[{"PAGE":"1","PG_SZ":"2"}]},
                 {"dsList":[]}]
                """);

        assertThatThrownBy(() -> parser.parse(response, request, 1))
                .isInstanceOf(ExternalDataRequestException.class).hasMessageContaining("빈 목록");
    }

    @Test
    @DisplayName("LH 임대 카탈로그 dataset이 없거나 타입이 잘못되면 실패한다")
    void rejectsMissingOrInvalidCatalogDataset() {
        JsonNode missing = response("""
                [{"resHeader":[{"SS_CODE":"Y"}]},{"dsSch":[{"PAGE":"1","PG_SZ":"2"}]}]
                """);
        JsonNode scalar = response("""
                [{"resHeader":[{"SS_CODE":"Y"}]},{"dsSch":[{"PAGE":"1","PG_SZ":"2"}]},
                 {"dsList":"invalid"}]
                """);

        assertThatThrownBy(() -> parser.parse(missing, request, 1))
                .isInstanceOf(ExternalDataRequestException.class).hasMessageContaining("dsList");
        assertThatThrownBy(() -> parser.parse(scalar, request, 1))
                .isInstanceOf(ExternalDataRequestException.class).hasMessageContaining("dsList");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"ARA_NM\":\"서울\",\"AIS_TP_CD_NM\":\"행복주택\"}",
            "{\"ARA_NM\":\"서울\",\"SBD_LGO_NM\":\"가 단지\"}",
            "{\"AIS_TP_CD_NM\":\"행복주택\",\"SBD_LGO_NM\":\"가 단지\"}",
            "{\"AREA_RENAMED\":\"서울\",\"AIS_TP_CD_NM\":\"행복주택\",\"SBD_LGO_NM\":\"가 단지\"}"
    })
    void 식별_정보가_없는_카탈로그_행을_거절한다(String row) {
        assertThatThrownBy(() -> parseRow(row))
                .isInstanceOf(ExternalDataRequestException.class);
    }

    private SourcePage<LhCatalogSourceSnapshot> parseRow(String payload) {
        var root = objectMapper.createArrayNode();
        root.add(response("{\"resHeader\":[{\"SS_CODE\":\"Y\"}]}"));
        root.add(response("{\"dsSch\":[{\"PAGE\":\"1\",\"PG_SZ\":\"2\"}]}"));
        var row = (ObjectNode) response(payload);
        row.put("ALL_CNT", "1");
        row.put("RNUM", "1");
        root.addObject().putArray("dsList").add(row);
        return parser.parse(root, request, 1);
    }

    private JsonNode response(String payload) {
        return objectMapper.readTree(payload);
    }
}

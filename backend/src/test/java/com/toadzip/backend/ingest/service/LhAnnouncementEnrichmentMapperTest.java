package com.toadzip.backend.ingest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.toadzip.backend.ingest.domain.LhAnnouncementDetailSource;
import com.toadzip.backend.ingest.domain.LhAnnouncementSupplySource;
import com.toadzip.backend.ingest.domain.LhAnnouncementSupplySourceData;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;

class LhAnnouncementEnrichmentMapperTest {

    private static final String PAN_ID = "100";

    private final LhAnnouncementEnrichmentMapper mapper = new LhAnnouncementEnrichmentMapper();

    @Test
    void 다단지_공고는_공급행의_단지에_해당하는_입주예정월을_매핑한다() {
        List<LhAnnouncementDetailSource> details = List.of(
                complexDetail(0, "동삼2", "202612"),
                complexDetail(1, "청운3", "202703")
        );
        List<LhAnnouncementSupplySource> supplies = List.of(
                supply(0, "동삼2", "46A"),
                supply(1, "청운3", "59B")
        );

        LhAnnouncementEnrichmentData result = mapper.map(PAN_ID, details, supplies);

        assertThat(result.supplies())
                .extracting(LhSupplyData::complexName, LhSupplyData::expectedMoveInMonth)
                .containsExactly(
                        tuple("동삼2", YearMonth.of(2026, 12)),
                        tuple("청운3", YearMonth.of(2027, 3))
                );
    }

    @Test
    void 다단지_공고에서_단지가_불일치하면_입주예정월을_매핑하지_않는다() {
        List<LhAnnouncementDetailSource> details = List.of(
                complexDetail(0, "동삼2", "202612"),
                complexDetail(1, "청운3", "202703")
        );

        LhAnnouncementEnrichmentData result = mapper.map(
                PAN_ID, details, List.of(supply(0, "매칭되지 않는 단지", "46A"))
        );

        assertThat(result.supplies()).singleElement()
                .extracting(LhSupplyData::expectedMoveInMonth)
                .isNull();
    }

    private LhAnnouncementDetailSource complexDetail(int order, String complexName, String expectedMoveInYearMonth) {
        return new LhAnnouncementDetailSource(
                order, PAN_ID, "COMPLEX", complexName, null, null, null, null, null,
                expectedMoveInYearMonth, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null
        );
    }

    private LhAnnouncementSupplySource supply(int order, String complexName, String housingTypeName) {
        return new LhAnnouncementSupplySource(order, PAN_ID, new LhAnnouncementSupplySourceData(
                complexName, housingTypeName, null, null, "100", "20", "10,000,000", "200,000"
        ));
    }
}

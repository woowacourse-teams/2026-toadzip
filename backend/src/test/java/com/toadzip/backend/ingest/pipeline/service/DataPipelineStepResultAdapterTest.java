package com.toadzip.backend.ingest.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.enrichment.dto.LhAnnouncementEnrichmentReport;
import com.toadzip.backend.ingest.enrichment.dto.LhHousingTypeHouseholdEnrichmentReport;
import com.toadzip.backend.ingest.mapping.dto.MyHomeAnnouncementMappingReport;
import com.toadzip.backend.ingest.mapping.dto.MyHomeComplexMappingReport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class DataPipelineStepResultAdapterTest {

    private final DataPipelineStepResultAdapter adapter = new DataPipelineStepResultAdapter(JsonMapper.builder().build());

    @Test
    void 수집_보고서는_실패_요청을_단계_실패로_호출_한도_실패를_따로_센다() {
        var result = adapter.adapt(new ExternalDataCollectionReport("LH", 3, 2, 5, 0, 1));

        assertThat(result.failureCount()).isEqualTo(2);
        assertThat(result.rateLimitedFailureCount()).isEqualTo(1);
        assertThat(result.warningCount()).isZero();
    }

    @Test
    void 단지_정제는_운영_실패만_단계_실패이고_나머지_실패_행은_경고다() {
        var result = adapter.adapt(new MyHomeComplexMappingReport(0, 0, 0, 0, 0, 0, 0, 5, 0, 2));

        assertThat(result.failureCount()).isEqualTo(2);
        assertThat(result.warningCount()).isEqualTo(3);
    }

    @Test
    void 공고_정제와_LH_보강의_실패는_단계를_멈추지_않는_경고다() {
        var mapping = adapter.adapt(new MyHomeAnnouncementMappingReport(0, 0, 0, 0, 0, 0, 0, 4));
        var household = adapter.adapt(new LhHousingTypeHouseholdEnrichmentReport(0, 0, 0, 0, 2, 3));
        var announcement = adapter.adapt(new LhAnnouncementEnrichmentReport(0, 0, 0, 0, 0, 0, 0, 0, 0, 6));

        assertThat(mapping.failureCount()).isZero();
        assertThat(mapping.warningCount()).isEqualTo(4);
        assertThat(household.failureCount()).isZero();
        assertThat(household.warningCount()).isEqualTo(5);
        assertThat(announcement.failureCount()).isZero();
        assertThat(announcement.warningCount()).isEqualTo(6);
    }

    @Test
    void 저장하는_응답에는_보고서_필드만_있고_단계_계약_값은_없다() {
        var result = adapter.adapt(new MyHomeAnnouncementMappingReport(1, 0, 0, 0, 0, 0, 0, 4));

        assertThat(result.serverResponse())
                .contains("\"failedSourceRowCount\":4")
                .doesNotContain("stepFailureCount", "stepRateLimitedFailureCount", "stepWarningCount");
    }
}

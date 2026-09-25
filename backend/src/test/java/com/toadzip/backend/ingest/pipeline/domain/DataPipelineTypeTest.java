package com.toadzip.backend.ingest.pipeline.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class DataPipelineTypeTest {

    @Test
    void 파이프라인_유형이_실행할_단계와_순서를_명시적으로_제공한다() {
        assertThat(DataPipelineType.ANNOUNCEMENT_COLLECTION.steps()).containsExactlyElementsOf(List.of(
                DataPipelineStep.COLLECT_MYHOME_ANNOUNCEMENTS,
                DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_CATALOG,
                DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_SUPPLIES,
                DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_DETAILS
        ));
    }
}

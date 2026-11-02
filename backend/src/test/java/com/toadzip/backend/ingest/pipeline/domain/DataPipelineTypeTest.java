package com.toadzip.backend.ingest.pipeline.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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

    @ParameterizedTest
    @CsvSource({
            "COMPLEX_COLLECTION, false",
            "COMPLEX_REFINEMENT, true",
            "ANNOUNCEMENT_COLLECTION, false",
            "ANNOUNCEMENT_REFINEMENT, false",
            "COMPLEX_SYNC, true",
            "ANNOUNCEMENT_SYNC, false",
            "ANNOUNCEMENT_REGISTRATION, true",
            "SH_ANNOUNCEMENT_COLLECTION, false"
    })
    void 유형마다_지원_종료_여부가_정해져_있다(DataPipelineType type, boolean retired) {
        assertThat(type.isRetired()).isEqualTo(retired);
    }

    @ParameterizedTest
    @CsvSource({
            "COMPLEX_COLLECTION, false",
            "COMPLEX_REFINEMENT, false",
            "ANNOUNCEMENT_COLLECTION, false",
            "ANNOUNCEMENT_REFINEMENT, false",
            "COMPLEX_SYNC, true",
            "ANNOUNCEMENT_SYNC, true",
            "ANNOUNCEMENT_REGISTRATION, true",
            "SH_ANNOUNCEMENT_COLLECTION, false"
    })
    void 수집과_정제를_잇는_유형만_정제_단계에_수집_성공을_요구한다(DataPipelineType type, boolean required) {
        assertThat(type.requiresSuccessfulCollection(DataPipelineStep.MAP_LH_ANNOUNCEMENTS)).isEqualTo(required);
        assertThat(type.requiresSuccessfulCollection(DataPipelineStep.COLLECT_MYHOME_ANNOUNCEMENTS)).isFalse();
    }

    @Test
    void 수집_단계와_정제_단계를_구분한다() {
        List<DataPipelineStep> collections = Arrays.stream(DataPipelineStep.values())
                .filter(DataPipelineStep::isCollection)
                .toList();

        assertThat(collections).containsExactly(
                DataPipelineStep.COLLECT_SH_ANNOUNCEMENTS,
                DataPipelineStep.COLLECT_MYHOME_COMPLEXES,
                DataPipelineStep.COLLECT_LH_LEASE_CATALOG,
                DataPipelineStep.COLLECT_MYHOME_ANNOUNCEMENTS,
                DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_CATALOG,
                DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_SUPPLIES,
                DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_DETAILS
        );
    }
}

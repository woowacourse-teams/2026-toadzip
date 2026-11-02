package com.toadzip.backend.ingest.pipeline.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.ingest.exception.exception.RetiredIngestOperationException;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

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
    @EnumSource(value = DataPipelineType.class, names = {
            "COMPLEX_COLLECTION", "ANNOUNCEMENT_COLLECTION", "ANNOUNCEMENT_REFINEMENT", "ANNOUNCEMENT_SYNC",
            "SH_ANNOUNCEMENT_COLLECTION"
    })
    void 실행할_수_있는_유형은_거절하지_않는다(DataPipelineType type) {
        assertThatCode(type::rejectIfRetired).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(value = DataPipelineType.class, names = {"COMPLEX_REFINEMENT", "COMPLEX_SYNC"})
    void 지원을_종료한_단지_정제_유형은_단지_정제_종료_안내로_거절한다(DataPipelineType type) {
        assertThatThrownBy(type::rejectIfRetired)
                .isInstanceOf(RetiredIngestOperationException.class)
                .hasMessage("단지 정제는 지원을 종료했습니다. 단지·주택형은 공고와 PDF 보정으로만 바뀝니다.");
    }

    @Test
    void 지원을_종료한_단건_등록은_마이홈_공고_정제_종료_안내로_거절한다() {
        assertThatThrownBy(DataPipelineType.ANNOUNCEMENT_REGISTRATION::rejectIfRetired)
                .isInstanceOf(RetiredIngestOperationException.class)
                .hasMessage("마이홈 공고 정제·LH 보강과 그 보정은 지원을 종료했습니다. 공고는 LH 공고 정제와 PDF 보정으로만 바뀝니다.");
    }

    @Test
    void 이력_구분마다_속한_유형을_선언_순서로_제공한다() {
        assertThat(DataPipelineType.ofDomain(DataPipelineType.Domain.COMPLEX)).containsExactly(
                DataPipelineType.COMPLEX_COLLECTION, DataPipelineType.COMPLEX_REFINEMENT, DataPipelineType.COMPLEX_SYNC
        );
        assertThat(DataPipelineType.ofDomain(DataPipelineType.Domain.SH_ANNOUNCEMENT))
                .containsExactly(DataPipelineType.SH_ANNOUNCEMENT_COLLECTION);
    }

    @Test
    void LH_공고_API를_함께_쓰는_수집_단계를_구분한다() {
        List<DataPipelineStep> lhAnnouncementCollections = Arrays.stream(DataPipelineStep.values())
                .filter(DataPipelineStep::isLhAnnouncementCollection)
                .toList();

        assertThat(lhAnnouncementCollections).containsExactly(
                DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_CATALOG,
                DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_SUPPLIES,
                DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_DETAILS
        );
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

package com.toadzip.backend.ingest.pipeline.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementExternalCollectionService;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.collection.myhome.announcement.repository.MyHomeAnnouncementSourceReader;
import com.toadzip.backend.ingest.collection.myhome.announcement.service.MyHomeAnnouncementCollector;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.collection.service.ExternalDataRetryExecutor;
import com.toadzip.backend.ingest.exception.exception.ExternalDataCallFailureException;
import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementMappingService;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineStep;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class AnnouncementRegistrationServiceTest {

    @Mock private AnnouncementRepository announcements;
    @Mock private MyHomeAnnouncementCollector collector;
    @Mock private ExternalDataRetryExecutor retry;
    @Mock private MyHomeAnnouncementSourceReader sources;
    @Mock private LhAnnouncementExternalCollectionService lhCollection;
    @Mock private MyHomeAnnouncementMappingService mapping;
    @Mock private MyHomeAnnouncementSource source;
    @Mock private Announcement announcement;
    private AnnouncementRegistrationService service;

    @BeforeEach
    void setUp() {
        service = new AnnouncementRegistrationService(announcements, collector, retry, sources, lhCollection,
                mapping, new DataPipelineStepResultAdapter(JsonMapper.builder().build()));
    }

    @Test
    void 기존_공고는_외부_API를_호출하기_전에_거절한다() {
        when(announcements.findBySourceAnnouncementIdentifier("21026")).thenReturn(Optional.of(announcement));

        assertThatThrownBy(() -> service.execute(DataPipelineStep.COLLECT_MYHOME_ANNOUNCEMENTS, "21026"))
                .hasMessageContaining("이미 등록");

        verifyNoInteractions(collector, lhCollection, mapping);
    }

    @Test
    void 없는_공고는_구체적인_실패_사유를_전달한다() {
        when(collector.collectAnnouncement("missing", retry))
                .thenThrow(new InvalidIngestRequestException("공고를 찾을 수 없습니다: missing"));

        assertThatThrownBy(() -> service.execute(DataPipelineStep.COLLECT_MYHOME_ANNOUNCEMENTS, "missing"))
                .hasMessageContaining("공고를 찾을 수 없습니다");

        verifyNoInteractions(lhCollection, mapping);
    }

    @Test
    void 마이홈_호출_제한을_구분해_전달한다() {
        when(collector.collectAnnouncement("21026", retry)).thenThrow(new ExternalDataCallFailureException(
                ExternalDataSource.MYHOME_ANNOUNCEMENT, "page=1", 1,
                ExternalDataRequestException.rateLimited("limit")));

        assertThatThrownBy(() -> service.execute(DataPipelineStep.COLLECT_MYHOME_ANNOUNCEMENTS, "21026"))
                .hasMessageContaining("호출 제한");
    }

    @Test
    void 다른_기관의_공고는_LH_수집을_하지_않는다() {
        when(sources.findAllByPblancIdOrderByIdAsc("21026")).thenReturn(List.of(source));
        when(source.getSuplyInsttNm()).thenReturn("부산도시공사");

        assertThat(service.execute(DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_SUPPLIES, "21026").skipReason())
                .contains("해당 없음");
        assertThat(service.execute(DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_DETAILS, "21026").skipReason())
                .contains("해당 없음");

        verifyNoInteractions(lhCollection);
    }

    @Test
    void LH_공고는_입력한_공고의_공급과_상세만_강제_조회한다() {
        when(sources.findAllByPblancIdOrderByIdAsc("21026")).thenReturn(List.of(source));
        when(source.getSuplyInsttNm()).thenReturn("LH");
        when(lhCollection.refresh(any(), eq("21026"))).thenReturn(
                new ExternalDataCollectionReport("lh", 1, 0, 1, 0, 0, 1));

        service.execute(DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_SUPPLIES, "21026");
        service.execute(DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_DETAILS, "21026");

        verify(lhCollection).refresh(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, "21026");
        verify(lhCollection).refresh(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL, "21026");
        verify(lhCollection, never()).collect(any());
    }

    @Test
    void LH_수집_부분_실패를_등록_성공으로_취급하지_않는다() {
        when(sources.findAllByPblancIdOrderByIdAsc("21026")).thenReturn(List.of(source));
        when(source.getSuplyInsttNm()).thenReturn("LH");
        when(lhCollection.refresh(any(), eq("21026"))).thenReturn(
                new ExternalDataCollectionReport("lh", 0, 1, 1, 0, 0, 0));

        assertThatThrownBy(() -> service.execute(DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_SUPPLIES, "21026"))
                .hasMessageContaining("수집에 실패");

        verifyNoInteractions(mapping);
    }
}

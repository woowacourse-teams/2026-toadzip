package com.toadzip.backend.ingest.enrichment.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.ingest.collection.lh.dto.LhAnnouncementRequest;
import com.toadzip.backend.ingest.enrichment.repository.LhAnnouncementEnrichmentFailureStore;
import com.toadzip.backend.ingest.enrichment.service.LhAnnouncementEnrichmentMapper.LhAnnouncementEnrichmentData;
import com.toadzip.backend.ingest.pipeline.service.DataPipelineStoppedException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LhAnnouncementEnrichmentWriterTest {

    @Mock
    private AnnouncementRepository announcementRepository;

    @Mock
    private LhAnnouncementEnrichmentSources sources;

    @Mock
    private LhScheduleAttachmentWriter scheduleAttachments;

    @Mock
    private LhSupplyAmountWriter supplyAmounts;

    @Mock
    private LhAnnouncementEnrichmentFailureStore failureStore;

    @Test
    void 매핑_후_보강_저장이_중지되면_실패를_해결하지_않는다() {
        Announcement announcement = mock(Announcement.class);
        LhAnnouncementRequest request = new LhAnnouncementRequest("100", "03", "06", "08", "062");
        when(announcement.getId()).thenReturn(1L);
        when(sources.read(any(), any(), any())).thenReturn(new LhAnnouncementEnrichmentData(
                "100", null, null, List.of(), List.of(), List.of(), false
        ));
        when(announcementRepository.findByIdForUpdate(1L)).thenThrow(new DataPipelineStoppedException());
        LhAnnouncementEnrichmentWriter writer = new LhAnnouncementEnrichmentWriter(
                announcementRepository, sources, scheduleAttachments, supplyAmounts, failureStore
        );

        assertThatThrownBy(() -> writer.writeAfterMapping(announcement, request, List.of(), Set.of(), Set.of(), Map.of()))
                .isInstanceOf(DataPipelineStoppedException.class);

        verifyNoInteractions(failureStore, scheduleAttachments, supplyAmounts);
    }
}

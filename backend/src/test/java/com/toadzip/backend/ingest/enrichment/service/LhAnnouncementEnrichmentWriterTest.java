package com.toadzip.backend.ingest.enrichment.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.repository.AnnouncementAttachmentRepository;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.AnnouncementScheduleRepository;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.announcement.repository.SupplyTargetRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LhAnnouncementEnrichmentWriterTest {

    @Mock
    private AnnouncementScheduleRepository scheduleRepository;

    @Mock
    private AnnouncementRepository announcementRepository;

    @Mock
    private AnnouncementAttachmentRepository attachmentRepository;

    @Mock
    private SupplyRowRepository supplyRowRepository;

    @Mock
    private SupplyTargetRepository supplyTargetRepository;

    @Mock
    private LhAnnouncementSupplyMatcher supplyMatcher;

    @Test
    void 공고의_공급대상을_한번_조회해_보강과_정리에_재사용한다() {
        Announcement announcement = mock(Announcement.class);
        SupplyRow row = mock(SupplyRow.class);
        LhSupplyData supply = new LhSupplyData("LH:100:1", "단지", "주택형", null,
                null, null, null, null);
        when(announcementRepository.save(announcement)).thenReturn(announcement);
        when(supplyRowRepository.findAllByAnnouncement(announcement)).thenReturn(List.of(row));
        when(row.getId()).thenReturn(1L);
        when(supplyMatcher.match(List.of(row), supply)).thenReturn(LhSupplyMatchResult.matched(row));

        LhAnnouncementEnrichmentWriter writer = new LhAnnouncementEnrichmentWriter(
                scheduleRepository, announcementRepository, attachmentRepository,
                supplyRowRepository, supplyTargetRepository, supplyMatcher
        );
        writer.write(announcement, new LhAnnouncementEnrichmentData(
                "100", null, null, List.of(), List.of(), List.of(supply)
        ));

        verify(supplyTargetRepository).findAllBySupplyRowIdIn(List.of(1L));
        verify(supplyTargetRepository, never()).findAllBySupplyRow(any());
    }
}

package com.toadzip.backend.ingest.enrichment.service;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.ingest.collection.lh.dto.LhAnnouncementRequest;
import com.toadzip.backend.ingest.collection.lh.supply.domain.LhAnnouncementSupplySourceView;
import com.toadzip.backend.ingest.enrichment.domain.LhAnnouncementEnrichmentFailureReason;
import com.toadzip.backend.ingest.enrichment.dto.LhAnnouncementEnrichmentReport;
import com.toadzip.backend.ingest.enrichment.repository.LhAnnouncementEnrichmentFailureStore;
import com.toadzip.backend.ingest.enrichment.service.LhAnnouncementEnrichmentMapper.LhAnnouncementEnrichmentData;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공고 한 건에 LH 보강을 쓴다. 공고를 잠그고 LH 원천을 바꿀 수 없는 공고를 거절한 뒤 공고 값, 일정·첨부, 공급행 금액을
 * 차례로 쓴다.
 */
@Component
@RequiredArgsConstructor
public class LhAnnouncementEnrichmentWriter implements MappedAnnouncementLhEnrichment {

    private final AnnouncementRepository announcementRepository;
    private final LhAnnouncementEnrichmentSources sources;
    private final LhScheduleAttachmentWriter scheduleAttachments;
    private final LhSupplyAmountWriter supplyAmounts;
    private final LhAnnouncementEnrichmentFailureStore failureStore;

    @Override
    @Transactional
    public void writeAfterMapping(
            Announcement announcement,
            LhAnnouncementRequest request,
            List<LhAnnouncementSupplySourceView> supplies,
            Set<Long> changedHousingTypeRowIds,
            Set<String> sourceKeysExcludedFromLhEnrichment,
            Map<String, LhSupplyRowLinks.State> supplyLinks
    ) {
        if (announcement.getSupplyType() == RentalType.ETC) {
            throw new LhAnnouncementEnrichmentRejectedException(
                    LhAnnouncementEnrichmentFailureReason.UNSUPPORTED_SUPPLY_TYPE,
                    "지원하지 않는 공급유형의 LH 공고입니다."
            );
        }
        LhAnnouncementEnrichmentWriteResult result = write(announcement, sources.read(request, supplies, supplyLinks),
                changedHousingTypeRowIds, sourceKeysExcludedFromLhEnrichment);
        if (!result.failures().isEmpty()) {
            LhSupplyMatchingFailureData failure = result.failures().getFirst();
            throw new LhAnnouncementEnrichmentRejectedException(failure.reason(), failure.detail());
        }
        failureStore.reconcileForAnnouncement(
                announcement.getSourceAnnouncementIdentifier(), List.of(),
                IngestExecutionScope.currentExecutionId().orElse(null)
        );
    }

    @Override
    @Transactional
    public void releaseLhOwnership(Announcement announcement) {
        scheduleAttachments.release(announcement);
        supplyAmounts.release(announcement);
    }

    @Override
    @Transactional
    public void releaseMovedLhRows(
            Announcement announcement, Map<String, String> resolvedSourceRows, Set<String> movedLhRows
    ) {
        supplyAmounts.releaseMovedLhRows(announcement, resolvedSourceRows, movedLhRows);
    }

    /** 공급행 매칭 실패는 결과로 돌려준다. 확인된 공고의 LH 원천과 보강 대상이 다르면 쓰기 전에 거절한다. */
    @Transactional
    public LhAnnouncementEnrichmentWriteResult write(
            Announcement announcement,
            LhAnnouncementEnrichmentData data,
            Set<Long> changedHousingTypeRowIds,
            Set<String> sourceKeysExcludedFromLhEnrichment
    ) {
        Announcement managedAnnouncement = announcementRepository.findByIdForUpdate(announcement.getId())
                .orElseThrow(() -> new IllegalStateException("보강할 공고가 없습니다."));
        String previousPanId = managedAnnouncement.getLhPanId();
        if ((managedAnnouncement.isLhPanIdReviewed()
                || managedAnnouncement.isAdminModified() || managedAnnouncement.isAdminDeleted())
                && !data.panId().equals(previousPanId)) {
            throw new LhAnnouncementEnrichmentRejectedException(
                    LhAnnouncementEnrichmentFailureReason.LH_COLLECTION_LINK_MISMATCH,
                    "확인된 공고의 LH 원천과 보강 대상이 다릅니다."
            );
        }
        int updatedAnnouncements = 0;
        if (managedAnnouncement.enrichFromLh(
                data.panId(), data.correctionReason(), data.receptionPlace()
        )) {
            updatedAnnouncements = 1;
        }
        var children = scheduleAttachments.write(managedAnnouncement, data, previousPanId);
        var supplies = supplyAmounts.write(
                managedAnnouncement, data, changedHousingTypeRowIds, sourceKeysExcludedFromLhEnrichment
        );
        return new LhAnnouncementEnrichmentWriteResult(
                new LhAnnouncementEnrichmentReport(
                        updatedAnnouncements, 1 - updatedAnnouncements,
                        children.createdSchedules(), children.updatedSchedules(),
                        children.createdAttachments(), children.updatedAttachments(),
                        supplies.updatedRows(), supplies.createdTargets(), supplies.updatedTargets(),
                        supplies.failures().size()
                ),
                supplies.failures()
        );
    }

    record LhAnnouncementEnrichmentWriteResult(
            LhAnnouncementEnrichmentReport report,
            List<LhSupplyMatchingFailureData> failures
    ) {
    }
}

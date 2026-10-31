package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.ingest.enrichment.service.LhAnnouncementEnrichmentMapper.LhAnnouncementEnrichmentRejectedException;
import com.toadzip.backend.ingest.enrichment.service.MappedAnnouncementLhEnrichment;
import com.toadzip.backend.ingest.exception.exception.AnnouncementRegistrationException;
import com.toadzip.backend.ingest.mapping.domain.MyHomeAnnouncementMappingFailureReason;
import com.toadzip.backend.ingest.mapping.dto.MyHomeAnnouncementMappingReport;
import com.toadzip.backend.ingest.mapping.repository.MyHomeAnnouncementMappingFailureStore;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementSourceMapper.MyHomeAnnouncementMappingData;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementSourceMapper.MyHomeAnnouncementMappingRejectedException;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementSupplyMatcher.MyHomeSupplyMatchResult;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementSupplyMatcher.MyHomeSupplyMatchingFailureData;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementSupplyRowResolver.ResolvedAnnouncement;
import com.toadzip.backend.ingest.mapping.service.MyHomeSupplyRowWriter.SupplyRowsWriteResult;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.toadzip.backend.notification.repository.NotificationOutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class MyHomeAnnouncementMappingWriter {

    private final NotificationOutboxRepository notificationOutbox;

    private final AnnouncementRepository announcementRepository;

    private final SupplyRowRepository supplyRowRepository;

    private final MyHomeSupplyRowWriter supplyRowWriter;

    private final MappedAnnouncementLhEnrichment lhEnrichment;

    private final MyHomeAnnouncementMappingFailureStore failureStore;
    private final AnnouncementSupplySplitWriter splitWriter;

    /** 신규 공고·공급행·LH 보강을 하나의 트랜잭션으로 저장한다. */
    @Transactional
    public MyHomeAnnouncementWriteResult register(ResolvedAnnouncement resolved, Announcement previous) {
        IngestExecutionScope.verifyCanContinue();
        String identifier = resolved.data().sourceAnnouncementIdentifier();
        if (announcementRepository.findBySourceAnnouncementIdentifierForUpdate(identifier).isPresent()) {
            throw new AnnouncementRegistrationException("이미 등록된 공고입니다: " + identifier);
        }
        return writeRegistration(resolved, previous, Map.of());
    }

    /**
     * 요청에서 검증한 선택과 원천행 분할을 이번 정제에 함께 적용한다. 분할을 먼저 적용하고 나머지 행을 정제한다.
     * 선택을 기억하는 규칙은 호출한 쪽이 저장한다.
     */
    @Transactional
    public MyHomeAnnouncementWriteResult writeSelected(ResolvedAnnouncement resolved, Announcement previous,
            Map<String, MyHomeSupplyMatchResult> selections, List<AnnouncementSupplySplitWriter.SplitPlan> plans) {
        return writeRegistration(resolved, previous, selections, plans);
    }

    @Transactional
    public MyHomeAnnouncementWriteResult writeSplit(ResolvedAnnouncement resolved, Announcement previous,
            AnnouncementSupplySplitWriter.SplitPlan plan) {
        return writeRegistration(resolved, previous, Map.of(), List.of(plan));
    }

    /** 단건 등록은 LH 보강을 재검증하고 실패 시 전체 저장을 되돌린다. */
    private MyHomeAnnouncementWriteResult writeRegistration(ResolvedAnnouncement resolved, Announcement previous,
            Map<String, MyHomeSupplyMatchResult> selections) {
        return writeRegistration(resolved, previous, selections, List.of());
    }

    private MyHomeAnnouncementWriteResult writeRegistration(ResolvedAnnouncement resolved, Announcement previous,
            Map<String, MyHomeSupplyMatchResult> selections, List<AnnouncementSupplySplitWriter.SplitPlan> plans) {
        IngestExecutionScope.verifyCanContinue();
        String identifier = resolved.data().sourceAnnouncementIdentifier();
        MyHomeAnnouncementWriteResult result = writeResolved(resolved, previous, selections, plans);
        if (!result.failures().isEmpty()) {
            MyHomeSupplyMatchingFailureData failure = result.failures().getFirst();
            throw new MyHomeAnnouncementMappingRejectedException(
                    failure.reason(), "공급행 매칭 실패: " + failure.detail());
        }
        if (resolved.data().provider() == AgencyCode.LH) {
            if (resolved.request() == null) {
                throw new MyHomeAnnouncementMappingRejectedException(
                        MyHomeAnnouncementMappingFailureReason.INVALID_VALUE, "연결된 LH 공고가 없습니다.");
            }
            Announcement announcement = announcementRepository.findBySourceAnnouncementIdentifier(identifier)
                    .orElseThrow();
            enrichLh(announcement, resolved, Set.of());
        }
        failureStore.reconcileForAnnouncement(
                identifier, List.of(), IngestExecutionScope.currentExecutionId().orElse(null));
        IngestExecutionScope.verifyCanContinue();
        announcementRepository.flush();
        return result;
    }

    /** 주택형 변경과 LH 금액 보강을 함께 저장한다. 보강이 실패하면 공고 한 건을 모두 되돌린다. */
    @Transactional
    public MyHomeAnnouncementWriteResult write(
            ResolvedAnnouncement resolved,
            Announcement previousAnnouncement
    ) {
        return writeResolved(resolved, previousAnnouncement, Map.of());
    }

    private MyHomeAnnouncementWriteResult writeResolved(ResolvedAnnouncement resolved,
            Announcement previousAnnouncement, Map<String, MyHomeSupplyMatchResult> selections) {
        return writeResolved(resolved, previousAnnouncement, selections, List.of());
    }

    private MyHomeAnnouncementWriteResult writeResolved(ResolvedAnnouncement resolved,
            Announcement previousAnnouncement, Map<String, MyHomeSupplyMatchResult> selections,
            List<AnnouncementSupplySplitWriter.SplitPlan> plans) {
        MyHomeAnnouncementMappingData data = resolved.data();
        AnnouncementWriteResult announcementResult = writeAnnouncement(data, previousAnnouncement);
        if (announcementResult.announcement().getProvider() != data.provider()) {
            int preservedRowCount = supplyRowRepository.findAllByAnnouncement(announcementResult.announcement()).size();
            var preserved = new SupplyRowsWriteResult(0, 0, preservedRowCount, 0, List.of(), Set.of());
            return new MyHomeAnnouncementWriteResult(reportOf(announcementResult, preserved), List.of());
        }
        if (announcementResult.releasedLhOwnership()) {
            lhEnrichment.releaseLhOwnership(announcementResult.announcement());
        }
        for (var plan : plans) {
            splitWriter.apply(announcementResult.announcement(), plan);
        }
        SupplyRowsWriteResult supplyRowsResult = supplyRowWriter.write(
                announcementResult.announcement(),
                data.supplyRows(),
                resolved.preserveExistingLhResolvedRows(),
                resolved.historicalSourceKeys(),
                selections
        );
        enrichChangedAnnouncement(resolved, announcementResult, supplyRowsResult);
        if (announcementResult.created() == 1) {
            announcementRepository.flush();
            notificationOutbox.appendNewAnnouncement(announcementResult.announcement());
        }
        return new MyHomeAnnouncementWriteResult(
                reportOf(announcementResult, supplyRowsResult),
                supplyRowsResult.failures()
        );
    }

    private void enrichChangedAnnouncement(
            ResolvedAnnouncement resolved,
            AnnouncementWriteResult announcementResult,
            SupplyRowsWriteResult supplyRowsResult
    ) {
        Announcement announcement = announcementResult.announcement();
        if (resolved.request() == null || announcement.getProvider() != AgencyCode.LH
                || announcement.getLhPanId() == null) {
            return;
        }
        if (announcementResult.updated() == 0 && supplyRowsResult.created() == 0
                && supplyRowsResult.updated() == 0 && supplyRowsResult.deleted() == 0) {
            return;
        }
        enrichLh(announcement, resolved, supplyRowsResult.changedHousingTypeRowIds());
    }

    private void enrichLh(Announcement announcement, ResolvedAnnouncement resolved, Set<Long> changedHousingTypeRowIds) {
        try {
            lhEnrichment.writeAfterMapping(announcement, resolved.request(), resolved.supplies(),
                    changedHousingTypeRowIds, resolved.sourceKeysExcludedFromLhEnrichment(), resolved.supplyLinks());
        }
        catch (LhAnnouncementEnrichmentRejectedException exception) {
            throw new LhEnrichmentRejectedMappingException(exception);
        }
    }

    private AnnouncementWriteResult writeAnnouncement(
            MyHomeAnnouncementMappingData data,
            Announcement previousAnnouncement
    ) {
        Announcement stored = announcementRepository
                .findBySourceAnnouncementIdentifierForUpdate(data.sourceAnnouncementIdentifier())
                .orElse(null);
        if (stored == null) {
            Announcement created = announcementRepository.save(Announcement.create(
                    data.sourceAnnouncementIdentifier(),
                    data.previousSourceAnnouncementIdentifier(),
                    previousAnnouncement,
                    data.name(),
                    data.publicationType(),
                    data.rentalType(),
                    data.recruitmentType(),
                    data.provider(),
                    data.postedDate(),
                    data.applicationStartDate(),
                    data.applicationEndDate(),
                    data.winnerAnnouncementDate(),
                    data.originalUrl(),
                    null,
                    0L,
                    data.receptionPlace()
            ));
            return new AnnouncementWriteResult(created, 1, 0, false);
        }
        if (stored.isLhPanIdReviewed() && data.provider() != AgencyCode.LH) {
            throw new MyHomeAnnouncementMappingRejectedException(
                    MyHomeAnnouncementMappingFailureReason.INVALID_VALUE,
                    "확인된 LH 공고의 기관을 바꿀 수 없습니다."
            );
        }
        boolean releasesLhOwnership = stored.getLhPanId() != null && data.provider() != AgencyCode.LH;
        boolean updated = stored.updateFromMyHome(
                data.previousSourceAnnouncementIdentifier(),
                previousAnnouncement,
                data.name(),
                data.publicationType(),
                data.rentalType(),
                data.recruitmentType(),
                data.provider(),
                data.postedDate(),
                data.applicationStartDate(),
                data.applicationEndDate(),
                data.winnerAnnouncementDate(),
                data.originalUrl(),
                data.receptionPlace()
        );
        if (updated) {
            return new AnnouncementWriteResult(stored, 0, 1, releasesLhOwnership);
        }
        return new AnnouncementWriteResult(stored, 0, 0, false);
    }

    private MyHomeAnnouncementMappingReport reportOf(
            AnnouncementWriteResult announcement,
            SupplyRowsWriteResult supplyRows
    ) {
        return new MyHomeAnnouncementMappingReport(
                announcement.created(),
                announcement.updated(),
                announcement.unchanged(),
                supplyRows.created(),
                supplyRows.updated(),
                supplyRows.unchanged(),
                supplyRows.deleted(),
                supplyRows.failures().size()
        );
    }

    private record AnnouncementWriteResult(
            Announcement announcement,
            int created,
            int updated,
            boolean releasedLhOwnership
    ) {

        int unchanged() {
            return 1 - created - updated;
        }
    }

    record MyHomeAnnouncementWriteResult(
            MyHomeAnnouncementMappingReport report,
            List<MyHomeSupplyMatchingFailureData> failures
    ) {
    }

    /** 전체 정제와 단건 정제가 같은 실패로 기록할 수 있도록 LH 보강 거절을 다른 정제 거절과 구분한다. */
    static class LhEnrichmentRejectedMappingException extends MyHomeAnnouncementMappingRejectedException {

        LhEnrichmentRejectedMappingException(LhAnnouncementEnrichmentRejectedException cause) {
            super(MyHomeAnnouncementMappingFailureReason.INVALID_VALUE,
                    "LH 보강 실패: " + cause.reason() + " - " + cause.getMessage());
        }
    }
}

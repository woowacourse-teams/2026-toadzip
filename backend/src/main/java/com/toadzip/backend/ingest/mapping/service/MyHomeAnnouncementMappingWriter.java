package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementAttachment;
import com.toadzip.backend.announcement.domain.AnnouncementSchedule;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.domain.SupplyTarget;
import com.toadzip.backend.announcement.repository.AnnouncementAttachmentRepository;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.AnnouncementScheduleRepository;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.announcement.repository.SupplyTargetRepository;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.ingest.collection.domain.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.domain.MyHomeAnnouncementSupplyRowGroups;
import com.toadzip.backend.ingest.enrichment.service.LhAnnouncementEnrichmentMapper.LhAnnouncementEnrichmentRejectedException;
import com.toadzip.backend.ingest.enrichment.service.LhAnnouncementEnrichmentWriter;
import com.toadzip.backend.ingest.mapping.domain.MyHomeAnnouncementMappingFailureReason;
import com.toadzip.backend.ingest.mapping.dto.MyHomeAnnouncementMappingReport;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementSourceMapper.MyHomeAnnouncementMappingData;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementSourceMapper.MyHomeAnnouncementMappingRejectedException;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementSourceMapper.MyHomeSupplyRowMappingData;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementSupplyMatcher.MyHomeSupplyMatchResult;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementSupplyRowResolver.ResolvedAnnouncement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class MyHomeAnnouncementMappingWriter {

    private final AnnouncementRepository announcementRepository;

    private final AnnouncementScheduleRepository scheduleRepository;

    private final AnnouncementAttachmentRepository attachmentRepository;

    private final SupplyRowRepository supplyRowRepository;

    private final SupplyTargetRepository supplyTargetRepository;

    private final MyHomeAnnouncementSupplyMatcher supplyMatcher;

    private final LhAnnouncementEnrichmentWriter enrichmentWriter;

    /** 주택형 변경과 LH 금액 보강을 함께 저장한다. 보강이 실패하면 공고 한 건을 모두 되돌린다. */
    @Transactional
    public MyHomeAnnouncementWriteResult write(
            ResolvedAnnouncement resolved,
            Announcement previousAnnouncement
    ) {
        MyHomeAnnouncementMappingData data = resolved.data();
        AnnouncementWriteResult announcementResult = writeAnnouncement(data, previousAnnouncement);
        if (announcementResult.releasedLhOwnership()) {
            deleteLhEnrichment(announcementResult.announcement());
        }
        SupplyRowsWriteResult supplyRowsResult = writeSupplyRows(
                announcementResult.announcement(),
                data.supplyRows(),
                resolved.preserveExistingLhResolvedRows(),
                resolved.historicalSourceKeys()
        );
        enrichChangedAnnouncement(resolved, announcementResult, supplyRowsResult);
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
        try {
            enrichmentWriter.writeAfterMapping(
                    announcement, resolved.request(), resolved.supplies(),
                    supplyRowsResult.changedHousingTypeRowIds(), resolved.lhHistoricalSourceKeys()
            );
        }
        catch (LhAnnouncementEnrichmentRejectedException exception) {
            throw new MyHomeAnnouncementMappingRejectedException(
                    MyHomeAnnouncementMappingFailureReason.INVALID_VALUE,
                    "LH 보강 실패: " + exception.reason() + " - " + exception.getMessage()
            );
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
        return new AnnouncementWriteResult(stored, 0, 0, releasesLhOwnership);
    }

    private void deleteLhEnrichment(Announcement announcement) {
        List<AnnouncementSchedule> schedules = scheduleRepository.findAllByAnnouncement(announcement).stream()
                .filter(schedule -> isLhSource(schedule.getSourceScheduleIdentifier()))
                .toList();
        scheduleRepository.deleteAll(schedules);
        List<AnnouncementAttachment> attachments = attachmentRepository.findAllByAnnouncement(announcement).stream()
                .filter(attachment -> isLhSource(attachment.getSourceAttachmentIdentifier()))
                .toList();
        attachmentRepository.deleteAll(attachments);
        List<Long> supplyRowIds = supplyRowRepository.findAllByAnnouncement(announcement).stream()
                .map(SupplyRow::getId)
                .toList();
        if (supplyRowIds.isEmpty()) {
            return;
        }
        List<SupplyTarget> targets = supplyTargetRepository.findAllBySupplyRowIdIn(supplyRowIds).stream()
                .filter(target -> isLhSource(target.getSourceSupplyTargetIdentifier()))
                .toList();
        supplyTargetRepository.deleteAll(targets);
    }

    private boolean isLhSource(String identifier) {
        return identifier != null && identifier.startsWith("LH:");
    }

    private SupplyRowsWriteResult writeSupplyRows(
            Announcement announcement,
            List<MyHomeSupplyRowMappingData> rows,
            boolean preserveExistingLhResolvedRows,
            Set<String> historicalSourceKeys
    ) {
        Map<String, SupplyRow> storedRows = supplyRowRepository.findAllByAnnouncement(announcement)
                .stream()
                .collect(Collectors.toMap(
                        SupplyRow::getSourceSupplyRowIdentifier,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        int created = 0;
        int updated = 0;
        int unchanged = 0;
        int displayOrder = 1;
        Set<Long> changedHousingTypeRowIds = new HashSet<>();
        List<MyHomeSupplyMatchingFailureData> failures = new ArrayList<>();
        Map<String, List<SupplyRow>> storedGroups = MyHomeAnnouncementSupplyRowGroups.byMyHomeSource(
                announcement.getSourceAnnouncementIdentifier(), storedRows.values()
        );
        Set<String> currentRowIdentifiers = rows.stream()
                .filter(row -> !historicalSourceKeys.contains(row.source().getSourceKey()))
                .map(MyHomeSupplyRowMappingData::sourceSupplyRowIdentifier)
                .collect(Collectors.toSet());
        for (MyHomeSupplyRowMappingData data : rows) {
            List<SupplyRow> storedGroup = storedGroups.get(data.sourceSupplyRowIdentifier());
            boolean historical = historicalSourceKeys.contains(data.source().getSourceKey());
            boolean preserveLhRows = preserveExistingLhResolvedRows
                    || announcement.getProvider() == AgencyCode.LH && historical;
            if (preserveLhRows && hasLhResolvedRows(announcement, storedGroup)) {
                PreservedGroupWriteResult result = updatePreservedGroup(
                        storedGroup, storedRows, data, historical, currentRowIdentifiers, displayOrder
                );
                updated += result.updated();
                unchanged += result.unchanged();
                displayOrder = result.nextDisplayOrder();
                continue;
            }
            SupplyRow stored = storedRows.remove(data.sourceSupplyRowIdentifier());
            MyHomeSupplyMatchResult match = supplyMatcher.match(data);
            if (match.failure() != null) {
                failures.add(match.failure());
            }
            if (stored == null) {
                SupplyRow createdRow = createSupplyRow(announcement, data, match, displayOrder++);
                applyLhResolution(createdRow, data, match);
                supplyRowRepository.save(createdRow);
                created++;
                continue;
            }
            if (shouldPreservePreviousLhResolution(stored, data, match)) {
                boolean changed = updatePreservingLhResolution(
                        stored, data, displayOrder++, data.totalSupplyHouseholdCount()
                );
                if (changed) {
                    updated++;
                    continue;
                }
                unchanged++;
                continue;
            }
            Long previousHousingTypeId = housingTypeId(stored);
            boolean changed = stored.updateFromMyHome(
                    match.complex(),
                    match.housingType(),
                    displayOrder++,
                    data.sourceComplexName(),
                    data.sourceHousingTypeName(),
                    data.pnu(),
                    data.supplyCategory(),
                    match.failureDetail(),
                    data.totalSupplyHouseholdCount()
            );
            changed |= applyLhResolution(stored, data, match);
            if (!Objects.equals(previousHousingTypeId, housingTypeId(stored))) {
                changedHousingTypeRowIds.add(stored.getId());
            }
            if (changed) {
                updated++;
                continue;
            }
            unchanged++;
        }
        List<SupplyRow> staleRows = List.copyOf(storedRows.values());
        deleteStaleRows(staleRows);
        return new SupplyRowsWriteResult(
                created, updated, unchanged, staleRows.size(), failures, changedHousingTypeRowIds
        );
    }

    private PreservedGroupWriteResult updatePreservedGroup(
            List<SupplyRow> storedGroup,
            Map<String, SupplyRow> storedRows,
            MyHomeSupplyRowMappingData data,
            boolean historical,
            Set<String> currentRowIdentifiers,
            int displayOrder
    ) {
        int updated = 0;
        int unchanged = 0;
        for (SupplyRow stored : storedGroup) {
            if (historical && currentRowIdentifiers.contains(stored.getSourceSupplyRowIdentifier())) {
                continue;
            }
            storedRows.remove(stored.getSourceSupplyRowIdentifier());
            Integer householdCount = stored.getTotalSupplyHouseholdCount();
            if (stored.getSourceSupplyRowIdentifier().equals(data.sourceSupplyRowIdentifier())) {
                householdCount = data.totalSupplyHouseholdCount();
            }
            boolean changed = updatePreservingLhResolution(stored, data, displayOrder++, householdCount);
            if (changed) {
                updated++;
                continue;
            }
            unchanged++;
        }
        return new PreservedGroupWriteResult(updated, unchanged, displayOrder);
    }

    private boolean updatePreservingLhResolution(
            SupplyRow stored,
            MyHomeSupplyRowMappingData data,
            int displayOrder,
            Integer householdCount
    ) {
        return stored.updateFromMyHome(
                stored.getHousingComplex(),
                stored.getHousingType(),
                displayOrder,
                data.sourceComplexName(),
                stored.getSourceHousingTypeName(),
                data.pnu(),
                data.supplyCategory(),
                stored.getMatchingFailureReason(),
                householdCount
        );
    }

    private Long housingTypeId(SupplyRow row) {
        if (row.getHousingType() == null) {
            return null;
        }
        return row.getHousingType().getId();
    }

    private boolean applyLhResolution(
            SupplyRow row,
            MyHomeSupplyRowMappingData data,
            MyHomeSupplyMatchResult match
    ) {
        if (data.resolvedLhSourceIdentifier() == null || match.failure() != null) {
            return false;
        }
        return row.resolveFromLhSupply(
                data.resolvedLhSourceIdentifier(),
                data.totalSupplyHouseholdCount(),
                data.lhTotalSupplyHouseholdCount()
        );
    }

    private boolean shouldPreservePreviousLhResolution(
            SupplyRow stored,
            MyHomeSupplyRowMappingData data,
            MyHomeSupplyMatchResult match
    ) {
        return match.failure() != null
                && data.resolvedLhSourceIdentifier() != null
                && stored.getLhSourceSupplyRowIdentifier() != null;
    }

    private boolean hasLhResolvedRows(Announcement announcement, List<SupplyRow> rows) {
        if (rows == null) {
            return false;
        }
        String generatedIdentifierPrefix = announcement.getSourceAnnouncementIdentifier() + ":LH:";
        return rows.stream().anyMatch(row -> row.getLhSourceSupplyRowIdentifier() != null
                || row.getSourceSupplyRowIdentifier().startsWith(generatedIdentifierPrefix));
    }

    private void deleteStaleRows(List<SupplyRow> staleRows) {
        if (staleRows.isEmpty()) {
            return;
        }
        supplyTargetRepository.deleteAllBySupplyRowIn(staleRows);
        supplyRowRepository.deleteAll(staleRows);
    }

    private SupplyRow createSupplyRow(
            Announcement announcement,
            MyHomeSupplyRowMappingData data,
            MyHomeSupplyMatchResult match,
            int displayOrder
    ) {
        return SupplyRow.create(
                announcement,
                match.complex(),
                match.housingType(),
                data.sourceSupplyRowIdentifier(),
                displayOrder,
                data.sourceComplexName(),
                data.sourceHousingTypeName(),
                data.pnu(),
                null,
                data.supplyCategory(),
                match.failureDetail(),
                data.totalSupplyHouseholdCount()
        );
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

    private record PreservedGroupWriteResult(int updated, int unchanged, int nextDisplayOrder) {
    }

    private record SupplyRowsWriteResult(
            int created,
            int updated,
            int unchanged,
            int deleted,
            List<MyHomeSupplyMatchingFailureData> failures,
            Set<Long> changedHousingTypeRowIds
    ) {
    }


    record MyHomeAnnouncementWriteResult(
            MyHomeAnnouncementMappingReport report,
            List<MyHomeSupplyMatchingFailureData> failures
    ) {
    }

    record MyHomeSupplyMatchingFailureData(
            MyHomeAnnouncementSource source,
            MyHomeAnnouncementMappingFailureReason reason,
            String detail
    ) {
    }
}

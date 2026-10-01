package com.toadzip.backend.ingest.enrichment.service;

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
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementCollectionCheckpoint;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementDetailSource;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementSupplySource;
import com.toadzip.backend.ingest.collection.dto.LhAnnouncementRequest;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementDetailSourceRepository;
import com.toadzip.backend.ingest.domain.MyHomeAnnouncementSupplyRowGroups;
import com.toadzip.backend.ingest.enrichment.domain.LhAnnouncementEnrichmentFailureReason;
import com.toadzip.backend.ingest.enrichment.dto.LhAnnouncementEnrichmentReport;
import com.toadzip.backend.ingest.enrichment.service.LhAnnouncementEnrichmentMapper.LhAnnouncementEnrichmentData;
import com.toadzip.backend.ingest.enrichment.service.LhAnnouncementEnrichmentMapper.LhAnnouncementEnrichmentRejectedException;
import com.toadzip.backend.ingest.enrichment.service.LhAnnouncementEnrichmentMapper.LhAttachmentData;
import com.toadzip.backend.ingest.enrichment.service.LhAnnouncementEnrichmentMapper.LhScheduleData;
import com.toadzip.backend.ingest.enrichment.service.LhAnnouncementEnrichmentMapper.LhSupplyData;
import com.toadzip.backend.ingest.enrichment.service.LhAnnouncementSupplyMatcher.LhSupplyMatchResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class LhAnnouncementEnrichmentWriter {

    private static final String ALL_TARGET = "전체";
    private static final String ALL_RANK = "전체";

    private final AnnouncementScheduleRepository scheduleRepository;
    private final AnnouncementRepository announcementRepository;
    private final AnnouncementAttachmentRepository attachmentRepository;
    private final SupplyRowRepository supplyRowRepository;
    private final SupplyTargetRepository supplyTargetRepository;
    private final LhAnnouncementSupplyMatcher supplyMatcher;
    private final LhAnnouncementDetailSourceRepository detailSourceRepository;
    private final LhAnnouncementEnrichmentMapper mapper;

    @Transactional
    public void writeAfterMapping(
            Announcement announcement,
            LhAnnouncementRequest request,
            List<LhAnnouncementSupplySource> supplies,
            Set<Long> changedHousingTypeRowIds,
            Set<String> sourceKeysExcludedFromLhEnrichment
    ) {
        if (announcement.getSupplyType() == RentalType.ETC) {
            throw new LhAnnouncementEnrichmentRejectedException(
                    LhAnnouncementEnrichmentFailureReason.UNSUPPORTED_SUPPLY_TYPE,
                    "지원하지 않는 공급유형의 LH 공고입니다."
            );
        }
        List<LhAnnouncementDetailSource> details = detailSourceRepository
                .findAllByPanIdAndRequestHashOrderBySourceOrderAsc(
                        request.panId(),
                        LhAnnouncementCollectionCheckpoint.requestHashOf(request.requestDescription())
                );
        if (details.isEmpty()) {
            throw new LhAnnouncementEnrichmentRejectedException(
                    LhAnnouncementEnrichmentFailureReason.LH_DETAIL_SOURCE_NOT_FOUND,
                    "연결된 LH 공고 상세 원본이 없습니다."
            );
        }
        LhAnnouncementEnrichmentWriteResult result = write(
                announcement, mapper.map(request.panId(), details, supplies),
                changedHousingTypeRowIds, sourceKeysExcludedFromLhEnrichment
        );
        if (!result.failures().isEmpty()) {
            LhSupplyMatchingFailureData failure = result.failures().getFirst();
            throw new LhAnnouncementEnrichmentRejectedException(failure.reason(), failure.detail());
        }
    }

    @Transactional
    public LhAnnouncementEnrichmentWriteResult write(
            Announcement announcement,
            LhAnnouncementEnrichmentData data,
            Set<Long> changedHousingTypeRowIds,
            Set<String> sourceKeysExcludedFromLhEnrichment
    ) {
        return write(announcement, data, changedHousingTypeRowIds, sourceKeysExcludedFromLhEnrichment, false);
    }

    @Transactional
    public LhAnnouncementEnrichmentWriteResult write(
            Announcement announcement,
            LhAnnouncementEnrichmentData data,
            Set<Long> changedHousingTypeRowIds,
            Set<String> sourceKeysExcludedFromLhEnrichment,
            boolean verifiedEmptySupply
    ) {
        Announcement managedAnnouncement = announcementRepository.findByIdForUpdate(announcement.getId())
                .orElseThrow(() -> new IllegalStateException("보강할 공고가 없습니다."));
        String previousPanId = managedAnnouncement.getLhPanId();
        if (managedAnnouncement.isLhPanIdReviewed() && !data.panId().equals(previousPanId)) {
            throw new LhAnnouncementEnrichmentRejectedException(
                    LhAnnouncementEnrichmentFailureReason.LH_COLLECTION_LINK_MISMATCH,
                    "확인된 공고의 LH 원천과 보강 대상이 다릅니다."
            );
        }
        Set<String> replacedPanIds = new HashSet<>();
        replacedPanIds.add(data.panId());
        if (previousPanId != null) {
            replacedPanIds.add(previousPanId);
        }
        int updatedAnnouncements = 0;
        if (managedAnnouncement.enrichFromLh(
                data.panId(), data.correctionReason(), data.receptionPlace()
        )) {
            updatedAnnouncements = 1;
        }
        SchedulesWriteResult schedules = writeSchedules(
                managedAnnouncement, data, replacedPanIds, previousPanId
        );
        AttachmentsWriteResult attachments = writeAttachments(
                managedAnnouncement, data, replacedPanIds, previousPanId
        );
        SupplyWriteResult supplies = writeSupplies(
                managedAnnouncement, data, changedHousingTypeRowIds,
                sourceKeysExcludedFromLhEnrichment, verifiedEmptySupply
        );
        return new LhAnnouncementEnrichmentWriteResult(
                new LhAnnouncementEnrichmentReport(
                        updatedAnnouncements, 1 - updatedAnnouncements,
                        schedules.created(), schedules.updated(), attachments.created(), attachments.updated(),
                        supplies.updatedRows(), supplies.createdTargets(), supplies.updatedTargets(),
                        supplies.failures().size()
                ),
                supplies.failures()
        );
    }

    private SchedulesWriteResult writeSchedules(
            Announcement announcement,
            LhAnnouncementEnrichmentData data,
            Set<String> replacedPanIds,
            String previousPanId
    ) {
        List<AnnouncementSchedule> storedSchedules = scheduleRepository.findAllByAnnouncement(announcement);
        Map<String, AnnouncementSchedule> stored = schedulesBySource(storedSchedules);
        Set<String> retained = new HashSet<>();
        int created = 0;
        int updated = 0;
        int order = 1;
        for (LhScheduleData source : data.schedules()) {
            retained.add(source.sourceIdentifier());
            AnnouncementSchedule schedule = stored.get(source.sourceIdentifier());
            if (schedule == null) {
                scheduleRepository.save(AnnouncementSchedule.createFromSource(
                        announcement, source.sourceIdentifier(), source.type(), source.name(),
                        source.startAt(), source.endAt(), order++, source.complexName()
                ));
                created++;
                continue;
            }
            if (schedule.updateFromSource(
                    source.type(), source.name(), source.startAt(), source.endAt(), order++, source.complexName())) {
                updated++;
            }
        }
        if (!data.schedules().isEmpty()) {
            scheduleRepository.deleteAll(staleSchedules(storedSchedules, retained, replacedPanIds));
            return new SchedulesWriteResult(created, updated);
        }
        if (previousPanId != null && !previousPanId.equals(data.panId())) {
            scheduleRepository.deleteAll(staleSchedules(storedSchedules, retained, Set.of(previousPanId)));
        }
        return new SchedulesWriteResult(created, updated);
    }

    private AttachmentsWriteResult writeAttachments(
            Announcement announcement,
            LhAnnouncementEnrichmentData data,
            Set<String> replacedPanIds,
            String previousPanId
    ) {
        List<AnnouncementAttachment> storedAttachments = attachmentRepository.findAllByAnnouncement(announcement);
        Map<String, AnnouncementAttachment> stored = attachmentsBySource(storedAttachments);
        Set<String> retained = new HashSet<>();
        int created = 0;
        int updated = 0;
        int order = 1;
        for (LhAttachmentData source : data.attachments()) {
            retained.add(source.sourceIdentifier());
            AnnouncementAttachment attachment = stored.get(source.sourceIdentifier());
            if (attachment == null) {
                attachmentRepository.save(AnnouncementAttachment.createFromSource(
                        announcement, source.sourceIdentifier(), source.name(), source.type(), source.url(), order++
                ));
                created++;
                continue;
            }
            if (attachment.updateFromSource(source.name(), source.type(), source.url(), order++)) {
                updated++;
            }
        }
        if (!data.attachments().isEmpty()) {
            attachmentRepository.deleteAll(staleAttachments(storedAttachments, retained, replacedPanIds));
            return new AttachmentsWriteResult(created, updated);
        }
        if (previousPanId != null && !previousPanId.equals(data.panId())) {
            attachmentRepository.deleteAll(staleAttachments(storedAttachments, retained, Set.of(previousPanId)));
        }
        return new AttachmentsWriteResult(created, updated);
    }

    private SupplyWriteResult writeSupplies(
            Announcement announcement,
            LhAnnouncementEnrichmentData data,
            Set<Long> changedHousingTypeRowIds,
            Set<String> sourceKeysExcludedFromLhEnrichment,
            boolean verifiedEmptySupply
    ) {
        List<SupplyRow> rows = enrichmentRows(announcement, sourceKeysExcludedFromLhEnrichment);
        if (data.supplies().isEmpty()) {
            if (!changedHousingTypeRowIds.isEmpty()) {
                throw missingAmountForChangedHousingType();
            }
            Map<Long, List<SupplyTarget>> targets = targetsByRow(rows);
            if (verifiedEmptySupply) {
                targets.values().forEach(stored -> deleteOtherLhTargets(stored, ""));
            }
            else {
                targets.values().stream().flatMap(List::stream)
                        .forEach(target -> target.markLhAmountPreserved("LH_SUPPLY_NOT_PROVIDED"));
            }
            return new SupplyWriteResult(0, 0, 0, List.of());
        }
        SupplyMatches matches = matchSupplies(rows, data.supplies());
        requireAmountsForChangedHousingTypes(changedHousingTypeRowIds, matches);
        return writeMatchedSupplies(rows, matches, data.panId());
    }

    private void requireAmountsForChangedHousingTypes(
            Set<Long> changedHousingTypeRowIds,
            SupplyMatches matches
    ) {
        if (changedHousingTypeRowIds.isEmpty()) {
            return;
        }
        Set<Long> rowsWithAmount = matches.supplies().stream()
                .filter(matched -> hasCompleteAmount(matched.source()))
                .map(matched -> matched.row().getId())
                .collect(Collectors.toSet());
        if (!rowsWithAmount.containsAll(changedHousingTypeRowIds)) {
            throw missingAmountForChangedHousingType();
        }
    }

    private List<SupplyRow> enrichmentRows(Announcement announcement, Set<String> sourceKeysExcludedFromLhEnrichment) {
        List<SupplyRow> rows = supplyRowRepository.findAllByAnnouncement(announcement);
        if (sourceKeysExcludedFromLhEnrichment.isEmpty()) {
            return rows;
        }
        Set<Long> rowIdsExcludedFromLhEnrichment = MyHomeAnnouncementSupplyRowGroups.byMyHomeSource(
                announcement.getSourceAnnouncementIdentifier(), rows
        ).entrySet().stream()
                .filter(group -> sourceKeysExcludedFromLhEnrichment.contains(group.getKey()))
                .flatMap(group -> group.getValue().stream())
                .map(SupplyRow::getId)
                .collect(Collectors.toSet());
        return rows.stream().filter(row -> !rowIdsExcludedFromLhEnrichment.contains(row.getId())).toList();
    }

    private SupplyMatches matchSupplies(List<SupplyRow> rows, List<LhSupplyData> supplies) {
        List<LhSupplyMatchingFailureData> failures = new ArrayList<>();
        List<MatchedSupply> matchedSupplies = new ArrayList<>();
        Set<Long> matchedRowIds = new HashSet<>();
        for (LhSupplyData source : supplies) {
            LhSupplyMatchResult match = supplyMatcher.match(rows, source);
            if (match.failure() != null) {
                failures.add(match.failure());
                continue;
            }
            SupplyRow row = match.row();
            if (!matchedRowIds.add(row.getId())) {
                throw new LhAnnouncementEnrichmentRejectedException(
                        LhAnnouncementEnrichmentFailureReason.AMBIGUOUS_HOUSING_TYPE,
                        "여러 LH 공급행이 하나의 제품 공급행에 연결됩니다."
                );
            }
            matchedSupplies.add(new MatchedSupply(source, row));
        }
        return new SupplyMatches(matchedSupplies, failures);
    }

    private SupplyWriteResult writeMatchedSupplies(
            List<SupplyRow> rows,
            SupplyMatches matches,
            String panId
    ) {
        Map<Long, List<SupplyTarget>> targetsByRow = targetsByRow(rows);
        Set<String> retainedTargetIdentifiers = new HashSet<>();
        int updatedRows = 0;
        int createdTargets = 0;
        int updatedTargets = 0;
        for (MatchedSupply matched : matches.supplies()) {
            LhSupplyData source = matched.source();
            SupplyRow row = matched.row();
            String previousSourceIdentifier = row.getLhSourceSupplyRowIdentifier();
            if (row.enrichFromLh(
                    source.sourceIdentifier(), source.expectedMoveInMonth(), source.supplyHouseholdCount()
            )) {
                updatedRows++;
            }
            List<SupplyTarget> storedTargets = targetsByRow.get(row.getId());
            SupplyTargetWriteResult target = writeOrPreserveAmount(row, source, previousSourceIdentifier, storedTargets);
            retainedTargetIdentifiers.add(target.retainedIdentifier());
            createdTargets += target.created();
            updatedTargets += target.updated();
        }
        if (matches.failures().isEmpty()) {
            deleteStaleTargets(targetsByRow, retainedTargetIdentifiers, Set.of(panId));
        }
        else {
            targetsByRow.values().stream().flatMap(List::stream)
                    .filter(target -> !retainedTargetIdentifiers.contains(target.getSourceSupplyTargetIdentifier()))
                    .forEach(target -> target.markLhAmountPreserved("LH_SUPPLY_MATCHING_FAILED"));
        }
        return new SupplyWriteResult(updatedRows, createdTargets, updatedTargets, matches.failures());
    }

    private Map<Long, List<SupplyTarget>> targetsByRow(List<SupplyRow> rows) {
        Map<Long, List<SupplyTarget>> targets = new HashMap<>();
        for (SupplyRow row : rows) {
            targets.put(row.getId(), new ArrayList<>());
        }
        if (rows.isEmpty()) {
            return targets;
        }
        List<Long> rowIds = rows.stream().map(SupplyRow::getId).toList();
        for (SupplyTarget target : supplyTargetRepository.findAllBySupplyRowIdIn(rowIds)) {
            targets.get(target.getSupplyRow().getId()).add(target);
        }
        return targets;
    }

    private LhAnnouncementEnrichmentRejectedException missingAmountForChangedHousingType() {
        return new LhAnnouncementEnrichmentRejectedException(
                LhAnnouncementEnrichmentFailureReason.INVALID_VALUE,
                "새 주택형의 임대금액이 없어 이전 주택형의 금액을 유지합니다."
        );
    }

    private void deleteOtherLhTargets(List<SupplyTarget> storedTargets, String retainedIdentifier) {
        List<SupplyTarget> previousTargets = storedTargets.stream()
                .filter(target -> isLhSource(target.getSourceSupplyTargetIdentifier()))
                .filter(target -> !retainedIdentifier.equals(target.getSourceSupplyTargetIdentifier()))
                .toList();
        supplyTargetRepository.deleteAll(previousTargets);
        storedTargets.removeAll(previousTargets);
    }

    private SupplyTargetWriteResult writeOrPreserveAmount(
            SupplyRow row,
            LhSupplyData source,
            String previousSourceIdentifier,
            List<SupplyTarget> storedTargets
    ) {
        String identifier = source.sourceIdentifier() + ":TARGET";
        if (!hasCompleteAmount(source)) {
            String retainedIdentifier = existingAmountTargetIdentifier(
                    storedTargets, identifier, previousSourceIdentifier
            );
            storedTargets.stream()
                    .filter(target -> retainedIdentifier.equals(target.getSourceSupplyTargetIdentifier()))
                    .findFirst()
                    .ifPresent(SupplyTarget::markLhAmountPreserved);
            return new SupplyTargetWriteResult(retainedIdentifier, 0, 0);
        }
        SupplyTarget stored = storedTargets.stream()
                .filter(target -> identifier.equals(target.getSourceSupplyTargetIdentifier()))
                .findFirst()
                .orElse(null);
        int createdCount = 0;
        int updatedCount = 0;
        if (stored == null) {
            SupplyTarget created = supplyTargetRepository.save(SupplyTarget.createFromSource(
                    row, identifier, ALL_TARGET, ALL_RANK, source.supplyHouseholdCount(),
                    source.rentalDeposit(), source.monthlyRent(), 1
            ));
            storedTargets.add(created);
            createdCount = 1;
        }
        else if (stored.updateFromSource(
                ALL_TARGET, ALL_RANK, source.supplyHouseholdCount(), source.rentalDeposit(), source.monthlyRent(), 1
        )) {
            updatedCount = 1;
        }
        deleteOtherLhTargets(storedTargets, identifier);
        return new SupplyTargetWriteResult(identifier, createdCount, updatedCount);
    }

    private boolean hasCompleteAmount(LhSupplyData source) {
        return source.rentalDeposit() != null && source.monthlyRent() != null;
    }

    private String existingAmountTargetIdentifier(
            List<SupplyTarget> storedTargets,
            String currentIdentifier,
            String previousSourceIdentifier
    ) {
        if (storedTargets.stream().anyMatch(target ->
                currentIdentifier.equals(target.getSourceSupplyTargetIdentifier()))) {
            return currentIdentifier;
        }
        if (previousSourceIdentifier != null) {
            String previousIdentifier = previousSourceIdentifier + ":TARGET";
            if (storedTargets.stream().anyMatch(target ->
                    previousIdentifier.equals(target.getSourceSupplyTargetIdentifier()))) {
                return previousIdentifier;
            }
        }
        return storedTargets.stream()
                .map(SupplyTarget::getSourceSupplyTargetIdentifier)
                .filter(this::isLhSource)
                .findFirst()
                .orElse(currentIdentifier);
    }

    private boolean isLhSource(String identifier) {
        return identifier != null && identifier.startsWith("LH:");
    }

    private Map<String, AnnouncementSchedule> schedulesBySource(List<AnnouncementSchedule> storedSchedules) {
        Map<String, AnnouncementSchedule> schedules = new HashMap<>();
        for (AnnouncementSchedule schedule : storedSchedules) {
            if (schedule.getSourceScheduleIdentifier() != null) {
                schedules.put(schedule.getSourceScheduleIdentifier(), schedule);
            }
        }
        return schedules;
    }

    private Map<String, AnnouncementAttachment> attachmentsBySource(List<AnnouncementAttachment> storedAttachments) {
        Map<String, AnnouncementAttachment> attachments = new HashMap<>();
        for (AnnouncementAttachment attachment : storedAttachments) {
            if (attachment.getSourceAttachmentIdentifier() != null) {
                attachments.put(attachment.getSourceAttachmentIdentifier(), attachment);
            }
        }
        return attachments;
    }

    private List<AnnouncementSchedule> staleSchedules(
            List<AnnouncementSchedule> storedSchedules, Set<String> retained, Set<String> replacedPanIds
    ) {
        return storedSchedules.stream()
                .filter(schedule -> lhSourceForAnyPan(schedule.getSourceScheduleIdentifier(), replacedPanIds))
                .filter(schedule -> !retained.contains(schedule.getSourceScheduleIdentifier()))
                .toList();
    }

    private List<AnnouncementAttachment> staleAttachments(
            List<AnnouncementAttachment> storedAttachments, Set<String> retained, Set<String> replacedPanIds
    ) {
        return storedAttachments.stream()
                .filter(attachment -> lhSourceForAnyPan(attachment.getSourceAttachmentIdentifier(), replacedPanIds))
                .filter(attachment -> !retained.contains(attachment.getSourceAttachmentIdentifier()))
                .toList();
    }

    private void deleteStaleTargets(
            Map<Long, List<SupplyTarget>> targetsByRow,
            Set<String> retained,
            Set<String> replacedPanIds
    ) {
        for (List<SupplyTarget> storedTargets : targetsByRow.values()) {
            List<SupplyTarget> stale = storedTargets.stream()
                    .filter(target -> lhSourceForAnyPan(target.getSourceSupplyTargetIdentifier(), replacedPanIds))
                    .filter(target -> !retained.contains(target.getSourceSupplyTargetIdentifier()))
                    .toList();
            supplyTargetRepository.deleteAll(stale);
        }
    }

    private boolean lhSourceForAnyPan(String identifier, Set<String> panIds) {
        return identifier != null && panIds.stream().anyMatch(panId -> identifier.startsWith("LH:" + panId + ":"));
    }

    private record SchedulesWriteResult(int created, int updated) {
    }

    private record AttachmentsWriteResult(int created, int updated) {
    }

    private record MatchedSupply(LhSupplyData source, SupplyRow row) {
    }

    private record SupplyMatches(List<MatchedSupply> supplies, List<LhSupplyMatchingFailureData> failures) {
    }

    private record SupplyWriteResult(
            int updatedRows,
            int createdTargets,
            int updatedTargets,
            List<LhSupplyMatchingFailureData> failures
    ) {
    }

    private record SupplyTargetWriteResult(
            String retainedIdentifier,
            int created,
            int updated
    ) {
    }


    record LhAnnouncementEnrichmentWriteResult(
            LhAnnouncementEnrichmentReport report,
            List<LhSupplyMatchingFailureData> failures
    ) {
    }

    record LhSupplyMatchingFailureData(
            LhSupplyData source,
            LhAnnouncementEnrichmentFailureReason reason,
            String detail
    ) {
    }
}

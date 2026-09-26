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
import com.toadzip.backend.ingest.enrichment.domain.LhAnnouncementEnrichmentFailureReason;
import com.toadzip.backend.ingest.enrichment.dto.LhAnnouncementEnrichmentReport;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class LhAnnouncementEnrichmentWriter {

    private static final String ALL_TARGET = "전체";
    private static final String ALL_RANK = "전체";

    private final AnnouncementScheduleRepository scheduleRepository;
    private final AnnouncementRepository announcementRepository;
    private final AnnouncementAttachmentRepository attachmentRepository;
    private final SupplyRowRepository supplyRowRepository;
    private final SupplyTargetRepository supplyTargetRepository;
    private final LhAnnouncementSupplyMatcher supplyMatcher;

    public LhAnnouncementEnrichmentWriter(
            AnnouncementScheduleRepository scheduleRepository,
            AnnouncementRepository announcementRepository,
            AnnouncementAttachmentRepository attachmentRepository,
            SupplyRowRepository supplyRowRepository,
            SupplyTargetRepository supplyTargetRepository,
            LhAnnouncementSupplyMatcher supplyMatcher
    ) {
        this.scheduleRepository = scheduleRepository;
        this.announcementRepository = announcementRepository;
        this.attachmentRepository = attachmentRepository;
        this.supplyRowRepository = supplyRowRepository;
        this.supplyTargetRepository = supplyTargetRepository;
        this.supplyMatcher = supplyMatcher;
    }

    @Transactional
    public LhAnnouncementEnrichmentWriteResult write(Announcement announcement, LhAnnouncementEnrichmentData data) {
        return write(announcement, data, Set.of());
    }

    @Transactional
    public LhAnnouncementEnrichmentWriteResult write(
            Announcement announcement,
            LhAnnouncementEnrichmentData data,
            Set<Long> changedHousingTypeRows
    ) {
        Announcement managedAnnouncement = managedAnnouncement(announcement);
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
        int updatedAnnouncements = managedAnnouncement.enrichFromLh(
                data.panId(), data.correctionReason(), data.receptionPlace()
        ) ? 1 : 0;
        SchedulesWriteResult schedules = writeSchedules(
                managedAnnouncement, data, replacedPanIds, previousPanId
        );
        AttachmentsWriteResult attachments = writeAttachments(
                managedAnnouncement, data, replacedPanIds, previousPanId
        );
        SupplyWriteResult supplies = writeSupplies(managedAnnouncement, data, changedHousingTypeRows);
        return new LhAnnouncementEnrichmentWriteResult(
                new LhAnnouncementEnrichmentReport(
                        updatedAnnouncements, updatedAnnouncements == 0 ? 1 : 0,
                        schedules.created(), schedules.updated(), attachments.created(), attachments.updated(),
                        supplies.updatedRows(), supplies.createdTargets(), supplies.updatedTargets(), supplies.failures().size()
                ),
                supplies.failures()
        );
    }

    private Announcement managedAnnouncement(Announcement announcement) {
        if (announcement.getId() == null) {
            return announcementRepository.save(announcement);
        }
        return announcementRepository.findByIdForUpdate(announcement.getId())
                .orElseThrow(() -> new IllegalStateException("보강할 공고가 없습니다."));
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
                        source.startAt(), source.endAt(), order++
                ));
                created++;
                continue;
            }
            if (schedule.updateFromSource(source.type(), source.name(), source.startAt(), source.endAt(), order++)) {
                updated++;
            }
        }
        if (!data.schedules().isEmpty()) {
            scheduleRepository.deleteAll(staleSchedules(storedSchedules, retained, replacedPanIds));
        }
        else if (previousPanId != null && !previousPanId.equals(data.panId())) {
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
        }
        else if (previousPanId != null && !previousPanId.equals(data.panId())) {
            attachmentRepository.deleteAll(staleAttachments(storedAttachments, retained, Set.of(previousPanId)));
        }
        return new AttachmentsWriteResult(created, updated);
    }

    private SupplyWriteResult writeSupplies(
            Announcement announcement,
            LhAnnouncementEnrichmentData data,
            Set<Long> changedHousingTypeRows
    ) {
        if (data.supplies().isEmpty()) {
            if (!changedHousingTypeRows.isEmpty()) {
                throw missingAmountForChangedHousingType();
            }
            return new SupplyWriteResult(0, 0, 0, List.of());
        }
        List<SupplyRow> rows = supplyRowRepository.findAllByAnnouncement(announcement);
        List<LhSupplyMatchingFailureData> failures = new ArrayList<>();
        List<MatchedSupply> matchedSupplies = new ArrayList<>();
        Set<Long> matchedRowIds = new HashSet<>();
        for (LhSupplyData source : data.supplies()) {
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
        Map<Long, List<SupplyTarget>> targetsByRow = targetsByRow(rows);
        Set<String> retainedTargetIdentifiers = new HashSet<>();
        Set<Long> pricedChangedHousingTypeRows = new HashSet<>();
        int updatedRows = 0;
        int createdTargets = 0;
        int updatedTargets = 0;
        for (MatchedSupply matched : matchedSupplies) {
            LhSupplyData source = matched.source();
            SupplyRow row = matched.row();
            if (changedHousingTypeRows.contains(row.getId())
                    && (source.rentalDeposit() == null || source.monthlyRent() == null)) {
                throw missingAmountForChangedHousingType();
            }
            if (changedHousingTypeRows.contains(row.getId())) {
                pricedChangedHousingTypeRows.add(row.getId());
            }
            String previousSourceIdentifier = row.getLhSourceSupplyRowIdentifier();
            if (row.enrichFromLh(source.sourceIdentifier(), source.expectedMoveInMonth(), source.supplyHouseholdCount())) {
                updatedRows++;
            }
            List<SupplyTarget> storedTargets = targetsByRow.get(row.getId());
            SupplyTargetWriteResult target = writeTarget(row, source, previousSourceIdentifier, storedTargets);
            if (target.replacesPrevious()) {
                deleteOtherLhTargets(storedTargets, target.sourceIdentifier());
            }
            retainedTargetIdentifiers.add(target.sourceIdentifier());
            createdTargets += target.created();
            updatedTargets += target.updated();
        }
        if (!pricedChangedHousingTypeRows.containsAll(changedHousingTypeRows)) {
            throw missingAmountForChangedHousingType();
        }
        if (failures.isEmpty()) {
            deleteStaleTargets(targetsByRow, retainedTargetIdentifiers, Set.of(data.panId()));
        }
        return new SupplyWriteResult(updatedRows, createdTargets, updatedTargets, failures);
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

    private SupplyTargetWriteResult writeTarget(
            SupplyRow row,
            LhSupplyData source,
            String previousSourceIdentifier,
            List<SupplyTarget> storedTargets
    ) {
        String identifier = source.sourceIdentifier() + ":TARGET";
        SupplyTarget stored = storedTargets.stream()
                .filter(target -> identifier.equals(target.getSourceSupplyTargetIdentifier()))
                .findFirst()
                .orElse(null);
        if (source.rentalDeposit() == null || source.monthlyRent() == null) {
            if (stored != null) {
                return new SupplyTargetWriteResult(identifier, 0, 0, false);
            }
            String previousTargetIdentifier = previousTargetIdentifier(storedTargets, previousSourceIdentifier);
            if (previousTargetIdentifier == null) {
                previousTargetIdentifier = existingLhTargetIdentifier(storedTargets);
            }
            return new SupplyTargetWriteResult(
                    previousTargetIdentifier == null ? identifier : previousTargetIdentifier,
                    0,
                    0,
                    false
            );
        }
        if (stored == null) {
            SupplyTarget created = supplyTargetRepository.save(SupplyTarget.createFromSource(
                    row, identifier, ALL_TARGET, ALL_RANK, source.supplyHouseholdCount(),
                    source.rentalDeposit(), source.monthlyRent(), 1
            ));
            storedTargets.add(created);
            return new SupplyTargetWriteResult(identifier, 1, 0, true);
        }
        boolean updated = stored.updateFromSource(
                ALL_TARGET, ALL_RANK, source.supplyHouseholdCount(), source.rentalDeposit(), source.monthlyRent(), 1
        );
        return new SupplyTargetWriteResult(identifier, 0, updated ? 1 : 0, true);
    }

    private String previousTargetIdentifier(
            List<SupplyTarget> storedTargets,
            String previousSourceIdentifier
    ) {
        if (previousSourceIdentifier == null) {
            return null;
        }
        String identifier = previousSourceIdentifier + ":TARGET";
        return storedTargets.stream()
                .map(SupplyTarget::getSourceSupplyTargetIdentifier)
                .filter(identifier::equals)
                .findFirst()
                .orElse(null);
    }

    private String existingLhTargetIdentifier(List<SupplyTarget> storedTargets) {
        return storedTargets.stream()
                .map(SupplyTarget::getSourceSupplyTargetIdentifier)
                .filter(this::isLhSource)
                .findFirst()
                .orElse(null);
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

    private record SupplyWriteResult(
            int updatedRows,
            int createdTargets,
            int updatedTargets,
            List<LhSupplyMatchingFailureData> failures
    ) {
    }

    private record SupplyTargetWriteResult(
            String sourceIdentifier,
            int created,
            int updated,
            boolean replacesPrevious
    ) {
    }

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

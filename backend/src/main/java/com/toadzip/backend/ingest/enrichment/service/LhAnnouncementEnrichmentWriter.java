package com.toadzip.backend.ingest.enrichment.service;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementAttachment;
import com.toadzip.backend.announcement.domain.AnnouncementSchedule;
import com.toadzip.backend.announcement.domain.ReceptionPlace;
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
        Announcement managedAnnouncement = announcementRepository.save(announcement);
        Set<String> replacedPanIds = new HashSet<>();
        replacedPanIds.add(data.panId());
        if (managedAnnouncement.getLhPanId() != null) {
            replacedPanIds.add(managedAnnouncement.getLhPanId());
        }
        int updatedAnnouncements = managedAnnouncement.enrichFromLh(
                data.panId(), valueOrCurrent(data.correctionReason(), managedAnnouncement.getCorrectionCancellationReason()),
                receptionOrCurrent(data.receptionPlace(), managedAnnouncement.getReceptionPlace())
        ) ? 1 : 0;
        SchedulesWriteResult schedules = writeSchedules(managedAnnouncement, data, replacedPanIds);
        AttachmentsWriteResult attachments = writeAttachments(managedAnnouncement, data, replacedPanIds);
        SupplyWriteResult supplies = writeSupplies(managedAnnouncement, data);
        return new LhAnnouncementEnrichmentWriteResult(
                new LhAnnouncementEnrichmentReport(
                        updatedAnnouncements, updatedAnnouncements == 0 ? 1 : 0,
                        schedules.created(), schedules.updated(), attachments.created(), attachments.updated(),
                        supplies.updatedRows(), supplies.createdTargets(), supplies.updatedTargets(), supplies.failures().size()
                ),
                supplies.failures()
        );
    }

    private SchedulesWriteResult writeSchedules(
            Announcement announcement, LhAnnouncementEnrichmentData data, Set<String> replacedPanIds
    ) {
        Map<String, AnnouncementSchedule> stored = schedulesBySource(announcement);
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
        scheduleRepository.deleteAll(staleSchedules(announcement, retained, replacedPanIds));
        return new SchedulesWriteResult(created, updated);
    }

    private AttachmentsWriteResult writeAttachments(
            Announcement announcement, LhAnnouncementEnrichmentData data, Set<String> replacedPanIds
    ) {
        Map<String, AnnouncementAttachment> stored = attachmentsBySource(announcement);
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
        attachmentRepository.deleteAll(staleAttachments(announcement, retained, replacedPanIds));
        return new AttachmentsWriteResult(created, updated);
    }

    private SupplyWriteResult writeSupplies(Announcement announcement, LhAnnouncementEnrichmentData data) {
        if (data.supplies().isEmpty()) {
            return new SupplyWriteResult(0, 0, 0, List.of());
        }
        List<SupplyRow> rows = supplyRowRepository.findAllByAnnouncement(announcement);
        List<LhSupplyMatchingFailureData> failures = new ArrayList<>();
        Set<String> retainedTargetIdentifiers = new HashSet<>();
        int updatedRows = 0;
        int createdTargets = 0;
        int updatedTargets = 0;
        for (LhSupplyData source : data.supplies()) {
            LhSupplyMatchResult match = supplyMatcher.match(rows, source);
            if (match.failure() != null) {
                failures.add(match.failure());
                continue;
            }
            SupplyRow row = match.row();
            String previousSourceIdentifier = row.getLhSourceSupplyRowIdentifier();
            if (row.enrichFromLh(source.sourceIdentifier(), source.expectedMoveInMonth(), source.totalHouseholdCount())) {
                updatedRows++;
            }
            SupplyTargetWriteResult target = writeTarget(row, source);
            deletePreviousTarget(row, previousSourceIdentifier, source.sourceIdentifier());
            retainedTargetIdentifiers.add(target.sourceIdentifier());
            createdTargets += target.created();
            updatedTargets += target.updated();
        }
        deleteStaleTargets(rows, retainedTargetIdentifiers, Set.of(data.panId()));
        return new SupplyWriteResult(updatedRows, createdTargets, updatedTargets, failures);
    }

    private void deletePreviousTarget(SupplyRow row, String previousIdentifier, String currentIdentifier) {
        if (previousIdentifier == null || previousIdentifier.equals(currentIdentifier)) {
            return;
        }
        List<SupplyTarget> previousTargets = supplyTargetRepository.findAllBySupplyRow(row).stream()
                .filter(target -> (previousIdentifier + ":TARGET").equals(target.getSourceSupplyTargetIdentifier()))
                .toList();
        supplyTargetRepository.deleteAll(previousTargets);
    }

    private SupplyTargetWriteResult writeTarget(SupplyRow row, LhSupplyData source) {
        String identifier = source.sourceIdentifier() + ":TARGET";
        SupplyTarget stored = supplyTargetRepository.findAllBySupplyRow(row).stream()
                .filter(target -> identifier.equals(target.getSourceSupplyTargetIdentifier()))
                .findFirst()
                .orElse(null);
        if (source.rentalDeposit() == null || source.monthlyRent() == null) {
            if (stored != null) {
                supplyTargetRepository.delete(stored);
            }
            return new SupplyTargetWriteResult(identifier, 0, 0);
        }
        if (stored == null) {
            supplyTargetRepository.save(SupplyTarget.createFromSource(
                    row, identifier, ALL_TARGET, ALL_RANK, source.supplyHouseholdCount(),
                    source.rentalDeposit(), source.monthlyRent(), 1
            ));
            return new SupplyTargetWriteResult(identifier, 1, 0);
        }
        boolean updated = stored.updateFromSource(
                ALL_TARGET, ALL_RANK, source.supplyHouseholdCount(), source.rentalDeposit(), source.monthlyRent(), 1
        );
        return new SupplyTargetWriteResult(identifier, 0, updated ? 1 : 0);
    }

    private Map<String, AnnouncementSchedule> schedulesBySource(Announcement announcement) {
        Map<String, AnnouncementSchedule> schedules = new HashMap<>();
        for (AnnouncementSchedule schedule : scheduleRepository.findAllByAnnouncement(announcement)) {
            if (schedule.getSourceScheduleIdentifier() != null) {
                schedules.put(schedule.getSourceScheduleIdentifier(), schedule);
            }
        }
        return schedules;
    }

    private Map<String, AnnouncementAttachment> attachmentsBySource(Announcement announcement) {
        Map<String, AnnouncementAttachment> attachments = new HashMap<>();
        for (AnnouncementAttachment attachment : attachmentRepository.findAllByAnnouncement(announcement)) {
            if (attachment.getSourceAttachmentIdentifier() != null) {
                attachments.put(attachment.getSourceAttachmentIdentifier(), attachment);
            }
        }
        return attachments;
    }

    private List<AnnouncementSchedule> staleSchedules(
            Announcement announcement, Set<String> retained, Set<String> replacedPanIds
    ) {
        return scheduleRepository.findAllByAnnouncement(announcement).stream()
                .filter(schedule -> lhSourceForAnyPan(schedule.getSourceScheduleIdentifier(), replacedPanIds))
                .filter(schedule -> !retained.contains(schedule.getSourceScheduleIdentifier()))
                .toList();
    }

    private List<AnnouncementAttachment> staleAttachments(
            Announcement announcement, Set<String> retained, Set<String> replacedPanIds
    ) {
        return attachmentRepository.findAllByAnnouncement(announcement).stream()
                .filter(attachment -> lhSourceForAnyPan(attachment.getSourceAttachmentIdentifier(), replacedPanIds))
                .filter(attachment -> !retained.contains(attachment.getSourceAttachmentIdentifier()))
                .toList();
    }

    private void deleteStaleTargets(List<SupplyRow> rows, Set<String> retained, Set<String> replacedPanIds) {
        for (SupplyRow row : rows) {
            List<SupplyTarget> stale = supplyTargetRepository.findAllBySupplyRow(row).stream()
                    .filter(target -> lhSourceForAnyPan(target.getSourceSupplyTargetIdentifier(), replacedPanIds))
                    .filter(target -> !retained.contains(target.getSourceSupplyTargetIdentifier()))
                    .toList();
            supplyTargetRepository.deleteAll(stale);
        }
    }

    private String valueOrCurrent(String value, String current) {
        if (value == null) {
            return current;
        }
        return value;
    }

    private ReceptionPlace receptionOrCurrent(ReceptionPlace value, ReceptionPlace current) {
        if (value == null) {
            return current;
        }
        return value;
    }

    private boolean lhSourceForAnyPan(String identifier, Set<String> panIds) {
        return identifier != null && panIds.stream().anyMatch(panId -> identifier.startsWith("LH:" + panId + ":"));
    }

    private record SchedulesWriteResult(int created, int updated) {
    }

    private record AttachmentsWriteResult(int created, int updated) {
    }

    private record SupplyWriteResult(
            int updatedRows,
            int createdTargets,
            int updatedTargets,
            List<LhSupplyMatchingFailureData> failures
    ) {
    }

    private record SupplyTargetWriteResult(String sourceIdentifier, int created, int updated) {
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

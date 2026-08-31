package com.toadzip.backend.ingest.service;

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
import com.toadzip.backend.ingest.domain.LhAnnouncementEnrichmentFailureReason;
import com.toadzip.backend.ingest.dto.LhAnnouncementEnrichmentReport;
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

    public LhAnnouncementEnrichmentWriter(
            AnnouncementScheduleRepository scheduleRepository,
            AnnouncementRepository announcementRepository,
            AnnouncementAttachmentRepository attachmentRepository,
            SupplyRowRepository supplyRowRepository,
            SupplyTargetRepository supplyTargetRepository
    ) {
        this.scheduleRepository = scheduleRepository;
        this.announcementRepository = announcementRepository;
        this.attachmentRepository = attachmentRepository;
        this.supplyRowRepository = supplyRowRepository;
        this.supplyTargetRepository = supplyTargetRepository;
    }

    @Transactional
    public LhAnnouncementEnrichmentWriteResult write(Announcement announcement, LhAnnouncementEnrichmentData data) {
        Announcement managedAnnouncement = announcementRepository.save(announcement);
        int updatedAnnouncements = managedAnnouncement.enrichFromLh(
                data.panId(), valueOrCurrent(data.correctionReason(), managedAnnouncement.getCorrectionCancellationReason()),
                receptionOrCurrent(data.receptionPlace(), managedAnnouncement.getReceptionPlace())
        ) ? 1 : 0;
        SchedulesWriteResult schedules = writeSchedules(managedAnnouncement, data);
        AttachmentsWriteResult attachments = writeAttachments(managedAnnouncement, data);
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

    private SchedulesWriteResult writeSchedules(Announcement announcement, LhAnnouncementEnrichmentData data) {
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
        scheduleRepository.deleteAll(staleSchedules(announcement, retained, data.panId()));
        return new SchedulesWriteResult(created, updated);
    }

    private AttachmentsWriteResult writeAttachments(Announcement announcement, LhAnnouncementEnrichmentData data) {
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
        attachmentRepository.deleteAll(staleAttachments(announcement, retained, data.panId()));
        return new AttachmentsWriteResult(created, updated);
    }

    private SupplyWriteResult writeSupplies(Announcement announcement, LhAnnouncementEnrichmentData data) {
        List<SupplyRow> rows = supplyRowRepository.findAllByAnnouncement(announcement);
        List<LhSupplyMatchingFailureData> failures = new ArrayList<>();
        Set<String> retainedTargetIdentifiers = new HashSet<>();
        int updatedRows = 0;
        int createdTargets = 0;
        int updatedTargets = 0;
        for (LhSupplyData source : data.supplies()) {
            SupplyMatchResult match = match(rows, source);
            if (match.failure() != null) {
                failures.add(match.failure());
                continue;
            }
            SupplyRow row = match.row();
            if (row.enrichFromLh(source.sourceIdentifier(), source.expectedMoveInMonth(), source.totalHouseholdCount())) {
                updatedRows++;
            }
            SupplyTargetWriteResult target = writeTarget(row, source);
            retainedTargetIdentifiers.add(target.sourceIdentifier());
            createdTargets += target.created();
            updatedTargets += target.updated();
        }
        deleteStaleTargets(rows, retainedTargetIdentifiers, data.panId());
        return new SupplyWriteResult(updatedRows, createdTargets, updatedTargets, failures);
    }

    private SupplyTargetWriteResult writeTarget(SupplyRow row, LhSupplyData source) {
        String identifier = source.sourceIdentifier() + ":TARGET";
        if (source.rentalDeposit() == null || source.monthlyRent() == null) {
            return new SupplyTargetWriteResult(identifier, 0, 0);
        }
        SupplyTarget stored = supplyTargetRepository.findAllBySupplyRow(row).stream()
                .filter(target -> identifier.equals(target.getSourceSupplyTargetIdentifier()))
                .findFirst()
                .orElse(null);
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

    private SupplyMatchResult match(List<SupplyRow> rows, LhSupplyData source) {
        for (SupplyRow row : rows) {
            if (source.sourceIdentifier().equals(row.getLhSourceSupplyRowIdentifier())
                    && matchesComplex(row, source)
                    && matchesHousingType(row, source)) {
                return SupplyMatchResult.matched(row);
            }
        }
        List<SupplyRow> complexMatches = rows.stream()
                .filter(row -> matchesComplex(row, source))
                .toList();
        if (complexMatches.isEmpty()) {
            return SupplyMatchResult.failure(source, LhAnnouncementEnrichmentFailureReason.COMPLEX_NOT_FOUND,
                    "LH 공급 원본과 일치하는 기존 공급 단지가 없습니다.");
        }
        List<SupplyRow> typeMatches = complexMatches.stream()
                .filter(row -> matchesHousingType(row, source))
                .toList();
        if (typeMatches.size() == 1) {
            return SupplyMatchResult.matched(typeMatches.getFirst());
        }
        if (typeMatches.size() > 1) {
            return SupplyMatchResult.failure(source, LhAnnouncementEnrichmentFailureReason.AMBIGUOUS_HOUSING_TYPE,
                    "LH 공급 원본에 일치하는 주택형 공급행이 여러 개입니다.");
        }
        if (complexMatches.size() == 1) {
            return SupplyMatchResult.failure(source, LhAnnouncementEnrichmentFailureReason.HOUSING_TYPE_NOT_FOUND,
                    "LH 공급 원본과 일치하는 기존 주택형 공급행이 없습니다.");
        }
        return SupplyMatchResult.failure(source, LhAnnouncementEnrichmentFailureReason.AMBIGUOUS_COMPLEX,
                "LH 공급 원본에 일치하는 기존 공급 단지가 여러 개입니다.");
    }

    private boolean matchesComplex(SupplyRow row, LhSupplyData source) {
        if (same(row.getSourceComplexName(), source.complexName())) {
            return true;
        }
        return row.getHousingComplex() != null
                && same(row.getHousingComplex().getName(), source.complexName());
    }

    private boolean matchesHousingType(SupplyRow row, LhSupplyData source) {
        if (same(row.getSourceHousingTypeName(), source.housingTypeName())) {
            return true;
        }
        return row.getHousingType() != null
                && same(row.getHousingType().getName(), source.housingTypeName());
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

    private List<AnnouncementSchedule> staleSchedules(Announcement announcement, Set<String> retained, String panId) {
        return scheduleRepository.findAllByAnnouncement(announcement).stream()
                .filter(schedule -> lhSourceForPan(schedule.getSourceScheduleIdentifier(), panId))
                .filter(schedule -> !retained.contains(schedule.getSourceScheduleIdentifier()))
                .toList();
    }

    private List<AnnouncementAttachment> staleAttachments(Announcement announcement, Set<String> retained, String panId) {
        return attachmentRepository.findAllByAnnouncement(announcement).stream()
                .filter(attachment -> lhSourceForPan(attachment.getSourceAttachmentIdentifier(), panId))
                .filter(attachment -> !retained.contains(attachment.getSourceAttachmentIdentifier()))
                .toList();
    }

    private void deleteStaleTargets(List<SupplyRow> rows, Set<String> retained, String panId) {
        for (SupplyRow row : rows) {
            List<SupplyTarget> stale = supplyTargetRepository.findAllBySupplyRow(row).stream()
                    .filter(target -> lhSourceForPan(target.getSourceSupplyTargetIdentifier(), panId))
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

    private boolean lhSourceForPan(String identifier, String panId) {
        return identifier != null && identifier.startsWith("LH:" + panId + ":");
    }

    private boolean same(String left, String right) {
        return normalized(left).equals(normalized(right));
    }

    private String normalized(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", "").replace("-", "").strip().toLowerCase();
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

    private record SupplyMatchResult(SupplyRow row, LhSupplyMatchingFailureData failure) {

        static SupplyMatchResult matched(SupplyRow row) {
            return new SupplyMatchResult(row, null);
        }

        static SupplyMatchResult failure(
                LhSupplyData source,
                LhAnnouncementEnrichmentFailureReason reason,
                String detail
        ) {
            return new SupplyMatchResult(null, new LhSupplyMatchingFailureData(source, reason, detail));
        }
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

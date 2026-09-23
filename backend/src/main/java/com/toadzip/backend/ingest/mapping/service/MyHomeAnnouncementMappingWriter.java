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
import com.toadzip.backend.ingest.mapping.domain.MyHomeAnnouncementMappingFailureReason;
import com.toadzip.backend.ingest.mapping.dto.MyHomeAnnouncementMappingReport;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class MyHomeAnnouncementMappingWriter {

    private final AnnouncementRepository announcementRepository;

    private final AnnouncementScheduleRepository scheduleRepository;

    private final AnnouncementAttachmentRepository attachmentRepository;

    private final SupplyRowRepository supplyRowRepository;

    private final SupplyTargetRepository supplyTargetRepository;

    private final MyHomeAnnouncementSupplyMatcher supplyMatcher;

    public MyHomeAnnouncementMappingWriter(
            AnnouncementRepository announcementRepository,
            AnnouncementScheduleRepository scheduleRepository,
            AnnouncementAttachmentRepository attachmentRepository,
            SupplyRowRepository supplyRowRepository,
            SupplyTargetRepository supplyTargetRepository,
            MyHomeAnnouncementSupplyMatcher supplyMatcher
    ) {
        this.announcementRepository = announcementRepository;
        this.scheduleRepository = scheduleRepository;
        this.attachmentRepository = attachmentRepository;
        this.supplyRowRepository = supplyRowRepository;
        this.supplyTargetRepository = supplyTargetRepository;
        this.supplyMatcher = supplyMatcher;
    }

    @Transactional
    public MyHomeAnnouncementWriteResult write(
            MyHomeAnnouncementMappingData data,
            Announcement previousAnnouncement
    ) {
        AnnouncementWriteResult announcementResult = writeAnnouncement(data, previousAnnouncement);
        if (announcementResult.releasedLhOwnership()) {
            deleteLhEnrichment(announcementResult.announcement());
        }
        SupplyRowsWriteResult supplyRowsResult = writeSupplyRows(
                announcementResult.announcement(),
                data.supplyRows(),
                data.preserveExistingLhResolvedRows()
        );
        return new MyHomeAnnouncementWriteResult(
                reportOf(announcementResult, supplyRowsResult),
                supplyRowsResult.failures()
        );
    }

    private AnnouncementWriteResult writeAnnouncement(
            MyHomeAnnouncementMappingData data,
            Announcement previousAnnouncement
    ) {
        Announcement stored = announcementRepository
                .findBySourceAnnouncementIdentifier(data.sourceAnnouncementIdentifier())
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
            return new AnnouncementWriteResult(created, true, false, false);
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
        return new AnnouncementWriteResult(stored, false, updated, releasesLhOwnership);
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
        for (SupplyRow row : supplyRowRepository.findAllByAnnouncement(announcement)) {
            List<SupplyTarget> targets = supplyTargetRepository.findAllBySupplyRow(row).stream()
                    .filter(target -> isLhSource(target.getSourceSupplyTargetIdentifier()))
                    .toList();
            supplyTargetRepository.deleteAll(targets);
        }
    }

    private boolean isLhSource(String identifier) {
        return identifier != null && identifier.startsWith("LH:");
    }

    private SupplyRowsWriteResult writeSupplyRows(
            Announcement announcement,
            List<MyHomeSupplyRowMappingData> rows,
            boolean preserveExistingLhResolvedRows
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
        List<MyHomeSupplyMatchingFailureData> failures = new ArrayList<>();
        Map<String, List<SupplyRow>> storedGroups = storedGroupsByMyHomeSource(announcement, storedRows.values());
        for (MyHomeSupplyRowMappingData data : rows) {
            List<SupplyRow> storedGroup = storedGroups.get(data.sourceSupplyRowIdentifier());
            if (preserveExistingLhResolvedRows && hasLhResolvedRows(announcement, storedGroup)) {
                for (SupplyRow stored : storedGroup) {
                    storedRows.remove(stored.getSourceSupplyRowIdentifier());
                    Integer householdCount = stored.getSourceSupplyRowIdentifier()
                            .equals(data.sourceSupplyRowIdentifier())
                            ? data.totalSupplyHouseholdCount()
                            : stored.getTotalSupplyHouseholdCount();
                    boolean changed = stored.updateFromMyHome(
                            stored.getHousingComplex(),
                            stored.getHousingType(),
                            displayOrder++,
                            data.sourceComplexName(),
                            stored.getSourceHousingTypeName(),
                            data.pnu(),
                            data.supplyCategory(),
                            stored.getMatchingFailureReason(),
                            householdCount
                    );
                    if (changed) {
                        updated++;
                        continue;
                    }
                    unchanged++;
                }
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
                boolean changed = stored.updateFromMyHome(
                        stored.getHousingComplex(),
                        stored.getHousingType(),
                        displayOrder++,
                        data.sourceComplexName(),
                        stored.getSourceHousingTypeName(),
                        data.pnu(),
                        data.supplyCategory(),
                        stored.getMatchingFailureReason(),
                        data.totalSupplyHouseholdCount()
                );
                if (changed) {
                    updated++;
                    continue;
                }
                unchanged++;
                continue;
            }
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
            if (changed) {
                updated++;
                continue;
            }
            unchanged++;
        }
        List<SupplyRow> staleRows = List.copyOf(storedRows.values());
        deleteStaleRows(staleRows);
        return new SupplyRowsWriteResult(created, updated, unchanged, staleRows.size(), failures);
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

    private Map<String, List<SupplyRow>> storedGroupsByMyHomeSource(
            Announcement announcement,
            Collection<SupplyRow> rows
    ) {
        String generatedIdentifierPrefix = announcement.getSourceAnnouncementIdentifier() + ":LH:";
        List<SupplyRow> orderedRows = rows.stream()
                .sorted(Comparator.comparingInt(SupplyRow::getDisplayOrder).thenComparing(SupplyRow::getId))
                .toList();
        Map<String, List<SupplyRow>> groups = new LinkedHashMap<>();
        String myHomeSourceIdentifier = null;
        for (SupplyRow row : orderedRows) {
            if (!row.getSourceSupplyRowIdentifier().startsWith(generatedIdentifierPrefix)) {
                myHomeSourceIdentifier = row.getSourceSupplyRowIdentifier();
            }
            if (myHomeSourceIdentifier != null) {
                groups.computeIfAbsent(myHomeSourceIdentifier, ignored -> new ArrayList<>()).add(row);
            }
        }
        return groups;
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
        int createdAnnouncement = announcement.created() ? 1 : 0;
        int updatedAnnouncement = announcement.updated() ? 1 : 0;
        int unchangedAnnouncement = announcement.unchanged() ? 1 : 0;
        return new MyHomeAnnouncementMappingReport(
                createdAnnouncement,
                updatedAnnouncement,
                unchangedAnnouncement,
                supplyRows.created(),
                supplyRows.updated(),
                supplyRows.unchanged(),
                supplyRows.deleted(),
                supplyRows.failures().size()
        );
    }

    private record AnnouncementWriteResult(
            Announcement announcement,
            boolean created,
            boolean updated,
            boolean releasedLhOwnership
    ) {

        boolean unchanged() {
            return !created && !updated;
        }
    }

    private record SupplyRowsWriteResult(
            int created,
            int updated,
            int unchanged,
            int deleted,
            List<MyHomeSupplyMatchingFailureData> failures
    ) {
    }

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

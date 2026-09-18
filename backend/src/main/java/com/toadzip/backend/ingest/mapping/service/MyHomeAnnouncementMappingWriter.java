package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.announcement.repository.SupplyTargetRepository;
import com.toadzip.backend.ingest.collection.domain.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.mapping.domain.MyHomeAnnouncementMappingFailureReason;
import com.toadzip.backend.ingest.mapping.dto.MyHomeAnnouncementMappingReport;
import java.util.ArrayList;
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

    private final SupplyRowRepository supplyRowRepository;

    private final SupplyTargetRepository supplyTargetRepository;

    private final MyHomeAnnouncementSupplyMatcher supplyMatcher;

    public MyHomeAnnouncementMappingWriter(
            AnnouncementRepository announcementRepository,
            SupplyRowRepository supplyRowRepository,
            SupplyTargetRepository supplyTargetRepository,
            MyHomeAnnouncementSupplyMatcher supplyMatcher
    ) {
        this.announcementRepository = announcementRepository;
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
            return new AnnouncementWriteResult(created, true, false);
        }
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
        return new AnnouncementWriteResult(stored, false, updated);
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
        List<MyHomeSupplyMatchingFailureData> failures = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            MyHomeSupplyRowMappingData data = rows.get(index);
            MyHomeSupplyMatchResult match = supplyMatcher.match(data);
            if (match.failure() != null) {
                failures.add(match.failure());
            }
            SupplyRow stored = storedRows.remove(data.sourceSupplyRowIdentifier());
            if (stored == null) {
                supplyRowRepository.save(createSupplyRow(announcement, data, match, index + 1));
                created++;
                continue;
            }
            boolean changed = stored.updateFromMyHome(
                    match.complex(),
                    match.housingType(),
                    index + 1,
                    data.sourceComplexName(),
                    data.sourceHousingTypeName(),
                    data.pnu(),
                    data.supplyCategory(),
                    match.failureDetail(),
                    data.totalSupplyHouseholdCount()
            );
            if (data.resolvedLhPanId() != null && !hasLhSourceForPan(stored, data.resolvedLhPanId())) {
                changed |= stored.enrichTotalSupplyHouseholdCountFromLh(data.totalSupplyHouseholdCount());
            }
            if (changed) {
                updated++;
                continue;
            }
            unchanged++;
        }
        List<SupplyRow> staleRows = staleRows(
                announcement,
                storedRows,
                preserveExistingLhResolvedRows
        );
        deleteStaleRows(staleRows);
        return new SupplyRowsWriteResult(created, updated, unchanged, staleRows.size(), failures);
    }

    private boolean hasLhSourceForPan(SupplyRow row, String panId) {
        String identifier = row.getLhSourceSupplyRowIdentifier();
        return identifier != null && identifier.startsWith("LH:" + panId + ":");
    }

    private List<SupplyRow> staleRows(
            Announcement announcement,
            Map<String, SupplyRow> storedRows,
            boolean preserveExistingLhResolvedRows
    ) {
        if (!preserveExistingLhResolvedRows) {
            return List.copyOf(storedRows.values());
        }
        String lhResolvedPrefix = announcement.getSourceAnnouncementIdentifier() + ":LH:";
        return storedRows.values().stream()
                .filter(row -> !row.getSourceSupplyRowIdentifier().startsWith(lhResolvedPrefix))
                .toList();
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

    private record AnnouncementWriteResult(Announcement announcement, boolean created, boolean updated) {

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

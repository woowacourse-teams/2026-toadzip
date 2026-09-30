package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.ingest.collection.domain.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.enrichment.domain.LhAnnouncementEnrichmentFailure;
import com.toadzip.backend.ingest.enrichment.service.LhAnnouncementEnrichmentService;
import com.toadzip.backend.ingest.mapping.domain.MyHomeAnnouncementMappingFailureReason;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class MyHomeLhAnnouncementAtomicWriter {

    private final MyHomeAnnouncementMappingWriter mappingWriter;
    private final LhAnnouncementEnrichmentService enrichmentService;
    private final SupplyRowRepository supplyRowRepository;

    public MyHomeLhAnnouncementAtomicWriter(
            MyHomeAnnouncementMappingWriter mappingWriter,
            LhAnnouncementEnrichmentService enrichmentService,
            SupplyRowRepository supplyRowRepository
    ) {
        this.mappingWriter = mappingWriter;
        this.enrichmentService = enrichmentService;
        this.supplyRowRepository = supplyRowRepository;
    }

    @Transactional
    public MyHomeAnnouncementWriteResult write(
            MyHomeAnnouncementMappingData data,
            Announcement previousAnnouncement,
            List<MyHomeAnnouncementSource> sources,
            Announcement announcement
    ) {
        Map<Long, Long> previousHousingTypes = new HashMap<>();
        for (SupplyRow row : supplyRowRepository.findAllByAnnouncement(announcement)) {
            previousHousingTypes.put(row.getId(), housingTypeId(row));
        }
        MyHomeAnnouncementWriteResult result = mappingWriter.write(data, previousAnnouncement);
        if (result.report().updatedAnnouncementCount() == 0
                && result.report().createdSupplyRowCount() == 0
                && result.report().updatedSupplyRowCount() == 0
                && result.report().deletedSupplyRowCount() == 0) {
            return result;
        }
        Set<Long> changedHousingTypeRows = supplyRowRepository.findAllByAnnouncement(announcement)
                .stream()
                .filter(row -> previousHousingTypes.containsKey(row.getId()))
                .filter(row -> !Objects.equals(previousHousingTypes.get(row.getId()), housingTypeId(row)))
                .map(SupplyRow::getId)
                .collect(Collectors.toSet());
        List<LhAnnouncementEnrichmentFailure> failures = enrichmentService.enrichForAtomicMapping(
                sources, changedHousingTypeRows
        );
        if (!failures.isEmpty()) {
            throw new MyHomeAnnouncementMappingRejectedException(
                    MyHomeAnnouncementMappingFailureReason.INVALID_VALUE,
                    "LH 보강 실패: " + failures.getFirst().getReason() + " - " + failures.getFirst().getDetail()
            );
        }
        return result;
    }

    private Long housingTypeId(SupplyRow row) {
        return row.getHousingType() == null ? null : row.getHousingType().getId();
    }
}

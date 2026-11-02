package com.toadzip.backend.ingest.lhrefinement.service;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementComplex;
import com.toadzip.backend.announcement.domain.LhSupplyMatchStatus;
import com.toadzip.backend.announcement.domain.SupplyCategory;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.domain.SupplyTarget;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.announcement.repository.SupplyTargetRepository;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import com.toadzip.backend.ingest.collection.lh.supply.domain.LhAnnouncementSupplySourceView;
import com.toadzip.backend.ingest.domain.LhSourceIdentifier;
import com.toadzip.backend.ingest.domain.SourceArea;
import com.toadzip.backend.ingest.enrichment.service.LhAnnouncementEnrichmentMapper.LhSupplyData;
import com.toadzip.backend.ingest.lhrefinement.dto.LhAnnouncementRefinementReport.SupplyCounts;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * LH 공급 한 행을 공급행 하나로 쓴다. 공급행 키는 `LHROW:{공고 단지 ID}:{정규화한 주택형명}#{같은 이름 안 순번}`이고 LH 대표 면적은 키에
 * 넣지 않는다. 대표 주택형은 연결 단지에서 LH 대표 전용면적과 면적이 같은 주택형이 하나일 때만 정한다. 공고문 소유 공고는 공급행을 만들거나
 * 지우지 않고 비어 있는 공고 단지 참조만 채운다.
 */
@Component
@RequiredArgsConstructor
class LhSupplyRowWriter {

    private static final String ROW_PREFIX = "LHROW:";
    private static final String ALL = "전체";

    private final SupplyRowRepository supplyRows;
    private final SupplyTargetRepository supplyTargets;
    private final HousingTypeRepository housingTypes;

    /** LH 원천 소유 공고의 공급행을 이번 LH 공급으로 맞추고 이 공고의 다른 공급행을 지운다. */
    void writeLhOwned(Announcement announcement, List<SupplyLine> lines, Map<String, AnnouncementComplex> complexes,
            SupplyCategory category, Tally tally) {
        Map<String, SupplyRow> stored = supplyRows.findAllByAnnouncement(announcement).stream()
                .collect(Collectors.toMap(SupplyRow::getSourceSupplyRowIdentifier, row -> row));
        Map<Long, List<HousingType>> typesByComplex = new HashMap<>();
        Map<String, Integer> occurrences = new HashMap<>();
        int order = 0;
        for (SupplyLine line : lines) {
            AnnouncementComplex complex = complexes.get(line.source().getComplexLabel());
            String base = complex.getId() + ":" + normalizedTypeName(line.source().getTypeName());
            int occurrence = occurrences.merge(base, 1, Integer::sum);
            String key = ROW_PREFIX + base + "#" + occurrence;
            BigDecimal area = area(line.source().getExclusiveArea());
            HousingType type = representative(complex.linkedComplex(), area, typesByComplex);
            LhSupplyMatchStatus status = status(complex, type, line.data());
            SupplyRow incoming = SupplyRow.createFromLh(announcement, complex, type, key, line.data().sourceIdentifier(),
                    order++, line.source().getTypeName(), area, line.data().expectedMoveInMonth(), category,
                    line.data().supplyHouseholdCount(), status);
            SupplyRow row = stored.remove(key);
            if (row == null) {
                row = supplyRows.save(incoming);
                tally.createdRows++;
            }
            else if (row.updateFromLh(incoming)) {
                tally.updatedRows++;
            }
            writeTarget(row, key, line.data(), tally);
            tally.count(status);
        }
        for (SupplyRow stale : stored.values()) {
            supplyTargets.deleteAll(supplyTargets.findAllBySupplyRow(stale));
            supplyRows.delete(stale);
            tally.deletedRows++;
        }
    }

    /**
     * 공고문 소유 공급행은 값·단지·주택형 참조를 그대로 두고 공고 단지 참조만 채운다. 공고 단지가 하나뿐이거나, 그 행의 단지에 연결된 공고 단지가
     * 하나일 때만 정한다. 그 밖에는 비워 둔다.
     */
    void fillDocumentOwned(Announcement announcement, Map<String, AnnouncementComplex> complexes, Tally tally) {
        List<AnnouncementComplex> all = new ArrayList<>(complexes.values());
        for (SupplyRow row : supplyRows.findAllByAnnouncement(announcement)) {
            if (row.getAnnouncementComplex() != null) {
                continue;
            }
            Optional<AnnouncementComplex> complex = soleComplex(all, row.getHousingComplex());
            if (complex.isPresent() && row.fillAnnouncementComplex(complex.get())) {
                tally.filledComplexes++;
            }
        }
    }

    private Optional<AnnouncementComplex> soleComplex(List<AnnouncementComplex> complexes, HousingComplex rowComplex) {
        if (complexes.size() == 1) {
            return Optional.of(complexes.getFirst());
        }
        if (rowComplex == null) {
            return Optional.empty();
        }
        List<AnnouncementComplex> linked = complexes.stream()
                .filter(complex -> complex.linkedComplex() != null
                        && complex.linkedComplex().getId().equals(rowComplex.getId()))
                .toList();
        return linked.size() == 1 ? Optional.of(linked.getFirst()) : Optional.empty();
    }

    private void writeTarget(SupplyRow row, String key, LhSupplyData data, Tally tally) {
        String identifier = LhSourceIdentifier.target(key);
        List<SupplyTarget> targets = supplyTargets.findAllBySupplyRow(row);
        Optional<SupplyTarget> stored = targets.stream()
                .filter(target -> identifier.equals(target.getSourceSupplyTargetIdentifier())).findFirst();
        boolean priced = data.rentalDeposit() != null && data.monthlyRent() != null;
        if (!priced) {
            stored.ifPresent(target -> {
                supplyTargets.delete(target);
                tally.deletedTargets++;
            });
            return;
        }
        if (stored.isEmpty()) {
            supplyTargets.save(SupplyTarget.createFromSource(row, identifier, ALL, ALL, data.supplyHouseholdCount(),
                    data.rentalDeposit(), data.monthlyRent(), 1));
            tally.createdTargets++;
        }
        else if (stored.get().updateFromSource(ALL, ALL, data.supplyHouseholdCount(), data.rentalDeposit(),
                data.monthlyRent(), 1)) {
            tally.updatedTargets++;
        }
    }

    private HousingType representative(HousingComplex complex, BigDecimal area,
            Map<Long, List<HousingType>> typesByComplex) {
        if (complex == null || area == null) {
            return null;
        }
        List<HousingType> matches = typesByComplex.computeIfAbsent(complex.getId(),
                        ignored -> housingTypes.findAllByHousingComplex(complex)).stream()
                .filter(type -> type.getExclusiveArea() != null
                        && SourceArea.normalized(type.getExclusiveArea()).compareTo(area) == 0)
                .toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private LhSupplyMatchStatus status(AnnouncementComplex complex, HousingType type, LhSupplyData data) {
        if (complex.linkedComplex() == null) {
            return LhSupplyMatchStatus.COMPLEX_UNLINKED;
        }
        if (type == null) {
            return LhSupplyMatchStatus.AREA_UNMATCHED;
        }
        if (data.rentalDeposit() == null || data.monthlyRent() == null) {
            return LhSupplyMatchStatus.VALUES_MISSING;
        }
        return LhSupplyMatchStatus.MATCHED;
    }

    private static BigDecimal area(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return SourceArea.normalized(new BigDecimal(value.strip()));
        }
        catch (NumberFormatException unreadable) {
            return null;
        }
    }

    /** 공급행 키에 쓰는 주택형명. 앞뒤·연속 공백만 맞추고 표기는 바꾸지 않는다. */
    private static String normalizedTypeName(String value) {
        return value.strip().replaceAll("\\s+", " ");
    }

    record SupplyLine(LhAnnouncementSupplySourceView source, LhSupplyData data) {
    }

    static final class Tally {

        void add(Tally other) {
            createdRows += other.createdRows;
            updatedRows += other.updatedRows;
            deletedRows += other.deletedRows;
            createdTargets += other.createdTargets;
            updatedTargets += other.updatedTargets;
            deletedTargets += other.deletedTargets;
            filledComplexes += other.filledComplexes;
            other.statuses.forEach((status, count) -> statuses.merge(status, count, Integer::sum));
        }

        SupplyCounts toCounts() {
            Map<String, Integer> statusCounts = new TreeMap<>();
            statuses.forEach((status, count) -> statusCounts.put(status.name(), count));
            return new SupplyCounts(createdRows, updatedRows, deletedRows, createdTargets, updatedTargets,
                    deletedTargets, filledComplexes, statusCounts);
        }

        private int createdRows;
        private int updatedRows;
        private int deletedRows;
        private int createdTargets;
        private int updatedTargets;
        private int deletedTargets;
        private int filledComplexes;
        private final Map<LhSupplyMatchStatus, Integer> statuses = new EnumMap<>(LhSupplyMatchStatus.class);

        void count(LhSupplyMatchStatus status) {
            statuses.merge(status, 1, Integer::sum);
        }
    }
}

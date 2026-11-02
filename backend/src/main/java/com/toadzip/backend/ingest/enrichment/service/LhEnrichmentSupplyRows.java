package com.toadzip.backend.ingest.enrichment.service;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.ingest.domain.MyHomeAnnouncementSupplyRowGroups;
import com.toadzip.backend.ingest.domain.SupplyNameNormalizer;
import com.toadzip.backend.ingest.enrichment.service.LhAnnouncementEnrichmentMapper.LhSupplyData;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * LH 보강이 보는 공고의 공급행이다. LH 보강에서 뺀 마이홈 원천의 공급행은 빼고, 복수 공급행으로 보정한 묶음 행은
 * 매칭 대상에서 빼고, 묶음 행에 붙는 LH 공급은 건너뛴다. 보강과 관리자 매칭 진단이 같은 행과 같은 규칙을 쓴다.
 */
@Component
@RequiredArgsConstructor
class LhEnrichmentSupplyRows {

    private final SupplyRowRepository supplyRowRepository;
    private final LhAnnouncementSupplyMatcher supplyMatcher;

    Rows read(Announcement announcement, Set<String> sourceKeysExcludedFromLhEnrichment) {
        List<SupplyRow> rows = supplyRowRepository.findAllByAnnouncement(announcement);
        if (sourceKeysExcludedFromLhEnrichment.isEmpty()) {
            return new Rows(rows);
        }
        Set<Long> rowIdsExcludedFromLhEnrichment = MyHomeAnnouncementSupplyRowGroups.byMyHomeSource(
                announcement.getSourceAnnouncementIdentifier(), rows
        ).entrySet().stream()
                .filter(group -> sourceKeysExcludedFromLhEnrichment.contains(group.getKey()))
                .flatMap(group -> group.getValue().stream())
                .map(SupplyRow::getId)
                .collect(Collectors.toSet());
        return new Rows(rows.stream().filter(row -> !rowIdsExcludedFromLhEnrichment.contains(row.getId())).toList());
    }

    /**
     * 복수 공급행으로 보정한 묶음 행에 붙는 LH 공급이라 보강이 건너뛴다. 매칭된 행이 묶음 행이거나, 매칭된 행이 없어도
     * 같은 단지의 행이 모두 묶음 행이면 건너뛴다.
     */
    boolean skips(Rows rows, LhSupplyData source) {
        if (!rows.hasGroupedRows()) {
            return false;
        }
        var matched = supplyMatcher.match(rows.all(), source);
        if (matched.row() != null) {
            return matched.row().getSupplyGroup() != null;
        }
        var complexRows = rows.all().stream().filter(row ->
                SupplyNameNormalizer.sameComplex(row.getSourceComplexName(), source.complexName())
                        || row.getHousingComplex() != null && SupplyNameNormalizer.sameComplex(
                                row.getHousingComplex().getName(), source.complexName())).toList();
        return !complexRows.isEmpty() && complexRows.stream().allMatch(row -> row.getSupplyGroup() != null);
    }

    /** {@code all}은 묶음 행을 포함한 보강 대상 행, {@link #matchable()}은 LH 공급을 매칭할 행이다. */
    record Rows(List<SupplyRow> all) {

        List<SupplyRow> matchable() {
            return all.stream().filter(row -> row.getSupplyGroup() == null).toList();
        }

        boolean hasGroupedRows() {
            return all.stream().anyMatch(row -> row.getSupplyGroup() != null);
        }
    }
}

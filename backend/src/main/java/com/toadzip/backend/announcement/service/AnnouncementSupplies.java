package com.toadzip.backend.announcement.service;

import com.toadzip.backend.announcement.domain.AgencySupplyLine;
import com.toadzip.backend.announcement.domain.AgencySupplySheet;
import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.LeaseTerms;
import com.toadzip.backend.announcement.domain.SupplyQuota;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.domain.SupplyRowTarget;
import com.toadzip.backend.announcement.domain.SupplySheet;
import com.toadzip.backend.announcement.repository.AgencySupplyLineRepository;
import com.toadzip.backend.announcement.repository.LeaseTermsRepository;
import com.toadzip.backend.announcement.repository.SupplyQuotaRepository;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.announcement.repository.SupplyRowTargetRepository;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 공고 공급을 읽는 입구 하나: 정본을 확정한 공고는 공고 공급표(정본), 확정하기 전 공고는 기관 준비 줄을 공급표로 바꾼 것
 * ({@link AgencySupplySheet})이다. 공개 상세·목록, 단지 상세 공급조건, 품질 패널, 작업실 '준비 데이터로 정본 시작'이 함께 쓴다.
 * 호출하는 쪽의 트랜잭션 안에서 쓴다.
 */
@Component
@RequiredArgsConstructor
public class AnnouncementSupplies {

    private final SupplyRowRepository supplyRows;
    private final SupplyRowTargetRepository cells;
    private final SupplyQuotaRepository quotas;
    private final LeaseTermsRepository leases;
    private final AgencySupplyLineRepository lines;
    private final HousingTypeRepository housingTypes;

    public AnnouncementSupply read(Announcement announcement) {
        return read(List.of(announcement)).get(announcement.getId());
    }

    /** 공고 ID별 공급. 모든 공고가 결과에 있다. */
    public Map<Long, AnnouncementSupply> read(Collection<Announcement> announcements) {
        Map<Long, AnnouncementSupply> result = new LinkedHashMap<>();
        List<Long> confirmed = announcements.stream().filter(Announcement::isSupplyConfirmed).map(Announcement::getId)
                .toList();
        List<Long> staged = announcements.stream().filter(announcement -> !announcement.isSupplyConfirmed())
                .map(Announcement::getId).toList();
        Map<Long, SupplySheet> canonical = canonical(confirmed);
        Map<Long, SupplySheet> staging = staging(staged);
        for (Announcement announcement : announcements) {
            long id = announcement.getId();
            result.put(id, announcement.isSupplyConfirmed()
                    ? new AnnouncementSupply(true, canonical.getOrDefault(id, SupplySheet.empty()))
                    : new AnnouncementSupply(false, staging.getOrDefault(id, SupplySheet.empty())));
        }
        return result;
    }

    /** 확정하기 전 공고의 준비 줄을 공급표로 바꾼 것. 공고에 준비 줄이 없으면 빈 공급표다. */
    public AgencySupplySheet.Converted staging(Announcement announcement) {
        List<AgencySupplyLine> found = lines.findAllByAnnouncementIdIn(List.of(announcement.getId()));
        return AgencySupplySheet.convert(found, typesOf(found));
    }

    private Map<Long, SupplySheet> canonical(List<Long> announcementIds) {
        if (announcementIds.isEmpty()) {
            return Map.of();
        }
        List<SupplyRow> rows = supplyRows.findAllByAnnouncementIdIn(announcementIds);
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> announcementOfRow = rows.stream()
                .collect(Collectors.toMap(SupplyRow::getId, row -> row.getAnnouncement().getId()));
        Map<Long, List<SupplyRow>> rowsBy = rows.stream()
                .collect(Collectors.groupingBy(row -> row.getAnnouncement().getId()));
        Map<Long, List<SupplyRowTarget>> cellsBy = cells.findAllBySupplyRowIdIn(announcementOfRow.keySet()).stream()
                .collect(Collectors.groupingBy(cell -> announcementOfRow.get(cell.getSupplyRow().getId())));
        Map<Long, List<SupplyQuota>> quotasBy = quotas.findAllByAnnouncementIdIn(announcementIds).stream()
                .collect(Collectors.groupingBy(quota -> quota.getAnnouncement().getId()));
        Map<Long, List<LeaseTerms>> leasesBy = leases.findAllByAnnouncementIdIn(announcementIds).stream()
                .collect(Collectors.groupingBy(lease -> lease.getAnnouncement().getId()));
        Map<Long, SupplySheet> sheets = new HashMap<>();
        rowsBy.forEach((id, announcementRows) -> sheets.put(id, SupplySheet.of(announcementRows,
                cellsBy.getOrDefault(id, List.of()), quotasBy.getOrDefault(id, List.of()),
                leasesBy.getOrDefault(id, List.of()))));
        return sheets;
    }

    private Map<Long, SupplySheet> staging(List<Long> announcementIds) {
        if (announcementIds.isEmpty()) {
            return Map.of();
        }
        List<AgencySupplyLine> found = lines.findAllByAnnouncementIdIn(announcementIds);
        Function<HousingComplex, List<HousingType>> types = typesOf(found);
        Map<Long, SupplySheet> sheets = new HashMap<>();
        found.stream().collect(Collectors.groupingBy(line -> line.getAnnouncement().getId()))
                .forEach((id, announcementLines) -> sheets.put(id,
                        AgencySupplySheet.convert(announcementLines, types).sheet()));
        return sheets;
    }

    /** 준비 줄의 공고 단지가 연결한 단지들의 주택타입을 한 번에 읽는다. */
    private Function<HousingComplex, List<HousingType>> typesOf(List<AgencySupplyLine> found) {
        List<Long> complexIds = found.stream().map(AgencySupplyLine::getAnnouncementComplex).filter(Objects::nonNull)
                .map(complex -> complex.linkedComplex()).filter(Objects::nonNull).map(HousingComplex::getId)
                .distinct().toList();
        Map<Long, List<HousingType>> byComplex = complexIds.isEmpty() ? Map.of()
                : housingTypes.findAllByHousingComplexIdIn(complexIds).stream()
                        .collect(Collectors.groupingBy(type -> type.getHousingComplex().getId()));
        return complex -> byComplex.getOrDefault(complex.getId(), List.of());
    }

    /** 공고 공급: 정본을 확정했는지와 보일 공급표. */
    public record AnnouncementSupply(boolean confirmed, SupplySheet sheet) {
    }
}

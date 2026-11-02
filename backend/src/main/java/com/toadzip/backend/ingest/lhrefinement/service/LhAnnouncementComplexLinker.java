package com.toadzip.backend.ingest.lhrefinement.service;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementComplex;
import com.toadzip.backend.announcement.domain.ComplexLinkMethod;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.repository.AnnouncementComplexRepository;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.ingest.collection.lh.supply.domain.LhAnnouncementSupplySourceView;
import com.toadzip.backend.ingest.domain.LhSourceIdentifier;
import com.toadzip.backend.ingest.lhrefinement.dto.LhAnnouncementRefinementReport.ComplexCounts;
import java.time.Clock;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 공고 상세의 단지를 공고 단지로 맞추고, 새로 만든 공고 단지만 제품 단지에 연결한다. 이미 붙은 연결은 다시 계산하지 않는다.
 * 연결 순서: 기존 공급행이 이 단지에서 한 단지만 가리키면 그 단지(MIGRATED) → 정정공고는 원공고의 같은 이름 공고 단지 연결 →
 * 주소 규칙 후보가 하나면 그 단지(AUTO) → 미연결.
 */
@Component
@RequiredArgsConstructor
class LhAnnouncementComplexLinker {

    static final String ACTOR = "LH 공고 정제";

    private final AnnouncementComplexRepository announcementComplexes;
    private final SupplyRowRepository supplyRows;
    private final HousingComplexRepository housingComplexes;
    private final Clock clock;

    /** 이 공고의 공고 단지를 이름 순서대로 돌려준다. 상세에 없지만 공급 원천에 나온 단지도 주소 없이 만든다. */
    Map<String, AnnouncementComplex> observe(Announcement announcement, List<DetailComplex> detailComplexes,
            List<LhAnnouncementSupplySourceView> supplies, RentalType supplyType, LhComplexIndex index, Tally tally) {
        Map<String, AnnouncementComplex> stored = announcementComplexes.findAllByAnnouncementOrderByIdAsc(announcement)
                .stream().collect(Collectors.toMap(AnnouncementComplex::getSourceComplexName, Function.identity(),
                        (first, second) -> first, LinkedHashMap::new));
        Map<String, DetailComplex> observed = new LinkedHashMap<>();
        detailComplexes.forEach(complex -> observed.putIfAbsent(complex.name(), complex));
        supplies.forEach(row -> observed.putIfAbsent(row.getComplexLabel(), new DetailComplex(row.getComplexLabel(), null, null)));
        Map<String, Long> migrated = migratedComplexIds(announcement, supplies, observed.keySet());
        Map<String, AnnouncementComplex> result = new LinkedHashMap<>();
        for (DetailComplex detail : observed.values()) {
            AnnouncementComplex complex = stored.remove(detail.name());
            if (complex == null) {
                complex = announcementComplexes.save(AnnouncementComplex.observe(announcement, detail.name(),
                        detail.roadAddress(), detail.totalHouseholdCount()));
                tally.created++;
                link(complex, announcement, migrated.get(detail.name()), supplyType, index, tally);
            }
            else if (complex.observeAgain(detail.roadAddress(), detail.totalHouseholdCount())) {
                tally.updated++;
            }
            result.put(detail.name(), complex);
        }
        stored.values().forEach(absent -> {
            if (absent.markAbsentFromSource()) {
                tally.absent++;
            }
        });
        return result;
    }

    private void link(AnnouncementComplex complex, Announcement announcement, Long migratedComplexId,
            RentalType supplyType, LhComplexIndex index, Tally tally) {
        if (migratedComplexId != null && linkTo(complex, migratedComplexId, ComplexLinkMethod.MIGRATED)) {
            tally.migrated++;
            return;
        }
        Optional<Long> inherited = inheritedComplexId(announcement, complex.getSourceComplexName());
        if (inherited.isPresent() && linkTo(complex, inherited.get(), ComplexLinkMethod.AUTO)) {
            tally.inherited++;
            return;
        }
        Optional<Long> candidate = index.soleCandidate(complex.getRoadAddress(), supplyType);
        if (candidate.isPresent() && linkTo(complex, candidate.get(), ComplexLinkMethod.AUTO)) {
            tally.auto++;
            return;
        }
        tally.unlinked++;
    }

    private boolean linkTo(AnnouncementComplex complex, long housingComplexId, ComplexLinkMethod method) {
        Optional<HousingComplex> target = housingComplexes.findById(housingComplexId)
                .filter(found -> !found.isAdminDeleted());
        return target.isPresent() && complex.linkBySource(target.get(), method, ACTOR, clock.instant());
    }

    /** 정정·취소공고는 원공고에서 같은 이름의 공고 단지가 붙은 단지를 이어받는다. */
    private Optional<Long> inheritedComplexId(Announcement announcement, String name) {
        Announcement previous = announcement.getPreviousAnnouncement();
        if (previous == null) {
            return Optional.empty();
        }
        return announcementComplexes.findAllByAnnouncementOrderByIdAsc(previous).stream()
                .filter(complex -> complex.getSourceComplexName().equals(name))
                .map(AnnouncementComplex::linkedComplex).filter(Objects::nonNull)
                .map(HousingComplex::getId).findFirst();
    }

    /**
     * 기존 공급행이 공고 단지마다 가리키던 제품 단지. LH 공급 원천 식별자로 원천 행의 단지명을 찾고, 공고 단지가 하나뿐이면 모든 행을 그 단지로
     * 본다. 한 공고 단지의 행들이 휴지통이 아닌 단지 하나만 가리킬 때만 옮긴다.
     */
    private Map<String, Long> migratedComplexIds(Announcement announcement, List<LhAnnouncementSupplySourceView> supplies,
            Set<String> names) {
        Map<String, String> labelByOrder = supplies.stream().collect(Collectors.toMap(
                row -> String.valueOf(row.getSourceOrder()), LhAnnouncementSupplySourceView::getComplexLabel,
                (first, second) -> first));
        String onlyName = names.size() == 1 ? names.iterator().next() : null;
        Map<String, Set<Long>> linked = new LinkedHashMap<>();
        for (SupplyRow row : supplyRows.findAllByAnnouncement(announcement)) {
            HousingComplex complex = row.getHousingComplex();
            if (complex == null || complex.isAdminDeleted()) {
                continue;
            }
            String name = LhSourceIdentifier.parseSupply(row.getLhSourceSupplyRowIdentifier())
                    .filter(supply -> supply.panId().equals(announcement.getLhPanId()))
                    .map(supply -> labelByOrder.get(supply.sourceOrder()))
                    .orElse(onlyName);
            if (name != null) {
                linked.computeIfAbsent(name, ignored -> new HashSet<>()).add(complex.getId());
            }
        }
        return linked.entrySet().stream().filter(entry -> entry.getValue().size() == 1)
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().iterator().next()));
    }

    record DetailComplex(String name, String roadAddress, Integer totalHouseholdCount) {
    }

    static final class Tally {

        void add(Tally other) {
            created += other.created;
            updated += other.updated;
            absent += other.absent;
            migrated += other.migrated;
            inherited += other.inherited;
            auto += other.auto;
            unlinked += other.unlinked;
        }

        ComplexCounts toCounts() {
            return new ComplexCounts(created, updated, absent, migrated, inherited, auto, unlinked);
        }

        private int created;
        private int updated;
        private int absent;
        private int migrated;
        private int inherited;
        private int auto;
        private int unlinked;
    }
}

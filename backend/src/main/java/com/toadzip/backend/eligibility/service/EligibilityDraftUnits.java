package com.toadzip.backend.eligibility.service;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.SupplyQuota;
import com.toadzip.backend.announcement.domain.SupplySheet;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.service.AnnouncementSupplies;
import com.toadzip.backend.eligibility.domain.ExtractedEligibilityDraft.Target;
import com.toadzip.backend.eligibility.domain.ExtractedEligibilityDraft.Unit;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * AI 추출 초안의 신청 단위: 공고 공급 읽기 입구({@link AnnouncementSupplies#read})가 보이는 공급행과 그 칸의 공급대상이다. 정본을
 * 확정한 공고는 정본, 확정하기 전 공고는 기관 준비 줄을 공급표로 바꾼 것이다. 공고 상세·단지 상세와 같은 공급표를 읽는다.
 * 칸이 없는 공급행(관리자가 대상을 모두 뺀 행)은 신청할 대상이 없으므로 뺀다. 대상 수는 그 칸이 든 가장 작은 당첨 묶음의 수다.
 * 호출하는 쪽의 트랜잭션 안에서 쓴다.
 */
@Component
class EligibilityDraftUnits {
    private static final String STAGED_PREFIX = "line-";

    private final AnnouncementRepository announcements;
    private final AnnouncementSupplies supplies;

    EligibilityDraftUnits(AnnouncementRepository announcements, AnnouncementSupplies supplies) {
        this.announcements = announcements;
        this.supplies = supplies;
    }

    List<Unit> units(long announcementId) {
        return announcements.findById(announcementId).map(this::units).orElse(List.of());
    }

    private List<Unit> units(Announcement announcement) {
        AnnouncementSupplies.AnnouncementSupply supply = supplies.read(announcement);
        SupplySheet sheet = supply.sheet();
        List<Unit> units = new ArrayList<>();
        for (SupplySheet.Row row : sheet.rows()) {
            List<Target> targets = sheet.cells(row.id()).stream().map(cell -> new Target(cell.label(),
                    cell.applicationCondition(), cell.groups(), sheet.smallestCount(cell, SupplyQuota.Kind.WINNER)))
                    .toList();
            if (!targets.isEmpty()) {
                units.add(unit(announcement, row, unitKey(supply, row), targets));
            }
        }
        return List.copyOf(units);
    }

    /** 확정 전 준비 데이터의 행 ID는 준비 줄 ID라, 정본 공급행 ID와 같은 수여도 다른 단위로 남게 앞머리를 붙인다. */
    private static String unitKey(AnnouncementSupplies.AnnouncementSupply supply, SupplySheet.Row row) {
        if (supply.confirmed()) {
            return Long.toString(row.id());
        }
        return STAGED_PREFIX + row.id();
    }

    private static Unit unit(Announcement announcement, SupplySheet.Row row, String key, List<Target> targets) {
        Optional<HousingComplex> complex = Optional.ofNullable(row.complex());
        Optional<HousingType> type = Optional.ofNullable(row.housingType()).filter(found -> !found.isAdminDeleted());
        String complexLabel = Optional.ofNullable(row.sourceComplexName()).filter(name -> !name.isBlank())
                .or(() -> complex.map(HousingComplex::getName)).orElse(null);
        return new Unit(key, complexLabel, row.displayTypeName(), row.exclusiveArea(),
                complex.map(HousingComplex::getId).orElse(null), type.map(HousingType::getId).orElse(null),
                rentalType(announcement, complex),
                complex.map(HousingComplex::getAddress).map(address -> address.getCityCountyDistrictCode()).stream()
                        .toList(),
                targets);
    }

    /** 저장된 공급유형 코드 원문: 공고의 공급유형, 없으면 단지의 공급유형이다. 서비스가 화면 이름으로 바꾼다. */
    private static String rentalType(Announcement announcement, Optional<HousingComplex> complex) {
        if (announcement.getSupplyType() != null) {
            return announcement.getSupplyType().legacyStoredValue();
        }
        return complex.map(HousingComplex::getSupplyType).orElse(null);
    }
}

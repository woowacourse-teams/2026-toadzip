package com.toadzip.backend.announcement.domain;

import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;

/**
 * 테스트에서 공고 공급표의 주택형을 등록·PDF 적용처럼 만든다. 주택형은 (공고, 단지명) 공고 단지에 들고, 단지는 그 공고 단지의
 * 연결로만 보인다({@link AnnouncementHousingComplex#LINKS}). 공급표를 쓴 공고이므로 공고를 정본 확정으로 둔다.
 */
public final class SupplyRowSeed {

    private static final Instant LINKED_AT = Instant.parse("2026-01-01T00:00:00Z");

    private SupplyRowSeed() {
    }

    /**
     * 저장된 공고의 공고 단지를 찾거나 만들어(저장) 그 공고 단지의 주택형을 만든다. 공고 단지가 아직 단지에 이어지지 않았고
     * 단지를 주면 그 단지로 관리자 연결한다. 주택형 자체는 저장하지 않는다.
     */
    public static SupplyRow create(EntityManager entities, Announcement announcement, HousingComplex housing,
            HousingType type, int displayOrder, String complexName, String typeName, String supplyPnu,
            YearMonth expectedMoveInMonth, SupplyCategory supplyCategory) {
        AnnouncementComplex complex = complex(entities, announcement, complexName.strip(), housing);
        return row(announcement, complex, housing, type, displayOrder, typeName, supplyPnu, expectedMoveInMonth,
                supplyCategory);
    }

    /** 저장하지 않는 단위 테스트용 주택형. 공고 단지도 저장하지 않고 새로 만든다. */
    public static SupplyRow create(Announcement announcement, HousingComplex housing, HousingType type,
            int displayOrder, String complexName, String typeName, String supplyPnu, YearMonth expectedMoveInMonth,
            SupplyCategory supplyCategory) {
        AnnouncementComplex complex = AnnouncementComplex.observe(announcement, complexName.strip(), null, null);
        link(complex, housing);
        return row(announcement, complex, housing, type, displayOrder, typeName, supplyPnu, expectedMoveInMonth,
                supplyCategory);
    }

    /** 이미 저장한 공고 단지의 주택형. 공고 단지가 이은 단지가 주택형의 단지다. */
    public static SupplyRow create(AnnouncementComplex complex, HousingType type, int displayOrder, String typeName,
            String supplyPnu, YearMonth expectedMoveInMonth, SupplyCategory supplyCategory) {
        return row(complex.getAnnouncement(), complex, complex.getHousingComplex(), type, displayOrder, typeName,
                supplyPnu, expectedMoveInMonth, supplyCategory);
    }

    /** 저장된 주택형을 원천 공급표의 행으로(주택형 키 `row:{ID}`) 다시 쓴다. */
    public static SourceSupplySheet.Row sourceRow(SupplyRow saved, Integer householdCount,
            List<SourceSupplySheet.Target> targets) {
        return new SourceSupplySheet.Row(sheetRow("row:" + saved.getId(), saved), saved.isAdminModified(),
                householdCount, targets);
    }

    /** 공급표 쓰기가 같은 행으로 다시 보낼 주택형 값. */
    public static SupplySheetDraft.HousingTypeRow sheetRow(String key, SupplyRow row) {
        HousingComplex housing = housingOf(row.getAnnouncementComplex().linkedComplex(), row.getHousingType());
        return new SupplySheetDraft.HousingTypeRow(key, row.getId(), row.getAnnouncementComplex().getId(),
                idOf(housing), idOf(row.getHousingType()), row.getAnnouncementComplex().getSourceComplexName(),
                row.getHousingTypeName(), row.getExclusiveArea(), row.getSupplyArea(), row.getExpectedMoveInMonth(),
                row.getSupplyPnu(), row.getSupplyCategory(), row.getDisplayOrder());
    }

    private static SupplyRow row(Announcement announcement, AnnouncementComplex complex, HousingComplex housing,
            HousingType type, int displayOrder, String typeName, String supplyPnu, YearMonth expectedMoveInMonth,
            SupplyCategory supplyCategory) {
        HousingComplex owner = housingOf(housing, type);
        SupplySheetDraft.HousingTypeRow values = new SupplySheetDraft.HousingTypeRow("seed", null, complex.getId(),
                idOf(owner), idOf(type), complex.getSourceComplexName(), typeName, null, null, expectedMoveInMonth,
                supplyPnu, supplyCategory, displayOrder);
        announcement.confirmSupply();
        return SupplyRow.createFromSheet(announcement, values, complex, owner, type);
    }

    private static AnnouncementComplex complex(EntityManager entities, Announcement announcement, String name,
            HousingComplex housing) {
        List<AnnouncementComplex> stored = entities.createQuery("""
                SELECT complex FROM AnnouncementComplex complex
                WHERE complex.announcement = :announcement AND complex.sourceComplexName = :name
                """, AnnouncementComplex.class).setParameter("announcement", announcement)
                .setParameter("name", name).getResultList();
        AnnouncementComplex complex;
        if (stored.isEmpty()) {
            complex = AnnouncementComplex.observe(announcement, name, null, null);
            entities.persist(complex);
        } else {
            complex = stored.getFirst();
        }
        link(complex, housing);
        return complex;
    }

    private static void link(AnnouncementComplex complex, HousingComplex housing) {
        if (complex.getHousingComplex() == null && housing != null) {
            complex.linkByAdmin(housing, "test", LINKED_AT);
        }
    }

    /** 주택타입이 있으면 그 단지가 주택타입의 단지다. */
    private static HousingComplex housingOf(HousingComplex housing, HousingType type) {
        if (type != null) {
            return type.getHousingComplex();
        }
        return housing;
    }

    private static Long idOf(HousingComplex housing) {
        if (housing == null) {
            return null;
        }
        return housing.getId();
    }

    private static Long idOf(HousingType type) {
        if (type == null) {
            return null;
        }
        return type.getId();
    }
}

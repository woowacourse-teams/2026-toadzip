package com.toadzip.backend.announcement.domain;

import static jakarta.persistence.EnumType.STRING;
import static jakarta.persistence.FetchType.LAZY;
import static lombok.AccessLevel.PROTECTED;

import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Objects;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 공고 공급표(정본)의 주택형(신청형) 한 줄. 공고 단지에 들고, 이름·전용·공급면적은 공고문 값이다. 단지는 공고 단지가 이은 단지이고
 * ({@link AnnouncementComplex#linkedComplex}), 단지 주택타입 연결은 따로 둔다(같은 면적이어도 같은 형이 아니다).
 * 공고 공급표 쓰기(SupplySheetStore)만 만들고 고친다.
 */
@Getter
@Entity
@Table(name = "supply_rows")
@NoArgsConstructor(access = PROTECTED)
public class SupplyRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "announcement_id", nullable = false)
    private Announcement announcement;

    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "announcement_complex_id", nullable = false)
    private AnnouncementComplex announcementComplex;

    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "housing_type_id")
    private HousingType housingType;

    @Column(nullable = false)
    private String housingTypeName;

    @Column(precision = 10, scale = 4)
    private BigDecimal exclusiveArea;

    @Column(precision = 10, scale = 4)
    private BigDecimal supplyArea;

    @Convert(converter = YearMonthAttributeConverter.class)
    private YearMonth expectedMoveInMonth;

    @Enumerated(STRING)
    @Column(nullable = false)
    private SupplyCategory supplyCategory;

    private String supplyPnu;

    @Column(nullable = false)
    private int displayOrder;

    @Column(nullable = false)
    @org.hibernate.annotations.ColumnDefault("false")
    private boolean adminExcluded;

    @Column(nullable = false)
    @org.hibernate.annotations.ColumnDefault("false")
    private boolean adminModified;

    private SupplyRow(Announcement announcement) {
        if (announcement == null) {
            throw new IllegalArgumentException("공고는 필수다.");
        }
        this.announcement = announcement;
    }

    /** 공고 공급표 쓰기가 처음 만드는 주택형. 값은 {@link #applySheet}와 같다. */
    public static SupplyRow createFromSheet(Announcement announcement, SupplySheetDraft.HousingTypeRow values,
            AnnouncementComplex complex, HousingComplex housing, HousingType type) {
        SupplyRow row = new SupplyRow(announcement);
        row.applySheet(values, complex, housing, type);
        return row;
    }

    /**
     * 공고 공급표가 정한 주택형 값으로 맞춘다. 공고 단지는 이 공고의 것이어야 하고, 주택타입은 공급표가 고른 단지(housing)의
     * 주택타입이어야 한다. 단지 자체는 공고 단지의 연결로 정하므로 행에 두지 않는다. 공급표가 면적을 주지 않으면 연결 주택타입의
     * 면적을 공고 면적으로 쓴다. 공급표가 다루지 않는 열(관리자 제외·수정 표시)은 그대로 둔다.
     */
    public void applySheet(SupplySheetDraft.HousingTypeRow values, AnnouncementComplex complex, HousingComplex housing,
            HousingType type) {
        if (complex == null) {
            throw new IllegalArgumentException("공고 단지는 필수다.");
        }
        if (!sameAnnouncement(complex.getAnnouncement())) {
            throw new IllegalArgumentException("다른 공고의 공고 단지는 연결할 수 없다.");
        }
        if (type != null && (housing == null || !Objects.equals(idOf(type.getHousingComplex()), idOf(housing)))) {
            throw new IllegalArgumentException("주택형은 연결한 단지의 주택형이어야 한다.");
        }
        announcementComplex = complex;
        housingType = type;
        housingTypeName = values.typeName();
        applyAreas(values, type);
        expectedMoveInMonth = values.expectedMoveInMonth();
        supplyPnu = values.supplyPnu();
        supplyCategory = values.supplyCategory();
        displayOrder = values.displayOrder();
    }

    /** 공급표의 같은 주택형이면 true다: 같은 공고 단지의 같은 신청형 이름. */
    public boolean sameSheetHousingType(AnnouncementComplex complex, String typeName) {
        return Objects.equals(announcementComplex.getId(), complex.getId()) && housingTypeName.strip().equals(typeName);
    }

    /**
     * 관리자가 공고 단지를 다른 단지로 이으면 표기값은 그대로 두고 주택타입 연결만 그 단지의 주택타입으로 바꾼다. 면적이 없던 행은
     * 공급표 쓰기({@link #applyAreas})처럼 새로 붙은 주택타입의 면적을 갖는다.
     */
    public boolean relinkByAdminMatch(HousingType type) {
        if (announcement.isAdminDeleted() || Objects.equals(idOf(housingType), idOf(type))) {
            return false;
        }
        housingType = type;
        if (type != null && exclusiveArea == null && supplyArea == null) {
            exclusiveArea = type.getExclusiveArea();
            supplyArea = type.getSupplyArea();
        }
        return true;
    }

    private boolean sameAnnouncement(Announcement other) {
        return other == announcement || other.getId() != null && other.getId().equals(announcement.getId());
    }

    private void applyAreas(SupplySheetDraft.HousingTypeRow values, HousingType type) {
        if (values.overridesArea() || type == null) {
            exclusiveArea = values.exclusiveArea();
            supplyArea = values.supplyArea();
            return;
        }
        exclusiveArea = type.getExclusiveArea();
        supplyArea = type.getSupplyArea();
    }

    private static Long idOf(HousingComplex complex) {
        return complex == null ? null : complex.getId();
    }

    private static Long idOf(HousingType type) {
        return type == null ? null : type.getId();
    }
}

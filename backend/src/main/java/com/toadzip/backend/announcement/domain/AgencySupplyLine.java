package com.toadzip.backend.announcement.domain;

import static jakarta.persistence.EnumType.STRING;
import static jakarta.persistence.FetchType.LAZY;
import static lombok.AccessLevel.PROTECTED;

import com.toadzip.backend.housing.domain.AgencyCode;
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
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Objects;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * 기관(LH·GH) 원천이 준 공급 한 줄(준비 데이터). 정제가 원천 그대로 다시 쓰고 관리자는 고치지 않는다. 공고 공급표(정본)를 확정하기 전
 * 공개·관리 조회가 이 줄을 공급표로 바꿔 보인다({@link AgencySupplySheet}). 단지는 공고 단지로만 얻고 주택타입 연결은 두지 않는다.
 */
@Getter
@Entity
@Table(name = "agency_supply_lines", uniqueConstraints = @UniqueConstraint(
        name = "uk_agency_supply_lines_source_line", columnNames = {"announcement_id", "source_line_key"}))
@NoArgsConstructor(access = PROTECTED)
public class AgencySupplyLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "announcement_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Announcement announcement;

    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "announcement_complex_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private AnnouncementComplex announcementComplex;

    @Enumerated(STRING)
    @Column(nullable = false, length = 10)
    private AgencyCode agency;

    @Column(nullable = false)
    private String sourceLineKey;

    @Column(nullable = false)
    private int displayOrder;

    @Column(nullable = false)
    private String complexName;

    @Column(nullable = false)
    private String housingTypeName;

    @Column(precision = 10, scale = 4)
    private BigDecimal exclusiveArea;

    @Column(precision = 10, scale = 4)
    private BigDecimal supplyArea;

    /** 원천이 대상 표기를 정해 준 줄만 채운다. 비어 있으면 공급표로 바꿀 때 LH 형명 규칙으로 정한다. */
    private String targetLabel;

    private Integer householdCount;

    @Column(precision = 38, scale = 2)
    private BigDecimal rentalDeposit;

    @Column(precision = 38, scale = 2)
    private BigDecimal monthlyRent;

    @Convert(converter = YearMonthAttributeConverter.class)
    private YearMonth expectedMoveInMonth;

    @Enumerated(STRING)
    @Column(nullable = false)
    private SupplyCategory supplyCategory;

    private AgencySupplyLine(Announcement announcement, AgencyCode agency, Values values) {
        if (announcement == null || agency == null) {
            throw new IllegalArgumentException("공고와 기관은 필수다.");
        }
        this.announcement = announcement;
        this.agency = agency;
        this.sourceLineKey = values.sourceLineKey();
        apply(values);
    }

    public static AgencySupplyLine create(Announcement announcement, AgencyCode agency, Values values) {
        return new AgencySupplyLine(announcement, agency, values);
    }

    /** 이번 원천 값으로 맞춘다. 바뀌었으면 true다. */
    public boolean apply(Values values) {
        if (!sourceLineKey.equals(values.sourceLineKey())) {
            throw new IllegalArgumentException("다른 원천 줄의 값이다: " + values.sourceLineKey());
        }
        Long complexId = values.announcementComplex() == null ? null : values.announcementComplex().getId();
        Long storedComplexId = announcementComplex == null ? null : announcementComplex.getId();
        boolean same = Objects.equals(storedComplexId, complexId) && displayOrder == values.displayOrder()
                && values.complexName().equals(complexName) && values.housingTypeName().equals(housingTypeName)
                && sameNumber(exclusiveArea, values.exclusiveArea()) && sameNumber(supplyArea, values.supplyArea())
                && Objects.equals(targetLabel, values.targetLabel())
                && Objects.equals(householdCount, values.householdCount())
                && sameNumber(rentalDeposit, values.rentalDeposit()) && sameNumber(monthlyRent, values.monthlyRent())
                && Objects.equals(expectedMoveInMonth, values.expectedMoveInMonth())
                && supplyCategory == values.supplyCategory();
        if (same) {
            return false;
        }
        announcementComplex = values.announcementComplex();
        displayOrder = values.displayOrder();
        complexName = values.complexName();
        housingTypeName = values.housingTypeName();
        exclusiveArea = values.exclusiveArea();
        supplyArea = values.supplyArea();
        targetLabel = values.targetLabel();
        householdCount = values.householdCount();
        rentalDeposit = values.rentalDeposit();
        monthlyRent = values.monthlyRent();
        expectedMoveInMonth = values.expectedMoveInMonth();
        supplyCategory = values.supplyCategory();
        return true;
    }

    /** DB는 소수 자리를 늘려 저장해(36.8000) 값 크기로 비교한다. */
    private static boolean sameNumber(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.compareTo(right) == 0;
    }

    /** 원천 한 줄의 값. 원천 줄 키는 공고 안에서 하나다. 수·면적·금액은 모르면 null이다. */
    public record Values(String sourceLineKey, int displayOrder, AnnouncementComplex announcementComplex,
            String complexName, String housingTypeName, BigDecimal exclusiveArea, BigDecimal supplyArea,
            String targetLabel, Integer householdCount, BigDecimal rentalDeposit, BigDecimal monthlyRent,
            YearMonth expectedMoveInMonth, SupplyCategory supplyCategory) {

        public Values {
            sourceLineKey = required(sourceLineKey, "원천 줄 키");
            complexName = required(complexName, "단지명");
            housingTypeName = required(housingTypeName, "형명");
            if (targetLabel != null && targetLabel.isBlank()) {
                targetLabel = null;
            }
            if (displayOrder < 0) {
                throw new IllegalArgumentException("표시 순서는 음수일 수 없다.");
            }
            if (householdCount != null && householdCount < 0) {
                throw new IllegalArgumentException("세대수는 음수일 수 없다.");
            }
            if (supplyCategory == null) {
                throw new IllegalArgumentException("공급구분은 필수다.");
            }
        }

        private static String required(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + "은 필수다.");
            }
            String stripped = value.strip();
            return stripped.length() <= 255 ? stripped : stripped.substring(0, 254) + "…";
        }
    }
}

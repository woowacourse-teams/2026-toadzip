package com.toadzip.backend.announcement.domain;

import static jakarta.persistence.FetchType.LAZY;
import static lombok.AccessLevel.PROTECTED;

import com.toadzip.backend.housing.domain.HousingComplex;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * LH 공고 상세에 나온 단지 하나. 이름은 같은 공고 안에서만 키이고, 공고를 넘어서는 단지 식별은 연결한 제품 단지뿐이다.
 * 원천에서 사라진 공고 단지는 지우지 않고 원천에 없음으로 둔다. 공고문으로 확정한 공급행이 그 단지를 계속 가리킬 수 있기 때문이다.
 */
@Getter
@Entity
@Table(name = "announcement_complexes", uniqueConstraints = @UniqueConstraint(
        name = "uk_announcement_complexes_name", columnNames = {"announcement_id", "source_complex_name"}))
@NoArgsConstructor(access = PROTECTED)
public class AnnouncementComplex {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "announcement_id", nullable = false)
    private Announcement announcement;

    @Column(nullable = false)
    private String sourceComplexName;

    @Column(length = 500)
    private String roadAddress;

    private Integer totalHouseholdCount;

    @Column(nullable = false)
    @org.hibernate.annotations.ColumnDefault("true")
    private boolean presentInSource;

    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "housing_complex_id")
    private HousingComplex housingComplex;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ComplexLinkMethod linkMethod;

    private String linkedBy;

    private Instant linkedAt;

    @org.hibernate.annotations.CreationTimestamp
    @Column(nullable = false, updatable = false)
    @org.hibernate.annotations.ColumnDefault("CURRENT_TIMESTAMP")
    private Instant createdAt;

    public static AnnouncementComplex observe(Announcement announcement, String name, String roadAddress,
            Integer totalHouseholdCount) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("공고 단지 이름이 필요합니다.");
        }
        AnnouncementComplex complex = new AnnouncementComplex();
        complex.announcement = announcement;
        complex.sourceComplexName = name;
        complex.roadAddress = roadAddress;
        complex.totalHouseholdCount = totalHouseholdCount;
        complex.presentInSource = true;
        return complex;
    }

    /** 이번 원천의 주소·세대수로 갱신하고 원천에 있음으로 둔다. 바뀐 값이 있으면 true다. */
    public boolean observeAgain(String roadAddress, Integer totalHouseholdCount) {
        if (presentInSource && Objects.equals(this.roadAddress, roadAddress)
                && Objects.equals(this.totalHouseholdCount, totalHouseholdCount)) {
            return false;
        }
        this.roadAddress = roadAddress;
        this.totalHouseholdCount = totalHouseholdCount;
        presentInSource = true;
        return true;
    }

    public boolean markAbsentFromSource() {
        if (!presentInSource) {
            return false;
        }
        presentInSource = false;
        return true;
    }

    /** 정제는 아직 연결이 없는 공고 단지만 연결한다. 이미 붙은 연결은 정제가 다시 계산하지 않는다. */
    public boolean linkBySource(HousingComplex complex, ComplexLinkMethod method, String actor, Instant at) {
        if (method == ComplexLinkMethod.ADMIN) {
            throw new IllegalArgumentException("관리자 연결은 PDF 보정에서 정합니다.");
        }
        if (housingComplex != null || complex == null) {
            return false;
        }
        housingComplex = complex;
        linkMethod = method;
        linkedBy = actor;
        linkedAt = at;
        return true;
    }

    /**
     * PDF·공급 보정에서 관리자가 정한 단지로 연결한다. 자동·이관 연결과 같은 단지여도 관리자가 확인했으므로 관리자 연결이 된다.
     * 바뀐 것이 있으면 true다.
     */
    public boolean linkByAdmin(HousingComplex complex, String actor, Instant at) {
        if (complex == null) {
            throw new IllegalArgumentException("연결할 단지가 필요합니다.");
        }
        if (linkMethod == ComplexLinkMethod.ADMIN && housingComplex != null
                && Objects.equals(housingComplex.getId(), complex.getId())) {
            return false;
        }
        housingComplex = complex;
        linkMethod = ComplexLinkMethod.ADMIN;
        linkedBy = actor;
        linkedAt = at;
        return true;
    }

    /** 연결된 제품 단지. 연결 단지가 휴지통에 있으면 미연결로 본다. */
    public HousingComplex linkedComplex() {
        if (housingComplex == null || housingComplex.isAdminDeleted()) {
            return null;
        }
        return housingComplex;
    }
}

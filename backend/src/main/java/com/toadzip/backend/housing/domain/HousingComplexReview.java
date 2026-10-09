package com.toadzip.backend.housing.domain;

import static jakarta.persistence.FetchType.LAZY;
import static lombok.AccessLevel.PROTECTED;

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
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;

@Getter
@Entity
@Table(name = "housing_complex_reviews")
@NoArgsConstructor(access = PROTECTED)
public class HousingComplexReview {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "housing_complex_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private HousingComplex housingComplex;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ComplexReviewOutcome outcome;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String checkedValues;

    @Column(length = 2048)
    private String evidenceUrl;

    @Column(nullable = false, length = 2000)
    private String evidenceNote;

    @Column(nullable = false)
    private String actor;

    @Column(nullable = false)
    private Instant reviewedAt;

    public HousingComplexReview(HousingComplex complex, ComplexReviewOutcome outcome, String checkedValues,
            String evidenceUrl, String evidenceNote, String actor, Instant reviewedAt) {
        if (complex.isAdminDeleted()) {
            throw new IllegalArgumentException("휴지통에서 복구한 뒤 검토해 주세요.");
        }
        if (outcome == null || checkedValues == null || evidenceNote == null || evidenceNote.isBlank()
                || actor == null || actor.isBlank() || reviewedAt == null) {
            throw new IllegalArgumentException("확인 범위와 근거, 확인자는 필수입니다.");
        }
        this.housingComplex = complex;
        this.outcome = outcome;
        this.checkedValues = checkedValues;
        this.evidenceUrl = evidenceUrl;
        this.evidenceNote = evidenceNote.strip();
        this.actor = actor;
        this.reviewedAt = reviewedAt;
    }
}

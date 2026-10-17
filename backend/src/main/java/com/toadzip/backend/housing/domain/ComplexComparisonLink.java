package com.toadzip.backend.housing.domain;

import static lombok.AccessLevel.PROTECTED;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "complex_comparison_links", uniqueConstraints = @UniqueConstraint(
        columnNames = {"housing_complex_id", "category", "source_key"}))
@NoArgsConstructor(access = PROTECTED)
public class ComplexComparisonLink {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false) private long housingComplexId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30) private ComplexComparisonSourceType category;
    @Column(nullable = false, length = 512) private String sourceKey;
    @Column(nullable = false, length = 2000) private String evidenceNote;
    @Column(nullable = false) private String actor;
    @Column(nullable = false) private Instant linkedAt;

    public ComplexComparisonLink(long complexId, ComplexComparisonSourceType category, String sourceKey,
            String evidenceNote, String actor, Instant linkedAt) {
        if (complexId <= 0 || category == null || sourceKey == null || sourceKey.isBlank()
                || evidenceNote == null || evidenceNote.isBlank() || actor == null || actor.isBlank()
                || linkedAt == null) {
            throw new IllegalArgumentException("비교 원천과 연결 근거, 확인자는 필수입니다.");
        }
        housingComplexId = complexId;
        this.category = category;
        this.sourceKey = sourceKey;
        this.evidenceNote = evidenceNote.strip();
        this.actor = actor;
        this.linkedAt = linkedAt.truncatedTo(ChronoUnit.MICROS);
    }
}

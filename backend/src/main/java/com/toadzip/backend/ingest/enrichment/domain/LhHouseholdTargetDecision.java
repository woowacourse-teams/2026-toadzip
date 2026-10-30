package com.toadzip.backend.ingest.enrichment.domain;

import static lombok.AccessLevel.PROTECTED;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 여러 LH 임대 목록 묶음이 같은 단지에 매칭되고 세대수가 서로 다를 때, 관리자가 그 단지에 쓸 묶음을 정하거나
 * 세대수 보강을 하지 않기로 한 결정이다. 저장 당시 경쟁 묶음 구성·값과 단지 지문이 지금과 다르면 쓰지 않는다.
 */
@Entity
@Getter
@Table(name = "lh_household_target_decisions")
@NoArgsConstructor(access = PROTECTED)
public class LhHouseholdTargetDecision {
    public enum Decision { USE_SOURCE, SKIP }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private long version;

    @Column(nullable = false, unique = true)
    private long housingComplexId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Decision decision;

    @Column(length = 500)
    private String chosenSourceKey;

    @Column(nullable = false, length = 64)
    private String sourcesFingerprint;

    @Column(nullable = false, length = 64)
    private String targetFingerprint;

    @Column(nullable = false, length = 2000)
    private String reason;

    @Column(nullable = false)
    private String actor;

    @Column(nullable = false)
    private Instant updatedAt;

    public LhHouseholdTargetDecision(long housingComplexId) {
        this.housingComplexId = housingComplexId;
    }

    public void confirm(Decision decision, String chosenSourceKey, String sourcesFingerprint,
            String targetFingerprint, String reason, String actor) {
        if ((decision == Decision.USE_SOURCE) != (chosenSourceKey != null)) {
            throw new IllegalArgumentException("묶음을 쓰는 결정에만 선택한 묶음이 필요합니다.");
        }
        this.decision = decision;
        this.chosenSourceKey = chosenSourceKey;
        this.sourcesFingerprint = sourcesFingerprint;
        this.targetFingerprint = targetFingerprint;
        this.reason = reason;
        this.actor = actor;
        this.updatedAt = Instant.now();
    }
}

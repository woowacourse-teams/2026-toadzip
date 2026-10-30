package com.toadzip.backend.ingest.enrichment.domain;

import static lombok.AccessLevel.PROTECTED;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 관리자가 확인한 LH 임대 목록 원천 그룹과 정제 단지의 연결이다. 이름이 달라 자동으로 고르지 못한 유일 후보에만 저장한다.
 * 저장 당시 원천·단지 지문이 지금과 다르면 세대수 보강은 이 연결을 쓰지 않고 재확인을 요구한다.
 */
@Entity
@Getter
@Table(name = "lh_household_complex_links")
@NoArgsConstructor(access = PROTECTED)
public class LhHouseholdComplexLink {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private long version;

    @Column(nullable = false, unique = true, length = 500)
    private String sourceKey;

    private Long housingComplexId;

    @Column(nullable = false, length = 64)
    private String sourceFingerprint;

    @Column(nullable = false, length = 64)
    private String targetFingerprint;

    @Column(length = 2048)
    private String sourceUrl;

    @Column(nullable = false, length = 2000)
    private String reason;

    @Column(nullable = false)
    private String actor;

    @Column(nullable = false)
    private Instant updatedAt;

    public LhHouseholdComplexLink(String sourceKey) {
        this.sourceKey = sourceKey;
    }

    public void confirm(long housingComplexId, String sourceFingerprint, String targetFingerprint, String sourceUrl,
            String reason, String actor) {
        this.housingComplexId = housingComplexId;
        this.sourceFingerprint = sourceFingerprint;
        this.targetFingerprint = targetFingerprint;
        this.sourceUrl = sourceUrl;
        this.reason = reason;
        this.actor = actor;
        this.updatedAt = Instant.now();
    }
}

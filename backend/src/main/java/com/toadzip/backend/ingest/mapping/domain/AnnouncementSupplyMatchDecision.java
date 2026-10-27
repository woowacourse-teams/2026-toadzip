package com.toadzip.backend.ingest.mapping.domain;

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

@Entity
@Getter
@Table(name = "announcement_supply_match_decisions")
@NoArgsConstructor(access = PROTECTED)
public class AnnouncementSupplyMatchDecision {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private long version;

    @Column(nullable = false, unique = true, length = 500)
    private String rowIdentifier;
    private Long complexId;
    private Long housingTypeId;
    @Column(nullable = false, length = 64)
    private String sourceFingerprint;

    @Column(nullable = false, length = 64)
    private String targetFingerprint;

    @Column(nullable = false, length = 2048)
    private String sourceUrl;

    @Column(nullable = false, length = 2000)
    private String reason;

    @Column(nullable = false)
    private String actor;

    @Column(nullable = false)
    private Instant updatedAt;

    public AnnouncementSupplyMatchDecision(String rowIdentifier) {
        this.rowIdentifier = rowIdentifier;
    }

    /** 원천 분할 단지를 합쳐 같은 주택형의 소속 단지만 바뀐 경우다. 확인자와 근거는 그대로 둔다. */
    public void moveToComplex(long complexId, String targetFingerprint) {
        this.complexId = complexId;
        this.targetFingerprint = targetFingerprint;
    }

    public void confirm(long complexId, long housingTypeId, String sourceFingerprint, String targetFingerprint,
            String sourceUrl, String reason, String actor) {
        this.complexId = complexId;
        this.housingTypeId = housingTypeId;
        this.sourceFingerprint = sourceFingerprint;
        this.targetFingerprint = targetFingerprint;
        this.sourceUrl = sourceUrl;
        this.reason = reason;
        this.actor = actor;
        this.updatedAt = Instant.now();
    }
}

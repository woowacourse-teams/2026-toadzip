package com.toadzip.backend.ingest.domain;

import static lombok.AccessLevel.PROTECTED;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "lh_announcement_enrichment_failures")
@NoArgsConstructor(access = PROTECTED)
public class LhAnnouncementEnrichmentFailure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 500)
    private String sourceKey;

    private String sourceAnnouncementIdentifier;

    private String panId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private LhAnnouncementEnrichmentFailureReason reason;

    @Column(nullable = false, length = 1000)
    private String detail;

    @Column(nullable = false)
    private Instant occurredAt;

    private LhAnnouncementEnrichmentFailure(
            String sourceKey,
            String sourceAnnouncementIdentifier,
            String panId,
            LhAnnouncementEnrichmentFailureReason reason,
            String detail,
            Instant occurredAt
    ) {
        requireText(sourceKey, "원천 키");
        require(reason, "실패 사유");
        requireText(detail, "실패 상세");
        require(occurredAt, "실패 시각");
        this.sourceKey = sourceKey;
        this.sourceAnnouncementIdentifier = sourceAnnouncementIdentifier;
        this.panId = panId;
        this.reason = reason;
        this.detail = detail;
        this.occurredAt = occurredAt;
    }

    public static LhAnnouncementEnrichmentFailure create(
            String sourceKey,
            String sourceAnnouncementIdentifier,
            String panId,
            LhAnnouncementEnrichmentFailureReason reason,
            String detail,
            Instant occurredAt
    ) {
        return new LhAnnouncementEnrichmentFailure(
                sourceKey, sourceAnnouncementIdentifier, panId, reason, detail, occurredAt
        );
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "은 필수입니다.");
        }
    }

    private static void require(Object value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + "은 필수입니다.");
        }
    }
}

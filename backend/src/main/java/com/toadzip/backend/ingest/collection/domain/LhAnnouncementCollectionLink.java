package com.toadzip.backend.ingest.collection.domain;

import static lombok.AccessLevel.PROTECTED;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(
        name = "lh_announcement_collection_links",
        indexes = @Index(
                name = "idx_lh_announcement_link_source_request_hash",
                columnList = "source, request_hash"
        ),
        uniqueConstraints = @UniqueConstraint(
                name = "uk_lh_announcement_link_source_announcement",
                columnNames = {"source", "source_announcement_key"}
        )
)
@NoArgsConstructor(access = PROTECTED)
public class LhAnnouncementCollectionLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ExternalDataSource source;

    @Column(name = "source_announcement_key", nullable = false, length = 500)
    private String sourceAnnouncementKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "request_description", nullable = false, length = 2000)
    private String requestDescription;

    @Column(name = "pan_id", nullable = false, length = 100)
    private String panId;

    @Column(name = "completed_at", nullable = false)
    private Instant completedAt;

    private LhAnnouncementCollectionLink(
            ExternalDataSource source,
            String sourceAnnouncementKey,
            String requestDescription,
            String panId,
            Instant completedAt
    ) {
        validateSource(source);
        validateNotBlank(sourceAnnouncementKey, "원천 공고 식별자");
        validateNotBlank(requestDescription, "조회 조건");
        validateNotBlank(panId, "LH 공고 식별자");
        if (completedAt == null) {
            throw new IllegalArgumentException("완료 시각은 필수입니다.");
        }
        this.source = source;
        this.sourceAnnouncementKey = sourceAnnouncementKey.strip();
        this.requestDescription = requestDescription.strip();
        requestHash = LhAnnouncementCollectionCheckpoint.requestHashOf(requestDescription);
        this.panId = panId.strip();
        this.completedAt = completedAt;
    }

    public static LhAnnouncementCollectionLink complete(
            ExternalDataSource source,
            String sourceAnnouncementKey,
            String requestDescription,
            String panId,
            Instant completedAt
    ) {
        return new LhAnnouncementCollectionLink(
                source,
                sourceAnnouncementKey,
                requestDescription,
                panId,
                completedAt
        );
    }

    public void updateFrom(String requestDescription, String panId, Instant completedAt) {
        validateNotBlank(requestDescription, "조회 조건");
        validateNotBlank(panId, "LH 공고 식별자");
        if (completedAt == null) {
            throw new IllegalArgumentException("완료 시각은 필수입니다.");
        }
        this.requestDescription = requestDescription.strip();
        requestHash = LhAnnouncementCollectionCheckpoint.requestHashOf(requestDescription);
        this.panId = panId.strip();
        this.completedAt = completedAt;
    }

    private static void validateSource(ExternalDataSource source) {
        boolean supported = source == ExternalDataSource.LH_ANNOUNCEMENT_DETAIL
                || source == ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY;
        if (!supported) {
            throw new IllegalArgumentException(
                    "LH 공고 상세·공급 원천만 연결할 수 있습니다."
            );
        }
    }

    private static void validateNotBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "은 필수입니다.");
        }
    }
}

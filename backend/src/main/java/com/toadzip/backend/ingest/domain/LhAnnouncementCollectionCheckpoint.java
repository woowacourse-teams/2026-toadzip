package com.toadzip.backend.ingest.domain;

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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(
        name = "lh_announcement_collection_checkpoints",
        indexes = @Index(
                name = "idx_lh_announcement_checkpoint_source_pan_id",
                columnList = "source, pan_id"
        ),
        uniqueConstraints = @UniqueConstraint(columnNames = {"source", "request_hash"})
)
@NoArgsConstructor(access = PROTECTED)
public class LhAnnouncementCollectionCheckpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ExternalDataSource source;

    @Column(nullable = false, length = 500)
    private String sourceAnnouncementKey;

    @Column(nullable = false, length = 64)
    private String requestHash;

    @Column(nullable = false, length = 2000)
    private String requestDescription;

    @Column(nullable = false, length = 100)
    private String panId;

    @Column(nullable = false)
    private Instant completedAt;

    private LhAnnouncementCollectionCheckpoint(
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
        requestHash = requestHashOf(requestDescription);
        this.requestDescription = requestDescription.strip();
        this.panId = panId.strip();
        this.completedAt = completedAt;
    }

    public static LhAnnouncementCollectionCheckpoint complete(
            ExternalDataSource source,
            String sourceAnnouncementKey,
            String requestDescription,
            String panId,
            Instant completedAt
    ) {
        return new LhAnnouncementCollectionCheckpoint(
                source,
                sourceAnnouncementKey,
                requestDescription,
                panId,
                completedAt
        );
    }

    public static String requestHashOf(String requestDescription) {
        validateNotBlank(requestDescription, "조회 조건");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(requestDescription.strip().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "조회 조건 hash 알고리즘을 사용할 수 없습니다.",
                    exception
            );
        }
    }

    private static void validateSource(ExternalDataSource source) {
        boolean supported = source == ExternalDataSource.LH_ANNOUNCEMENT_DETAIL
                || source == ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY;
        if (!supported) {
            throw new IllegalArgumentException(
                    "LH 공고 상세·공급 원천만 체크포인트를 저장할 수 있습니다."
            );
        }
    }

    private static void validateNotBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "은 필수입니다.");
        }
    }
}

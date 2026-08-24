package com.toadzip.backend.ingest.domain;

import static lombok.AccessLevel.PROTECTED;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PostLoad;
import jakarta.persistence.Table;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "external_api_data")
@NoArgsConstructor(access = PROTECTED)
public class ExternalApiData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ExternalApi externalApi;

    @Column(nullable = false, length = 2000)
    private String requestDescription;

    @Column(nullable = false)
    private int page;

    @Column(nullable = false)
    private Instant collectedAt;

    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private LhNoticeProcessingStatus lhNoticeProcessingStatus;

    private Instant lhNoticeProcessedAt;

    @Lob
    @Column(nullable = false)
    private String apiData;

    private ExternalApiData(
            ExternalApi externalApi,
            String requestDescription,
            int page,
            Instant collectedAt,
            String apiData
    ) {
        validateRequired(externalApi, "외부 API");
        validateNotBlank(requestDescription, "조회 조건");
        validatePage(page);
        validateRequired(collectedAt, "수집 시각");
        validateNotBlank(apiData, "API 데이터");
        this.externalApi = externalApi;
        this.requestDescription = requestDescription;
        this.page = page;
        this.collectedAt = collectedAt;
        this.contentHash = contentHashOf(apiData);
        if (externalApi == ExternalApi.MYHOME_NOTICE) {
            this.lhNoticeProcessingStatus = LhNoticeProcessingStatus.PENDING;
        }
        this.apiData = apiData;
    }

    public static ExternalApiData create(
            ExternalApi externalApi,
            String requestDescription,
            int page,
            Instant collectedAt,
            String apiData
    ) {
        return new ExternalApiData(externalApi, requestDescription, page, collectedAt, apiData);
    }

    public void completeLhNoticeProcessing(Instant processedAt) {
        changeLhNoticeProcessingStatus(LhNoticeProcessingStatus.COMPLETED, processedAt);
    }

    public void failLhNoticeProcessing(Instant processedAt) {
        changeLhNoticeProcessingStatus(LhNoticeProcessingStatus.FAILED, processedAt);
    }

    @PostLoad
    private void initializeCollectionMetadata() {
        if (contentHash == null) {
            contentHash = contentHashOf(apiData);
        }
        if (externalApi == ExternalApi.MYHOME_NOTICE && lhNoticeProcessingStatus == null) {
            lhNoticeProcessingStatus = LhNoticeProcessingStatus.PENDING;
        }
    }

    private void changeLhNoticeProcessingStatus(
            LhNoticeProcessingStatus status,
            Instant processedAt
    ) {
        validateRequired(processedAt, "LH 공고 처리 시각");
        if (externalApi != ExternalApi.MYHOME_NOTICE) {
            throw new IllegalStateException("마이홈 공고 API 데이터만 LH 공고 처리 상태를 변경할 수 있습니다.");
        }
        if (lhNoticeProcessingStatus != null
                && lhNoticeProcessingStatus != LhNoticeProcessingStatus.PENDING) {
            return;
        }
        lhNoticeProcessingStatus = status;
        lhNoticeProcessedAt = processedAt;
    }

    private static String contentHashOf(String apiData) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(apiData.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "API 데이터 hash 알고리즘을 사용할 수 없습니다.",
                    exception
            );
        }
    }

    private void validatePage(int value) {
        if (value < 1) {
            throw new IllegalArgumentException("페이지는 1 이상이어야 합니다.");
        }
    }

    private void validateNotBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "은 필수입니다.");
        }
    }

    private void validateRequired(Object value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + "은 필수입니다.");
        }
    }
}

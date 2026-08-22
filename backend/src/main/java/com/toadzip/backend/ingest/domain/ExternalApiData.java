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
import jakarta.persistence.Table;
import java.time.Instant;
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

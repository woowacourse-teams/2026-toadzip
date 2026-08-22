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
@Table(name = "external_api_collection_failures")
@NoArgsConstructor(access = PROTECTED)
public class ExternalApiCollectionFailure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ExternalApi externalApi;

    @Column(nullable = false, length = 2000)
    private String requestDescription;

    @Column(nullable = false)
    private Instant occurredAt;

    @Column(nullable = false, length = 120)
    private String errorType;

    @Column(nullable = false, length = 1000)
    private String reason;

    private ExternalApiCollectionFailure(
            ExternalApi externalApi,
            String requestDescription,
            Instant occurredAt,
            String errorType,
            String reason
    ) {
        validateRequired(externalApi, "외부 API");
        validateNotBlank(requestDescription, "조회 조건");
        validateRequired(occurredAt, "실패 시각");
        validateNotBlank(errorType, "오류 유형");
        validateNotBlank(reason, "실패 원인");
        this.externalApi = externalApi;
        this.requestDescription = requestDescription;
        this.occurredAt = occurredAt;
        this.errorType = errorType;
        this.reason = reason;
    }

    public static ExternalApiCollectionFailure create(
            ExternalApi externalApi,
            String requestDescription,
            Instant occurredAt,
            String errorType,
            String reason
    ) {
        return new ExternalApiCollectionFailure(externalApi, requestDescription, occurredAt, errorType, reason);
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

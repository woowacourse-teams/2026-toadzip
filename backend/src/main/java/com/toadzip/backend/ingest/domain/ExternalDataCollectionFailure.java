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
@Table(name = "external_data_collection_failures")
@NoArgsConstructor(access = PROTECTED)
public class ExternalDataCollectionFailure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ExternalDataSource source;

    @Column(nullable = false, length = 2000)
    private String requestDescription;

    @Column(nullable = false)
    private Instant occurredAt;

    @Column(nullable = false, length = 120)
    private String errorType;

    @Column(nullable = false, length = 1000)
    private String reason;

    private ExternalDataCollectionFailure(
            ExternalDataSource source,
            String requestDescription,
            Instant occurredAt,
            String errorType,
            String reason
    ) {
        validateRequired(source, "외부 데이터 출처");
        validateNotBlank(requestDescription, "조회 조건");
        validateRequired(occurredAt, "실패 시각");
        validateNotBlank(errorType, "오류 유형");
        validateNotBlank(reason, "실패 원인");
        this.source = source;
        this.requestDescription = requestDescription;
        this.occurredAt = occurredAt;
        this.errorType = errorType;
        this.reason = reason;
    }

    public static ExternalDataCollectionFailure create(
            ExternalDataSource source,
            String requestDescription,
            Instant occurredAt,
            String errorType,
            String reason
    ) {
        return new ExternalDataCollectionFailure(source, requestDescription, occurredAt, errorType, reason);
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

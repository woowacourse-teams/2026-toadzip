package com.toadzip.backend.ingest.collection.domain;

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
import java.util.UUID;
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

    @Column(nullable = false, columnDefinition = "integer default 1")
    private Integer attemptCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, columnDefinition = "varchar(20) default 'PENDING'")
    private ExternalDataFailureStatus status;

    private Instant resolvedAt;

    @Column(nullable = false)
    private Instant lastOccurredAt;

    @Column(nullable = false)
    private int occurrenceCount;

    @Column(nullable = false)
    private int recurrenceCount;

    private UUID firstExecutionId;

    private UUID lastExecutionId;

    private UUID resolvedExecutionId;

    @Column(nullable = false, length = 120)
    private String errorType;

    @Column(nullable = false, length = 1000)
    private String reason;

    private ExternalDataCollectionFailure(
            ExternalDataSource source,
            String requestDescription,
            Instant occurredAt,
            int attemptCount,
            String errorType,
            String reason
    ) {
        validateRequired(source, "외부 데이터 출처");
        validateNotBlank(requestDescription, "조회 조건");
        validateRequired(occurredAt, "실패 시각");
        if (attemptCount < 0) {
            throw new IllegalArgumentException("시도 횟수는 음수일 수 없습니다.");
        }
        validateNotBlank(errorType, "오류 유형");
        validateNotBlank(reason, "실패 원인");
        this.source = source;
        this.requestDescription = requestDescription;
        this.occurredAt = occurredAt;
        this.attemptCount = attemptCount;
        status = ExternalDataFailureStatus.PENDING;
        this.errorType = errorType;
        this.reason = reason;
        lastOccurredAt = occurredAt;
        occurrenceCount = 1;
    }

    public static ExternalDataCollectionFailure create(
            ExternalDataSource source,
            String requestDescription,
            Instant occurredAt,
            int attemptCount,
            String errorType,
            String reason
    ) {
        return new ExternalDataCollectionFailure(
                source,
                requestDescription,
                occurredAt,
                attemptCount,
                errorType,
                reason
        );
    }

    public void observe(ExternalDataCollectionFailure observed, UUID executionId) {
        validateRequired(observed, "관찰한 실패");
        lastOccurredAt = observed.occurredAt;
        attemptCount = observed.attemptCount;
        errorType = observed.errorType;
        reason = observed.reason;
        occurrenceCount++;
        if (status != ExternalDataFailureStatus.PENDING) {
            recurrenceCount++;
        }
        status = ExternalDataFailureStatus.PENDING;
        lastExecutionId = executionId;
    }

    public void attachFirstExecution(UUID executionId) {
        firstExecutionId = executionId;
        lastExecutionId = executionId;
    }

    public void resolve(Instant resolvedAt, UUID executionId) {
        validateRequired(resolvedAt, "해결 시각");
        status = ExternalDataFailureStatus.RESOLVED;
        this.resolvedAt = resolvedAt;
        resolvedExecutionId = executionId;
    }

    public void skip(Instant skippedAt, String skipReason, UUID executionId) {
        validateRequired(skippedAt, "건너뛴 시각");
        validateNotBlank(skipReason, "건너뛴 사유");
        status = ExternalDataFailureStatus.SKIPPED;
        resolvedAt = skippedAt;
        errorType = "NOT_APPLICABLE";
        reason = skipReason;
        resolvedExecutionId = executionId;
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

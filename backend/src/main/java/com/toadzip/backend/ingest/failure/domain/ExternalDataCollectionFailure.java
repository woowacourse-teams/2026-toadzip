package com.toadzip.backend.ingest.failure.domain;

import static lombok.AccessLevel.PROTECTED;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 외부 데이터 수집 요청 하나의 실패다. 원천 키는 조회 조건, 실패 종류는 오류 유형, 상세는 실패 원인이다.
 * 기존 테이블의 컬럼 이름과 길이를 그대로 쓴다. 같은 실패는 출처와 조회 조건의 최신 행으로 찾고 오류 유형은 다시 관찰할 때
 * 바뀌므로, 원천 키와 실패 종류로 묶는 {@link IngestFailureReconciler}의 대상이 아니다.
 */
@Getter
@Entity
@Table(name = "external_data_collection_failures")
@AttributeOverride(name = "sourceKey", column = @Column(name = "request_description", nullable = false, length = 2000))
@AttributeOverride(name = "detail", column = @Column(name = "reason", nullable = false, length = 1000))
@AttributeOverride(name = "lastResolvedAt", column = @Column(name = "resolved_at"))
@AttributeOverride(name = "lastResolvedExecutionId", column = @Column(name = "resolved_execution_id"))
@NoArgsConstructor(access = PROTECTED)
public class ExternalDataCollectionFailure extends IngestFailure<ExternalDataCollectionFailure> {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ExternalDataSource source;

    @Column(nullable = false, columnDefinition = "integer default 1")
    private Integer attemptCount;

    @Column(nullable = false, length = 120)
    private String errorType;

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
        super(requestDescription, errorType, reason, occurredAt);
        this.source = source;
        this.attemptCount = attemptCount;
        this.errorType = errorType;
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

    /** 오류 유형이다. DB와 API의 {@code reason}(실패 원인)은 {@link #getDetail()}이다. */
    @Override
    public String getReason() {
        return errorType;
    }

    @Override
    protected void updateObservedDetails(ExternalDataCollectionFailure observed) {
        attemptCount = observed.attemptCount;
        errorType = observed.errorType;
    }

    public void skip(Instant skippedAt, String skipReason, UUID executionId) {
        markSkipped(skippedAt, skipReason, executionId);
        errorType = "NOT_APPLICABLE";
    }

    private static void validateNotBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "은 필수입니다.");
        }
    }

    private static void validateRequired(Object value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + "은 필수입니다.");
        }
    }
}

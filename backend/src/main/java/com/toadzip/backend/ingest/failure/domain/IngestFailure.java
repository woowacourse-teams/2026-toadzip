package com.toadzip.backend.ingest.failure.domain;

import static lombok.AccessLevel.PROTECTED;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 원천별 실패 테이블의 공통 매핑과 발생·해결·재발 상태를 관리한다. */
@Getter
@MappedSuperclass
@Access(AccessType.FIELD)
@NoArgsConstructor(access = PROTECTED)
public abstract class IngestFailure<T extends IngestFailure<T>> {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 500)
    private String sourceKey;

    @Column(nullable = false, length = 1000)
    private String detail;

    @Column(nullable = false)
    private Instant occurredAt;

    @Column(nullable = false)
    private Instant lastOccurredAt;

    @Column(nullable = false)
    private int occurrenceCount;

    @Column(nullable = false)
    private int recurrenceCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IngestFailureStatus status;

    private Instant lastResolvedAt;
    private UUID firstExecutionId;
    private UUID lastExecutionId;
    private UUID lastResolvedExecutionId;

    protected IngestFailure(String sourceKey, Enum<?> reason, String detail, Instant occurredAt) {
        requireText(sourceKey, "원천 키");
        require(reason, "실패 사유");
        requireText(detail, "실패 상세");
        require(occurredAt, "실패 시각");
        this.sourceKey = sourceKey;
        this.detail = detail;
        this.occurredAt = occurredAt;
        lastOccurredAt = occurredAt;
        occurrenceCount = 1;
        status = IngestFailureStatus.PENDING;
    }

    public abstract Enum<?> getReason();

    public void observe(T observed, UUID executionId) {
        require(observed, "관찰한 실패");
        updateObservedDetails(observed);
        detail = observed.getDetail();
        lastOccurredAt = observed.getOccurredAt();
        occurrenceCount++;
        if (status == IngestFailureStatus.RESOLVED) {
            recurrenceCount++;
        }
        status = IngestFailureStatus.PENDING;
        lastExecutionId = executionId;
    }

    protected abstract void updateObservedDetails(T observed);

    public void attachFirstExecution(UUID executionId) {
        firstExecutionId = executionId;
        lastExecutionId = executionId;
    }

    public void resolve(Instant resolvedAt, UUID executionId) {
        require(resolvedAt, "해결 시각");
        if (status == IngestFailureStatus.RESOLVED) {
            return;
        }
        status = IngestFailureStatus.RESOLVED;
        lastResolvedAt = resolvedAt;
        lastResolvedExecutionId = executionId;
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

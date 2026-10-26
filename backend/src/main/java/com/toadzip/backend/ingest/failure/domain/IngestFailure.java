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

    /** 관리자가 공고를 보정해 해결로 바꾼 경우의 관리자. 정제가 해결하면 비운다. */
    private String lastResolvedBy;

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
        lastResolvedBy = null;
    }

    /** 관리자가 공고를 원문 기준으로 보정해 해결로 바꾼다. 정제 실행이 아니므로 실행 ID는 남기지 않는다. */
    public void resolveByAdmin(Instant resolvedAt, String actor) {
        requireText(actor, "해결한 관리자");
        if (status == IngestFailureStatus.RESOLVED) {
            return;
        }
        resolve(resolvedAt, null);
        lastResolvedBy = actor;
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

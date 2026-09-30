package com.toadzip.backend.ingest.mapping.domain;

import static lombok.AccessLevel.PROTECTED;

import com.toadzip.backend.ingest.failure.domain.IngestFailureStatus;
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
@Table(name = "myhome_announcement_mapping_failures")
@NoArgsConstructor(access = PROTECTED)
public class MyHomeAnnouncementMappingFailure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 500)
    private String sourceKey;

    private String sourceAnnouncementIdentifier;

    private Integer sourceHouseSerialNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private MyHomeAnnouncementMappingFailureReason reason;

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

    private MyHomeAnnouncementMappingFailure(
            String sourceKey,
            String sourceAnnouncementIdentifier,
            Integer sourceHouseSerialNumber,
            MyHomeAnnouncementMappingFailureReason reason,
            String detail,
            Instant occurredAt
    ) {
        validateNotBlank(sourceKey, "원천 키");
        validateRequired(reason, "실패 사유");
        validateNotBlank(detail, "실패 상세");
        validateRequired(occurredAt, "실패 시각");
        this.sourceKey = sourceKey;
        this.sourceAnnouncementIdentifier = sourceAnnouncementIdentifier;
        this.sourceHouseSerialNumber = sourceHouseSerialNumber;
        this.reason = reason;
        this.detail = detail;
        this.occurredAt = occurredAt;
        lastOccurredAt = occurredAt;
        occurrenceCount = 1;
        status = IngestFailureStatus.PENDING;
    }

    public void observe(MyHomeAnnouncementMappingFailure observed, UUID executionId) {
        validateRequired(observed, "관찰한 실패");
        sourceAnnouncementIdentifier = observed.sourceAnnouncementIdentifier;
        sourceHouseSerialNumber = observed.sourceHouseSerialNumber;
        detail = observed.detail;
        lastOccurredAt = observed.occurredAt;
        occurrenceCount++;
        if (status == IngestFailureStatus.RESOLVED) {
            recurrenceCount++;
        }
        status = IngestFailureStatus.PENDING;
        lastExecutionId = executionId;
    }

    public void attachFirstExecution(UUID executionId) {
        firstExecutionId = executionId;
        lastExecutionId = executionId;
    }

    public void resolve(Instant resolvedAt, UUID executionId) {
        validateRequired(resolvedAt, "해결 시각");
        if (status == IngestFailureStatus.RESOLVED) {
            return;
        }
        status = IngestFailureStatus.RESOLVED;
        lastResolvedAt = resolvedAt;
        lastResolvedExecutionId = executionId;
    }

    public static MyHomeAnnouncementMappingFailure create(
            String sourceKey,
            String sourceAnnouncementIdentifier,
            Integer sourceHouseSerialNumber,
            MyHomeAnnouncementMappingFailureReason reason,
            String detail,
            Instant occurredAt
    ) {
        return new MyHomeAnnouncementMappingFailure(
                sourceKey,
                sourceAnnouncementIdentifier,
                sourceHouseSerialNumber,
                reason,
                detail,
                occurredAt
        );
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

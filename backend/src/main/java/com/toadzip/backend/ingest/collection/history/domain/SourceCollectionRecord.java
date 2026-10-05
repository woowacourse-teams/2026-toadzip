package com.toadzip.backend.ingest.collection.history.domain;

import static lombok.AccessLevel.PROTECTED;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "source_collection_records")
@NoArgsConstructor(access = PROTECTED)
public class SourceCollectionRecord {

    @Id
    private UUID id;

    // 직접 지정한 UUID라도 새 기록은 INSERT한다. 실패 기록이 기존 성공 기록을 merge하지 않게 한다.
    @Version
    private Long version;

    private UUID executionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private CollectionSource source;

    @ElementCollection
    @CollectionTable(name = "source_collection_record_parameters", joinColumns = @JoinColumn(name = "record_id"))
    @MapKeyColumn(name = "parameter_name", length = 100)
    @Column(name = "parameter_value", nullable = false, length = 500)
    private Map<String, String> requestParameters = new LinkedHashMap<>();

    @Column(nullable = false)
    private Instant startedAt;

    private Instant finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CollectionStatus status;

    @Column(nullable = false)
    private int storedRowCount;

    @Column(length = 120)
    private String errorType;

    @Column(length = 1000)
    private String failureReason;

    private SourceCollectionRecord(
            UUID id, UUID executionId, CollectionSource source, Map<String, String> requestParameters,
            Instant startedAt
    ) {
        this.id = Objects.requireNonNull(id, "수집 기록 ID는 필수입니다.");
        this.executionId = executionId;
        this.source = Objects.requireNonNull(source, "원천 종류는 필수입니다.");
        this.requestParameters.putAll(Map.copyOf(requestParameters));
        this.startedAt = Objects.requireNonNull(startedAt, "요청 시작 시각은 필수입니다.")
                .truncatedTo(ChronoUnit.MICROS);
        status = CollectionStatus.RUNNING;
    }

    public static SourceCollectionRecord start(
            UUID id, UUID executionId, CollectionSource source, Map<String, String> requestParameters,
            Instant startedAt
    ) {
        return new SourceCollectionRecord(id, executionId, source, requestParameters, startedAt);
    }

    public void complete(Instant finishedAt, int storedRowCount) {
        requireRunning();
        Objects.requireNonNull(finishedAt, "요청 종료 시각은 필수입니다.");
        finishedAt = finishedAt.truncatedTo(ChronoUnit.MICROS);
        if (finishedAt.isBefore(startedAt) || storedRowCount < 0) {
            throw new IllegalArgumentException("수집 완료 시각과 저장 행 수가 올바르지 않습니다.");
        }
        this.finishedAt = finishedAt;
        this.storedRowCount = storedRowCount;
        status = CollectionStatus.SUCCESS;
    }

    public void fail(Instant finishedAt, String errorType, String failureReason) {
        requireRunning();
        Objects.requireNonNull(finishedAt, "요청 종료 시각은 필수입니다.");
        finishedAt = finishedAt.truncatedTo(ChronoUnit.MICROS);
        if (finishedAt.isBefore(startedAt) || errorType == null || errorType.isBlank()
                || failureReason == null || failureReason.isBlank()) {
            throw new IllegalArgumentException("실패 종료 시각, 유형과 사유가 올바르지 않습니다.");
        }
        this.finishedAt = finishedAt;
        this.errorType = errorType;
        this.failureReason = failureReason;
        status = CollectionStatus.FAILED;
    }

    public void verifyRequest(
            UUID executionId, CollectionSource source, Map<String, String> parameters, Instant startedAt
    ) {
        if (!Objects.equals(this.executionId, executionId) || this.source != source
                || !requestParameters.equals(parameters)
                || !this.startedAt.equals(startedAt.truncatedTo(ChronoUnit.MICROS))) {
            throw new IllegalArgumentException("수집 기록과 요청 조건이 일치하지 않습니다.");
        }
    }

    private void requireRunning() {
        if (status != CollectionStatus.RUNNING) {
            throw new IllegalStateException("진행 중인 수집 기록만 종료할 수 있습니다.");
        }
    }

    public Map<String, String> getRequestParameters() {
        return Map.copyOf(requestParameters);
    }
}

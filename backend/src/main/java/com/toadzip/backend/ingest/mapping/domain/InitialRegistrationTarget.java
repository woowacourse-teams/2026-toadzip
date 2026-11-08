package com.toadzip.backend.ingest.mapping.domain;

import static lombok.AccessLevel.PROTECTED;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "initial_registration_targets")
@NoArgsConstructor(access = PROTECTED)
public class InitialRegistrationTarget {

    @Id
    @Column(length = 255)
    private String sourceComplexIdentifier;
    @Column(columnDefinition = "text")
    private String name;
    @Column(columnDefinition = "text")
    private String roadAddress;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private InitialRegistrationStatus status;
    @Column(nullable = false, columnDefinition = "text")
    private String detail;
    private Long housingComplexId;
    @Column(nullable = false)
    private Instant checkedAt;
    @Embedded
    private InitialRegistrationLocation location;

    public void prepareLocation(InitialRegistrationLocation location) {
        if (isCompleted()) {
            throw new IllegalStateException("등록 완료 대상은 보완할 수 없습니다.");
        }
        this.location = location;
    }

    private InitialRegistrationTarget(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("원천 식별자는 필수입니다.");
        }
        sourceComplexIdentifier = identifier;
    }

    public static InitialRegistrationTarget create(String identifier) {
        return new InitialRegistrationTarget(identifier);
    }

    public boolean isCompleted() {
        return status == InitialRegistrationStatus.COMPLETED;
    }

    public void complete(long complexId, Instant completedAt) {
        if (status != InitialRegistrationStatus.READY || complexId <= 0) {
            throw new IllegalStateException("등록 가능한 대상만 완료할 수 있습니다.");
        }
        status = InitialRegistrationStatus.COMPLETED;
        housingComplexId = complexId;
        detail = "단지·주택형 등록과 원천 연결이 완료되었습니다.";
        checkedAt = completedAt;
    }

    public void fail(String detail, Instant failedAt) {
        if (status != InitialRegistrationStatus.READY || detail == null || detail.isBlank()) {
            throw new IllegalStateException("등록 가능한 대상의 저장 실패 사유가 필요합니다.");
        }
        status = InitialRegistrationStatus.FAILED;
        this.detail = detail;
        checkedAt = failedAt;
    }

    public void assess(String name, String roadAddress, InitialRegistrationStatus status,
            String detail, Long complexId, Instant checkedAt) {
        if (isCompleted()) {
            return;
        }
        if (status == InitialRegistrationStatus.COMPLETED || status == InitialRegistrationStatus.FAILED) {
            throw new IllegalArgumentException("등록 결과는 준비 상태 확인으로 설정할 수 없습니다.");
        }
        this.name = name;
        this.roadAddress = roadAddress;
        this.checkedAt = checkedAt;
        // 저장 실패는 준비 상태 재확인만으로 성공/등록 가능으로 숨기지 않는다.
        if (this.status == InitialRegistrationStatus.FAILED && status == InitialRegistrationStatus.READY) {
            return;
        }
        this.status = status;
        this.detail = detail;
        this.housingComplexId = complexId;
    }
}

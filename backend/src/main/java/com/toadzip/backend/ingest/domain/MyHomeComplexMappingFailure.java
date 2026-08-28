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
@Table(name = "myhome_complex_mapping_failures")
@NoArgsConstructor(access = PROTECTED)
public class MyHomeComplexMappingFailure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 500)
    private String sourceKey;

    private String sourceComplexIdentifier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private MyHomeComplexMappingFailureReason reason;

    @Column(nullable = false, length = 1000)
    private String detail;

    @Column(nullable = false)
    private Instant occurredAt;

    private MyHomeComplexMappingFailure(
            String sourceKey,
            String sourceComplexIdentifier,
            MyHomeComplexMappingFailureReason reason,
            String detail,
            Instant occurredAt
    ) {
        validateNotBlank(sourceKey, "원천 키");
        validateRequired(reason, "실패 사유");
        validateNotBlank(detail, "실패 상세");
        validateRequired(occurredAt, "실패 시각");
        this.sourceKey = sourceKey;
        this.sourceComplexIdentifier = sourceComplexIdentifier;
        this.reason = reason;
        this.detail = detail;
        this.occurredAt = occurredAt;
    }

    public static MyHomeComplexMappingFailure create(
            String sourceKey,
            String sourceComplexIdentifier,
            MyHomeComplexMappingFailureReason reason,
            String detail,
            Instant occurredAt
    ) {
        return new MyHomeComplexMappingFailure(
                sourceKey,
                sourceComplexIdentifier,
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

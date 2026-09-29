package com.toadzip.backend.ingest.mapping.domain;

import static lombok.AccessLevel.PROTECTED;

import com.toadzip.backend.ingest.failure.domain.IngestFailure;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "myhome_complex_mapping_failures")
@NoArgsConstructor(access = PROTECTED)
public class MyHomeComplexMappingFailure extends IngestFailure<MyHomeComplexMappingFailure> {

    private String sourceComplexIdentifier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private MyHomeComplexMappingFailureReason reason;

    private MyHomeComplexMappingFailure(
            String sourceKey,
            String sourceComplexIdentifier,
            MyHomeComplexMappingFailureReason reason,
            String detail,
            Instant occurredAt
    ) {
        super(sourceKey, reason, detail, occurredAt);
        this.sourceComplexIdentifier = sourceComplexIdentifier;
        this.reason = reason;
    }

    @Override
    protected void updateObservedDetails(MyHomeComplexMappingFailure observed) {
        sourceComplexIdentifier = observed.sourceComplexIdentifier;
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
}

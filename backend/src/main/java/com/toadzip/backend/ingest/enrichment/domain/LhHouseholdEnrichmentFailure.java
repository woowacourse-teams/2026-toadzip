package com.toadzip.backend.ingest.enrichment.domain;

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
@Table(name = "lh_household_enrichment_failures")
@NoArgsConstructor(access = PROTECTED)
public class LhHouseholdEnrichmentFailure extends IngestFailure<LhHouseholdEnrichmentFailure> {

    @Column(length = 200)
    private String areaName;

    @Column(length = 200)
    private String supplyTypeName;

    @Column(length = 500)
    private String complexName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private LhHouseholdEnrichmentFailureReason reason;

    private LhHouseholdEnrichmentFailure(
            String sourceKey,
            String areaName,
            String supplyTypeName,
            String complexName,
            LhHouseholdEnrichmentFailureReason reason,
            String detail,
            Instant occurredAt
    ) {
        super(sourceKey, reason, detail, occurredAt);
        this.areaName = areaName;
        this.supplyTypeName = supplyTypeName;
        this.complexName = complexName;
        this.reason = reason;
    }

    public static LhHouseholdEnrichmentFailure create(
            String sourceKey,
            String areaName,
            String supplyTypeName,
            String complexName,
            LhHouseholdEnrichmentFailureReason reason,
            String detail,
            Instant occurredAt
    ) {
        return new LhHouseholdEnrichmentFailure(
                sourceKey, areaName, supplyTypeName, complexName, reason, detail, occurredAt
        );
    }

    @Override
    protected void updateObservedDetails(LhHouseholdEnrichmentFailure observed) {
        areaName = observed.areaName;
        supplyTypeName = observed.supplyTypeName;
        complexName = observed.complexName;
    }
}

package com.toadzip.backend.ingest.domain;

import static lombok.AccessLevel.PROTECTED;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "lh_catalog_source")
@NoArgsConstructor(access = PROTECTED)
public class LhCatalogSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Integer sourceOrder;
    private Instant collectedAt;
    private String areaName;
    private String supplyTypeName;
    private String complexLabel;
    private String complexTotalUnitCount;
    private String exclusiveArea;
    private String totalUnitCount;
    private String depositText;
    private String monthlyRentText;

    public LhCatalogSource(int sourceOrder, LhCatalogSourceData data) {
        this.sourceOrder = sourceOrder;
        areaName = trim(data.areaName());
        supplyTypeName = trim(data.supplyTypeName());
        complexLabel = trim(data.complexLabel());
        complexTotalUnitCount = trim(data.complexTotalUnitCount());
        exclusiveArea = trim(data.exclusiveArea());
        totalUnitCount = trim(data.totalUnitCount());
        depositText = trim(data.depositText());
        monthlyRentText = trim(data.monthlyRentText());
    }

    public void markCollectedAt(Instant collectedAt) {
        if (collectedAt == null) {
            throw new IllegalArgumentException("수집 시각은 필수입니다.");
        }
        this.collectedAt = collectedAt;
    }

    private static String trim(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }
}

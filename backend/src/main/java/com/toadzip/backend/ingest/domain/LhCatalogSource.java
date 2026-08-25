package com.toadzip.backend.ingest.domain;

import static lombok.AccessLevel.PROTECTED;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.toadzip.backend.ingest.dto.LhCatalogSourceItem;

@Getter
@Entity
@Table(name = "lh_catalog_source")
@NoArgsConstructor(access = PROTECTED)
public class LhCatalogSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Integer sourceOrder;
    private String areaName;
    private String supplyTypeName;
    private String complexLabel;
    private String complexTotalUnitCount;
    private String exclusiveArea;
    private String totalUnitCount;
    private String depositText;
    private String monthlyRentText;

    public LhCatalogSource(int sourceOrder, LhCatalogSourceItem item) {
        this.sourceOrder = sourceOrder;
        areaName = trim(item.areaName());
        supplyTypeName = trim(item.supplyTypeName());
        complexLabel = trim(item.complexLabel());
        complexTotalUnitCount = trim(item.complexTotalUnitCount());
        exclusiveArea = trim(item.exclusiveArea());
        totalUnitCount = trim(item.totalUnitCount());
        depositText = trim(item.depositText());
        monthlyRentText = trim(item.monthlyRentText());
    }

    private static String trim(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }
}

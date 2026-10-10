package com.toadzip.backend.ingest.collection.lh.leasecatalog.domain;

public record LhCatalogSourceSnapshot(
        String areaName,
        String supplyTypeName,
        String complexLabel,
        String complexTotalUnitCount,
        String exclusiveArea,
        String totalUnitCount,
        String depositText,
        String monthlyRentText
) {

    public void validateIdentifiers() {
        if (areaName == null || areaName.isBlank() || supplyTypeName == null || supplyTypeName.isBlank()
                || complexLabel == null || complexLabel.isBlank()) {
            throw new IllegalArgumentException("LH 임대 카탈로그 행의 지역·공급유형·단지명은 필수입니다.");
        }
    }
}

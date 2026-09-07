package com.toadzip.backend.ingest.collection.domain;

public record LhCatalogSourceData(
        String areaName,
        String supplyTypeName,
        String complexLabel,
        String complexTotalUnitCount,
        String exclusiveArea,
        String totalUnitCount,
        String depositText,
        String monthlyRentText
) {
}

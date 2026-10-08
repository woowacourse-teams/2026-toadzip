package com.toadzip.backend.ingest.collection.lh.leasecatalog.domain.projection;

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
}

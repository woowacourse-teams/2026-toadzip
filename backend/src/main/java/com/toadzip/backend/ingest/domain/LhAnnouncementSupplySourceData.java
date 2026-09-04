package com.toadzip.backend.ingest.domain;

public record LhAnnouncementSupplySourceData(
        String complexLabel,
        String typeName,
        String exclusiveArea,
        String supplyArea,
        String totalUnitCount,
        String suppliedUnitCount,
        String depositText,
        String monthlyRentText
) {
}

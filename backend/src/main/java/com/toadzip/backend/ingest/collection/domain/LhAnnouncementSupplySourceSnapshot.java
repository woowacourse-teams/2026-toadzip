package com.toadzip.backend.ingest.collection.domain;

public record LhAnnouncementSupplySourceSnapshot(
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

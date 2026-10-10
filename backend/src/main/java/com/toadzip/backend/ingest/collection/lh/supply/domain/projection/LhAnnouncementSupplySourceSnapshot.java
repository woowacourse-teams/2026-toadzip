package com.toadzip.backend.ingest.collection.lh.supply.domain.projection;

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

package com.toadzip.backend.ingest.collection.lh.supply.domain;

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
    public void validateIdentity() {
        if (complexLabel == null || complexLabel.isBlank() || typeName == null || typeName.isBlank()) {
            throw new IllegalArgumentException("LH 공급행의 단지명 또는 주택형명이 비어 있습니다.");
        }
    }
}

package com.toadzip.backend.ingest.dto;

import tools.jackson.databind.JsonNode;

public record LhNoticeSupplySourceItem(
        String complexLabel,
        String typeName,
        String exclusiveArea,
        String supplyArea,
        String totalUnitCount,
        String suppliedUnitCount,
        String depositText,
        String monthlyRentText
) {

    public static LhNoticeSupplySourceItem from(JsonNode row) {
        return new LhNoticeSupplySourceItem(
                text(row, "SBD_LGO_NM"),
                text(row, "HTY_NNA"),
                text(row, "DDO_AR"),
                text(row, "SPL_AR"),
                text(row, "HSH_CNT"),
                text(row, "NOW_HSH_CNT"),
                text(row, "LS_GMY"),
                text(row, "RFE")
        );
    }

    private static String text(JsonNode row, String field) {
        return row.path(field).asString(null);
    }
}

package com.toadzip.backend.ingest.dto;

import tools.jackson.databind.JsonNode;

public record LhCatalogSourceItem(
        String areaName,
        String supplyTypeName,
        String complexLabel,
        String complexTotalUnitCount,
        String exclusiveArea,
        String totalUnitCount,
        String depositText,
        String monthlyRentText
) {

    public static LhCatalogSourceItem from(JsonNode row) {
        return new LhCatalogSourceItem(
                text(row, "ARA_NM"),
                text(row, "AIS_TP_CD_NM"),
                text(row, "SBD_LGO_NM"),
                text(row, "SUM_HSH_CNT"),
                text(row, "DDO_AR"),
                text(row, "HSH_CNT"),
                text(row, "LS_GMY"),
                text(row, "RFE")
        );
    }

    private static String text(JsonNode row, String field) {
        return row.path(field).asString(null);
    }
}

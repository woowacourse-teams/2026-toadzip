package com.toadzip.backend.ingest.lh;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** LH 임대주택 단지별 조건(15059475)의 dsList 한 행이다. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LhLeaseInfoItem(
		@JsonProperty("ARA_NM") String areaName,
		@JsonProperty("AIS_TP_CD_NM") String supplyTypeName,
		@JsonProperty("SBD_LGO_NM") String complexLabel,
		@JsonProperty("SUM_HSH_CNT") String complexTotalUnitCount,
		@JsonProperty("DDO_AR") String exclusiveArea,
		@JsonProperty("HSH_CNT") String totalUnitCount,
		@JsonProperty("LS_GMY") String deposit,
		@JsonProperty("RFE") String monthlyRent) {
}

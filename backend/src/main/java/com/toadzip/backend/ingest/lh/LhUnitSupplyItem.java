package com.toadzip.backend.ingest.lh;

import tools.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LhUnitSupplyItem(@JsonProperty("SBD_LGO_NM") String complexLabel,
		@JsonProperty("HTY_NNA") String typeName, @JsonProperty("DDO_AR") String exclusiveArea,
		@JsonProperty("SPL_AR") String supplyArea, @JsonProperty("HSH_CNT") String totalUnitCount,
		@JsonProperty("NOW_HSH_CNT") String suppliedUnitCount, @JsonProperty("LS_GMY") String deposit,
		@JsonProperty("RFE") String monthlyRent) {
}

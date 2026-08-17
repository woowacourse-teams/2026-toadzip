package com.toadzip.backend.ingest.lh;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class LhNoticeDetail {

	private LhNoticeDetail() {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record EtcInfo(@JsonProperty("CRC_RSN") String correctionReason,
			@JsonProperty("ETC_CTS") String etcContents) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ComplexDetail(@JsonProperty("LCC_NT_NM") String complexName,
			@JsonProperty("LGDN_ADR") String lotAddress, @JsonProperty("LGDN_DTL_ADR") String lotDetailAddress,
			@JsonProperty("HSH_CNT") String totalUnitCount, @JsonProperty("HTN_FMLA_DESC") String heatingDescription,
			@JsonProperty("DDO_AR") String exclusiveAreaRange, @JsonProperty("MVIN_XPC_YM") String expectedMoveInYearMonth,
			@JsonProperty("SPL_INF_GUD_FCTS") String guidanceText) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Schedule(@JsonProperty("SBD_LGO_NM") String complexName,
			@JsonProperty("ACP_DTTM") String applicationPeriod,
			@JsonProperty("PPR_SBM_OPE_ANC_DT") String documentTargetAnnouncementDate,
			@JsonProperty("PPR_ACP_ST_DT") String documentSubmissionBeginDate,
			@JsonProperty("PPR_ACP_CLSG_DT") String documentSubmissionEndDate,
			@JsonProperty("CTRT_ST_DT") String contractBeginDate,
			@JsonProperty("CTRT_ED_DT") String contractEndDate) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Reception(@JsonProperty("CTRT_PLC_ADR") String address,
			@JsonProperty("CTRT_PLC_DTL_ADR") String detailAddress, @JsonProperty("TSK_ST_DTTM") String operationBegin,
			@JsonProperty("TSK_ED_DTTM") String operationEnd, @JsonProperty("SIL_OFC_TLNO") String phone,
			@JsonProperty("SIL_OFC_GUD_FCTS") String guidance) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record NoticeFile(@JsonProperty("SL_PAN_AHFL_DS_CD_NM") String kind,
			@JsonProperty("CMN_AHFL_NM") String name, @JsonProperty("AHFL_URL") String url) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ComplexImage(@JsonProperty("LS_SPL_INF_UPL_FL_DS_CD_NM") String kind,
			@JsonProperty("CMN_AHFL_NM") String name, @JsonProperty("AHFL_URL") String url,
			@JsonProperty("LCC_NT_NM") String complexName) {
	}
}

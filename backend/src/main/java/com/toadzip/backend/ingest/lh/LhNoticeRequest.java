package com.toadzip.backend.ingest.lh;

import java.net.URI;
import java.util.Optional;

import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

import com.toadzip.backend.ingest.SourceValues;

public record LhNoticeRequest(String panId, String connectionSystemDivisionCode,
		String upperAnnouncementTypeCode, String announcementTypeCode, String supplyInfoTypeCode) {

	public static Optional<LhNoticeRequest> from(URI detailUrl, String supplyInfoTypeCode) {
		MultiValueMap<String, String> query = UriComponentsBuilder.fromUri(detailUrl).build().getQueryParams();
		String panId = SourceValues.trimToNull(query.getFirst("panId"));
		String connection = SourceValues.trimToNull(query.getFirst("ccrCnntSysDsCd"));
		String upperType = SourceValues.trimToNull(query.getFirst("uppAisTpCd"));
		String code = SourceValues.trimToNull(supplyInfoTypeCode);
		if (panId == null || connection == null || upperType == null || code == null) {
			return Optional.empty();
		}
		return Optional.of(new LhNoticeRequest(panId, connection, upperType,
				SourceValues.trimToNull(query.getFirst("aisTpCd")), code));
	}

	public MultiValueMap<String, String> toParams() {
		MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
		params.add("PAN_ID", panId);
		params.add("CCR_CNNT_SYS_DS_CD", connectionSystemDivisionCode);
		params.add("UPP_AIS_TP_CD", upperAnnouncementTypeCode);
		if (announcementTypeCode != null) {
			params.add("AIS_TP_CD", announcementTypeCode);
		}
		params.add("SPL_INF_TP_CD", supplyInfoTypeCode);
		params.add("PG_SZ", "100");
		params.add("PAGE", "1");
		return params;
	}
}

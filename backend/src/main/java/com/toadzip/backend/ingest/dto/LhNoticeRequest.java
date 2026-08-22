package com.toadzip.backend.ingest.dto;

import java.net.URI;
import java.util.Optional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

public record LhNoticeRequest(
        String panId,
        String connectionSystemDivisionCode,
        String upperAnnouncementTypeCode,
        String announcementTypeCode,
        String supplyInfoTypeCode
) {

    public static Optional<LhNoticeRequest> from(URI detailUrl, String supplyInfoTypeCode) {
        MultiValueMap<String, String> query = UriComponentsBuilder.fromUri(detailUrl)
                .build()
                .getQueryParams();
        String panId = query.getFirst("panId");
        String connection = query.getFirst("ccrCnntSysDsCd");
        String upperType = query.getFirst("uppAisTpCd");
        String announcementType = query.getFirst("aisTpCd");
        if (isBlank(panId) || isBlank(connection) || isBlank(upperType) || isBlank(supplyInfoTypeCode)) {
            return Optional.empty();
        }
        return Optional.of(new LhNoticeRequest(
                panId,
                connection,
                upperType,
                blankToNull(announcementType),
                supplyInfoTypeCode
        ));
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

    public String requestDescription() {
        return "PAN_ID=" + panId
                + "&CCR_CNNT_SYS_DS_CD=" + connectionSystemDivisionCode
                + "&UPP_AIS_TP_CD=" + upperAnnouncementTypeCode
                + "&SPL_INF_TP_CD=" + supplyInfoTypeCode;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value) {
        if (isBlank(value)) {
            return null;
        }
        return value;
    }
}

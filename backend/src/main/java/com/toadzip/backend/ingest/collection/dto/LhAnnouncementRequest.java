package com.toadzip.backend.ingest.collection.dto;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

public record LhAnnouncementRequest(
        String panId,
        String connectionSystemDivisionCode,
        String upperAnnouncementTypeCode,
        String announcementTypeCode,
        String supplyInfoTypeCode,
        int pageSize,
        int page
) {

    private static final int DEFAULT_PAGE_SIZE = 100;

    public LhAnnouncementRequest(
            String panId,
            String connectionSystemDivisionCode,
            String upperAnnouncementTypeCode,
            String announcementTypeCode,
            String supplyInfoTypeCode
    ) {
        this(
                panId,
                connectionSystemDivisionCode,
                upperAnnouncementTypeCode,
                announcementTypeCode,
                supplyInfoTypeCode,
                DEFAULT_PAGE_SIZE,
                1
        );
    }

    public LhAnnouncementRequest {
        if (pageSize < 1 || page < 1) {
            throw new IllegalArgumentException("페이지 크기와 페이지 번호는 1 이상이어야 합니다.");
        }
    }

    public static Optional<LhAnnouncementRequest> from(URI detailUrl, String supplyInfoTypeCode) {
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
        return Optional.of(new LhAnnouncementRequest(
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
        params.add("PG_SZ", Integer.toString(pageSize));
        params.add("PAGE", Integer.toString(page));
        return params;
    }

    public LhAnnouncementRequest withPage(int page) {
        return new LhAnnouncementRequest(
                panId,
                connectionSystemDivisionCode,
                upperAnnouncementTypeCode,
                announcementTypeCode,
                supplyInfoTypeCode,
                pageSize,
                page
        );
    }

    public String requestDescription() {
        String description = legacyRequestDescription();
        if (announcementTypeCode == null) {
            return description;
        }
        return description + "&AIS_TP_CD=" + announcementTypeCode;
    }

    public List<String> compatibleRequestDescriptions() {
        String currentDescription = requestDescription();
        String legacyDescription = legacyRequestDescription();
        if (currentDescription.equals(legacyDescription)) {
            return List.of(currentDescription);
        }
        return List.of(currentDescription, legacyDescription);
    }

    public String pageRequestDescription() {
        return requestDescription() + "&PG_SZ=" + pageSize + "&PAGE=" + page;
    }

    private String legacyRequestDescription() {
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

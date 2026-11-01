package com.toadzip.backend.ingest.collection.lh.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

public record LhAnnouncementQuery(
        String panId, String connectionSystemDivisionCode, String upperAnnouncementTypeCode,
        String announcementTypeCode, String supplyInfoTypeCode
) {

    private static final String COLLECTION_VERSION = "COLLECTION_VERSION";

    public LhAnnouncementQuery {
        if (panId == null || panId.isBlank() || connectionSystemDivisionCode == null
                || connectionSystemDivisionCode.isBlank() || upperAnnouncementTypeCode == null
                || upperAnnouncementTypeCode.isBlank() || supplyInfoTypeCode == null || supplyInfoTypeCode.isBlank()) {
            throw new IllegalArgumentException("LH 공고 조회의 필수 조건이 비어 있습니다.");
        }
        if (announcementTypeCode != null && announcementTypeCode.isBlank()) {
            announcementTypeCode = null;
        }
    }

    public Map<String, String> parameters() {
        var parameters = new LinkedHashMap<String, String>();
        parameters.put("PAN_ID", panId);
        parameters.put("CCR_CNNT_SYS_DS_CD", connectionSystemDivisionCode);
        parameters.put("UPP_AIS_TP_CD", upperAnnouncementTypeCode);
        parameters.put("SPL_INF_TP_CD", supplyInfoTypeCode);
        if (announcementTypeCode != null) {
            parameters.put("AIS_TP_CD", announcementTypeCode);
        }
        return Map.copyOf(parameters);
    }

    public String description() {
        String description = "PAN_ID=" + panId + "&CCR_CNNT_SYS_DS_CD=" + connectionSystemDivisionCode
                + "&UPP_AIS_TP_CD=" + upperAnnouncementTypeCode + "&SPL_INF_TP_CD=" + supplyInfoTypeCode;
        if (announcementTypeCode != null) {
            return description + "&AIS_TP_CD=" + announcementTypeCode;
        }
        return description;
    }

    /** 수집 버전까지 붙인 조회 조건. 수집 기록이 이 파라미터로 같은 요청인지 확인한다. */
    public Map<String, String> parameters(int collectionVersion) {
        var parameters = new LinkedHashMap<>(parameters());
        parameters.put(COLLECTION_VERSION, Integer.toString(collectionVersion));
        return Map.copyOf(parameters);
    }

    /** 수집 버전까지 붙인 요청 설명. 수집 기록과 진행 상태가 이 문자열로 같은 요청을 찾는다. */
    public String description(int collectionVersion) {
        return description() + "&" + COLLECTION_VERSION + "=" + collectionVersion;
    }

    public String identityHash() {
        return requestHashOf(description());
    }

    public static String requestHashOf(String description) {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("조회 조건은 필수입니다.");
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(description.strip().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("조회 조건 hash 알고리즘을 사용할 수 없습니다.", exception);
        }
    }

}

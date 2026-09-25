package com.toadzip.backend.ingest.collection.repository.external;

import com.toadzip.backend.ingest.collection.dto.LhAnnouncementRequest;
import java.util.List;
import tools.jackson.databind.JsonNode;

public class LhAnnouncementResponseIdentityValidator {

    public void validate(LhAnnouncementRequest request, JsonNode root) {
        List<JsonNode> conditions = ExternalResponseRows.find(root, "dsSch");
        if (conditions.size() != 1) {
            throw new ExternalDataRequestException("LH 공고 응답에 조회 조건이 없습니다.");
        }
        JsonNode condition = conditions.getFirst();
        requireSame(condition, "PAN_ID", request.panId());
        requireSame(condition, "CCR_CNNT_SYS_DS_CD", request.connectionSystemDivisionCode());
        requireSame(condition, "UPP_AIS_TP_CD", request.upperAnnouncementTypeCode());
        requireSame(condition, "SPL_INF_TP_CD", request.supplyInfoTypeCode());
        if (request.announcementTypeCode() != null) {
            requireSame(condition, "AIS_TP_CD", request.announcementTypeCode());
        }
    }

    private void requireSame(JsonNode condition, String field, String requested) {
        if (!requested.equals(condition.path(field).asString(null))) {
            throw new ExternalDataRequestException("LH 공고 응답의 조회 조건이 요청과 다릅니다: " + field);
        }
    }
}

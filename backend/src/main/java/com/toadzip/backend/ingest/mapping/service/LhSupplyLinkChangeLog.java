package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.admin.domain.AdminDataChange;
import com.toadzip.backend.admin.repository.AdminDataChangeRepository;
import com.toadzip.backend.ingest.mapping.dto.AnnouncementSupplyMatching.SavedMatch;
import com.toadzip.backend.ingest.mapping.dto.LhSupplyLinking.SavedLink;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * LH 공급 연결 변경을 연결 단위 관리자 변경 이력으로 남긴다. 연결 실패로 정제가 롤백되면 공고가 아직 없을 수 있어 공고가 아니라
 * 연결 ID를 자원으로 쓴다. 공고 단위 공급 매칭 이력은 {@link SupplyMatchingChangeLog}가 남긴다.
 */
@Component
@RequiredArgsConstructor
class LhSupplyLinkChangeLog {

    private static final String RESOURCE = "LH_SUPPLY_LINK";
    private static final String MATCH_RELEASE_REASON = "LH 공급 연결로 마이홈 원천행의 LH 행 구성이 바뀌어 원천 지문이 달라짐";

    private final AdminDataChangeRepository changes;
    private final ObjectMapper json;

    void linkSaved(long linkId, String identifier, String lhRowIdentifier, SavedLink before, SavedLink saved,
            String actor) {
        changes.save(new AdminDataChange(RESOURCE, linkId, "SAVE_LH_SUPPLY_LINK", actor,
                json.writeValueAsString(before), json.writeValueAsString(Map.of("announcementIdentifier", identifier,
                        "lhRowIdentifier", lhRowIdentifier, "link", saved))));
    }

    void linkReleased(long linkId, String identifier, String lhRowIdentifier, SavedLink released, String reason,
            String actor) {
        changes.save(new AdminDataChange(RESOURCE, linkId, "RELEASE_LH_SUPPLY_LINK", actor,
                json.writeValueAsString(Map.of("announcementIdentifier", identifier,
                        "lhRowIdentifier", lhRowIdentifier, "link", released)),
                json.writeValueAsString(Map.of("announcementIdentifier", identifier,
                        "lhRowIdentifier", lhRowIdentifier, "reason", reason))));
    }

    /** LH 연결 변경으로 원천 지문이 달라져 놓은 저장 매칭이다. */
    void matchReleased(long linkId, String identifier, String rowIdentifier, SavedMatch decision, String actor) {
        var previous = new LinkedHashMap<String, Object>();
        previous.put("rowIdentifier", rowIdentifier);
        previous.put("complexId", decision.complexId());
        previous.put("housingTypeId", decision.housingTypeId());
        previous.put("sourceUrl", decision.sourceUrl());
        previous.put("reason", decision.reason());
        previous.put("actor", decision.actor());
        changes.save(new AdminDataChange(RESOURCE, linkId, "RELEASE_SUPPLY_MATCH", actor,
                json.writeValueAsString(previous), json.writeValueAsString(Map.of(
                        "announcementIdentifier", identifier, "rowIdentifier", rowIdentifier,
                        "reason", MATCH_RELEASE_REASON))));
    }
}

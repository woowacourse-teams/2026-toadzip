package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.admin.domain.AdminDataChange;
import com.toadzip.backend.admin.repository.AdminDataChangeRepository;
import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.ingest.mapping.dto.AnnouncementSupplyMatching.Row;
import com.toadzip.backend.ingest.mapping.dto.AnnouncementSupplyMatching.SavedMatch;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 공급 매칭 보정을 공고의 관리자 변경 이력으로 남긴다. 공고 단위 이력의 동작 이름과 JSON 모양을 여기서만 정한다.
 * LH 공급 연결 때문에 놓은 저장 매칭은 연결 단위로 {@link LhSupplyLinkChangeLog}가 남긴다.
 */
@Component
@RequiredArgsConstructor
class SupplyMatchingChangeLog {
    private static final String TYPE = "ANNOUNCEMENT";

    private final AdminDataChangeRepository changes;
    private final ObjectMapper json;
    private final AnnouncementSupplyRowQueryService rowQuery;
    private final SupplyRowRepository productRows;

    void savedMatch(Announcement announcement, String rowIdentifier, SavedMatch before, SavedMatch saved,
            String actor) {
        changes.save(new AdminDataChange(TYPE, announcement.getId(), "SAVE_SUPPLY_MATCH", actor,
                json.writeValueAsString(before),
                json.writeValueAsString(Map.of("rowIdentifier", rowIdentifier, "decision", saved))));
    }

    /** 분할 전 화면 상태. 분할을 쓰기 전에 남겨야 이력에 바뀌기 전 값이 남는다. */
    String beforeSplit(String identifier) {
        return json.writeValueAsString(rowQuery.rows(identifier));
    }

    /** 쓴 분할을 반영한 화면 상태를 이력에 남기고 돌려준다. */
    List<Row> split(Announcement announcement, String identifier, String before, String actor) {
        productRows.flush();
        var after = rowQuery.rows(identifier);
        changes.save(new AdminDataChange(TYPE, announcement.getId(), "SPLIT_SUPPLY", actor,
                before, json.writeValueAsString(after)));
        return after;
    }

    void released(Announcement announcement, String rowIdentifier, SavedMatch released, String reason,
            String actor) {
        changes.save(new AdminDataChange(TYPE, announcement.getId(), "RELEASE_SUPPLY_MATCH", actor,
                json.writeValueAsString(released),
                json.writeValueAsString(Map.of("rowIdentifier", rowIdentifier, "reason", reason))));
    }

    void exclusion(Announcement announcement, String rowIdentifier, boolean before, boolean excluded, String reason,
            String actor) {
        changes.save(new AdminDataChange(TYPE, announcement.getId(),
                excluded ? "EXCLUDE_SUPPLY_ROW" : "INCLUDE_SUPPLY_ROW", actor,
                json.writeValueAsString(Map.of("rowIdentifier", rowIdentifier, "excluded", before)),
                json.writeValueAsString(Map.of("rowIdentifier", rowIdentifier,
                        "excluded", excluded, "reason", reason))));
    }
}

package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.admin.domain.AdminDataChange;
import com.toadzip.backend.admin.repository.AdminDataChangeRepository;
import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.ingest.mapping.dto.AnnouncementSupplyMatching.Row;
import com.toadzip.backend.ingest.mapping.dto.AnnouncementSupplyMatching.SavedMatch;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 공급 매칭 보정을 공고의 관리자 변경 이력으로 남긴다. 이력 화면이 읽는 동작 이름과 JSON 모양을 여기서만 정한다. */
@Component
@RequiredArgsConstructor
class SupplyMatchingChangeLog {
    private static final String TYPE = "ANNOUNCEMENT";

    private final AdminDataChangeRepository changes;
    private final ObjectMapper json;

    void savedMatch(Announcement announcement, String rowIdentifier, SavedMatch before, SavedMatch saved,
            String actor) {
        changes.save(new AdminDataChange(TYPE, announcement.getId(), "SAVE_SUPPLY_MATCH", actor,
                json.writeValueAsString(before),
                json.writeValueAsString(Map.of("rowIdentifier", rowIdentifier, "decision", saved))));
    }

    /** 분할 전 화면 상태. 분할을 쓰기 전에 남겨야 이력에 바뀌기 전 값이 남는다. */
    String snapshot(List<Row> rows) {
        return json.writeValueAsString(rows);
    }

    void split(Announcement announcement, String before, List<Row> after, String actor) {
        changes.save(new AdminDataChange(TYPE, announcement.getId(), "SPLIT_SUPPLY", actor,
                before, json.writeValueAsString(after)));
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

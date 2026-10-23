package com.toadzip.backend.announcement.service;

import com.toadzip.backend.admin.domain.AdminDataChange;
import com.toadzip.backend.admin.exception.AdminDataConflictException;
import com.toadzip.backend.admin.repository.AdminDataChangeRepository;
import com.toadzip.backend.announcement.domain.SupplyReviewRecord;
import com.toadzip.backend.announcement.dto.correction.SupplyReview;
import com.toadzip.backend.announcement.exception.AnnouncementNotFoundException;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.SupplyReviewQueryRepository;
import com.toadzip.backend.announcement.repository.SupplyReviewRecordRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class SupplyReviewService {
    private static final Set<String> METADATA = Set.of("id", "source_id", "collected_at", "source_order",
            "collection_record_id", "consecutive_miss_count", "last_seen_run_id", "last_seen_at");
    private final SupplyCorrectionService corrections;
    private final AnnouncementRepository announcements;
    private final SupplyReviewRecordRepository records;
    private final SupplyReviewQueryRepository queries;
    private final AdminDataChangeRepository changes;
    private final ObjectMapper json;

    public SupplyReview.Overview overview(long id) {
        if (!announcements.existsById(id)) { throw new AnnouncementNotFoundException(); }
        return new SupplyReview.Overview(queries.values(id), queries.records(id));
    }

    @Transactional
    public SupplyReview.Overview record(long id, SupplyReview.Request request, String actor) {
        announcements.findByIdForUpdate(id).orElseThrow(AnnouncementNotFoundException::new);
        var workspace = corrections.workspace(id);
        if (!workspace.snapshotToken().equals(request.snapshotToken())) {
            throw new AdminDataConflictException("검토 중 등록값 또는 원천이 바뀌었습니다. 최신값을 확인한 뒤 기록하세요.");
        }
        var current = queries.values(id).get(request.target());
        if (current == null) { throw new IllegalArgumentException("이 공고에 속한 검토 대상을 선택하세요."); }
        var checked = json.createObjectNode();
        for (var field : request.fields()) {
            if (!current.has(field)) { throw new IllegalArgumentException("검토할 수 없는 항목입니다: " + field); }
            checked.set(field, current.get(field));
        }
        List<Map<String, Object>> evidence = new ArrayList<>();
        for (var scope : request.sources()) {
            var source = workspace.sources().stream().filter(item -> item.category() == scope.category()
                    && item.sourceKey().equals(scope.sourceKey())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("현재 비교 자료에 있는 원천을 선택하세요."));
            if (source.rows().isEmpty()) { throw new AdminDataConflictException("확인할 원천이 사라졌습니다."); }
            var fields = scope.fields().stream().distinct().sorted().toList();
            if (fields.stream().anyMatch(field -> METADATA.contains(field)
                    || source.rows().stream().noneMatch(item -> item.raw().containsKey(field)))) {
                throw new IllegalArgumentException("수집 메타정보를 제외한 실제 원천 항목을 선택하세요.");
            }
            var projected = source.rows().stream().map(item -> {
                var values = new LinkedHashMap<String, Object>();
                fields.stream().filter(item.raw()::containsKey).forEach(field -> values.put(field, item.raw().get(field)));
                return values;
            }).sorted(Comparator.comparing(json::writeValueAsString)).toList();
            evidence.add(Map.of("category", scope.category().name(), "sourceKey", scope.sourceKey(),
                    "fields", fields, "rows", projected));
        }
        var saved = records.saveAndFlush(new SupplyReviewRecord(id, request.target(), request.outcome(),
                json.writeValueAsString(checked), queries.canonicalEvidence(json.writeValueAsString(evidence)), request.note().strip(),
                request.sourceUrl(), request.sourcePage(), actor));
        changes.save(new AdminDataChange("ANNOUNCEMENT", id, "REVIEW_SUPPLY", actor, "{}",
                json.writeValueAsString(Map.of("reviewId", saved.getId(), "review", request,
                        "checkedValues", checked, "evidence", evidence))));
        return overview(id);
    }
}

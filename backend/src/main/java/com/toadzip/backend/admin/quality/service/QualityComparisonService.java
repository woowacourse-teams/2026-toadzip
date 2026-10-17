package com.toadzip.backend.admin.quality.service;

import com.toadzip.backend.admin.domain.AdminDataChange;
import com.toadzip.backend.admin.exception.AdminDataConflictException;
import com.toadzip.backend.admin.quality.domain.QualityResource;
import com.toadzip.backend.admin.quality.dto.QualityComparison;
import com.toadzip.backend.admin.quality.dto.QualityComparison.Source;
import com.toadzip.backend.admin.quality.dto.QualityComparisonRequest;
import com.toadzip.backend.admin.quality.repository.QualityComparisonRepository;
import com.toadzip.backend.admin.repository.AdminDataChangeRepository;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.housing.service.ComplexComparisonService;
import com.toadzip.backend.ingest.source.dto.IngestSourceCategory;
import com.toadzip.backend.ingest.source.repository.IngestSourceQueryRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class QualityComparisonService {
    private final QualityTargetService targets;
    private final QualityComparisonRepository comparisons;
    private final IngestSourceQueryRepository sources;
    private final HousingComplexRepository complexes;
    private final AnnouncementRepository announcements;
    private final ComplexComparisonService complexComparison;
    private final AdminDataChangeRepository changes;
    private final ObjectMapper json;

    public QualityComparisonService(QualityTargetService targets, QualityComparisonRepository comparisons,
            IngestSourceQueryRepository sources, HousingComplexRepository complexes,
            AnnouncementRepository announcements, ComplexComparisonService complexComparison,
            AdminDataChangeRepository changes, ObjectMapper json) {
        this.targets = targets;
        this.comparisons = comparisons;
        this.sources = sources;
        this.complexes = complexes;
        this.announcements = announcements;
        this.complexComparison = complexComparison;
        this.changes = changes;
        this.json = json;
    }

    public QualityComparison detail(QualityResource resource, long id, String target) {
        var detail = targets.detail(resource, id);
        targets.target(detail, target);
        List<Source> evidence = new ArrayList<>(automatic(resource, id));
        comparisons.links(resource, id, target).forEach(link -> evidence.add(new Source(link.category(),
                link.sourceKey(), false, link.evidenceNote(),
                sources.findAllByKey(link.category(), link.sourceKey()))));
        return new QualityComparison(token(detail, evidence), target, detail, List.copyOf(evidence));
    }

    @Transactional
    public QualityComparison update(QualityResource resource, long id, QualityComparisonRequest.Update request,
            String actor) {
        lock(resource, id);
        var before = checked(resource, id, request.target(), request.snapshotToken());
        var target = targets.target(before.detail(), request.target());
        if (request.values().isEmpty()) {
            throw new IllegalArgumentException("수정할 항목을 선택해 주세요.");
        }
        targets.update(resource, id, request.target(), target, request.values(), actor);
        var after = detail(resource, id, request.target());
        var current = new LinkedHashMap<>(targets.target(after.detail(), request.target()).current());
        current.put("comparisonEvidence", Map.of("note", request.evidenceNote(), "target", request.target(),
                "sources", before.sources()));
        record(resource, id, "UPDATE_COMPARED", actor, target.current(), current);
        return after;
    }

    @Transactional
    public QualityComparison link(QualityResource resource, long id, QualityComparisonRequest.Link request,
            String actor, boolean remove) {
        lock(resource, id);
        var before = checked(resource, id, request.target(), request.snapshotToken());
        allowed(resource, request.category());
        if (remove) {
            if (comparisons.unlink(resource, id, request.target(), request.category(), request.sourceKey()) != 1) {
                throw new AdminDataConflictException("직접 연결한 원천만 해제할 수 있습니다.");
            }
        }
        if (!remove) {
            if (comparisons.links(resource, id, request.target()).size() >= 20
                    || before.sources().stream().anyMatch(source ->
                    source.category() == request.category() && source.sourceKey().equals(request.sourceKey()))) {
                throw new AdminDataConflictException("이미 연결되었거나 연결 한도(20개)에 도달했습니다.");
            }
            if (sources.findAllByKey(request.category(), request.sourceKey()).isEmpty()) {
                throw new AdminDataConflictException("선택한 원천이 사라졌습니다. 다시 검색해 주세요.");
            }
            comparisons.link(resource, id, request.target(), request.category(), request.sourceKey(),
                    request.evidenceNote(), actor);
        }
        var after = detail(resource, id, request.target());
        String action = "LINK_COMPARISON_SOURCE";
        if (remove) {
            action = "UNLINK_COMPARISON_SOURCE";
        }
        record(resource, id, action, actor, before.sources(),
                Map.of("target", request.target(), "sources", after.sources(), "note", request.evidenceNote()));
        return after;
    }

    @Transactional
    public QualityComparison checkComplex(long id, QualityComparisonRequest.Check request, String actor) {
        lock(QualityResource.COMPLEX, id);
        if (!request.target().equals("basic")
                || !complexComparison.detail(id).snapshotToken().equals(request.snapshotToken())) {
            throw new AdminDataConflictException("단지 등록값 또는 원천이 변경되었습니다. 다시 비교해 주세요.");
        }
        var current = detail(QualityResource.COMPLEX, id, "basic");
        return check(QualityResource.COMPLEX, id, new QualityComparisonRequest.Check("basic",
                current.snapshotToken(), request.evidenceNote()), actor);
    }

    @Transactional
    public QualityComparison check(QualityResource resource, long id, QualityComparisonRequest.Check request,
            String actor) {
        lock(resource, id);
        var before = checked(resource, id, request.target(), request.snapshotToken());
        if (before.sources().isEmpty() || before.sources().stream().anyMatch(source -> source.rows().isEmpty())) {
            throw new IllegalArgumentException("확인할 DB 원천을 연결해 주세요.");
        }
        var evidence = before.sources().stream().map(source -> Map.of("category", source.category().name(),
                "sourceKey", source.sourceKey(), "rows", json.readTree(
                        comparisons.fingerprint(source.category(), source.sourceKey())))).toList();
        comparisons.check(resource, id, request.target(), json.writeValueAsString(evidence),
                json.writeValueAsString(targets.target(before.detail(), request.target()).current()),
                request.evidenceNote(), actor);
        record(resource, id, "CHECK_COMPARISON_SOURCES", actor, Map.of(),
                Map.of("target", request.target(), "comparisonEvidence", Map.of("note", request.evidenceNote(),
                        "sources", before.sources())));
        return before;
    }

    private List<Source> automatic(QualityResource resource, long id) {
        if (resource == QualityResource.COMPLEX) {
            return complexComparison.detail(id).sources().stream().filter(source -> source.data() != null)
                    .map(source -> new Source(IngestSourceCategory.valueOf(source.category().name()),
                            source.sourceKey(), true, "단지에 연결된 원천입니다. 주택형·적용 범위를 확인하세요.",
                            List.of(source.data()))).distinct().toList();
        }
        var announcement = announcements.findById(id).orElseThrow();
        String panId = "";
        if (announcement.getLhPanId() != null) {
            panId = announcement.getLhPanId();
        }
        List<Source> result = new ArrayList<>();
        for (var category : List.of(IngestSourceCategory.MYHOME_ANNOUNCEMENT,
                IngestSourceCategory.LH_ANNOUNCEMENT_CATALOG, IngestSourceCategory.LH_ANNOUNCEMENT_DETAIL,
                IngestSourceCategory.LH_ANNOUNCEMENT_SUPPLY, IngestSourceCategory.SH_ANNOUNCEMENT)) {
            for (var key : comparisons.automaticAnnouncementKeys(category,
                    announcement.getSourceAnnouncementIdentifier(), panId)) {
                result.add(new Source(category, key, true, "공고 식별자로 연결된 원천",
                        sources.findAllByKey(category, key)));
            }
        }
        return result;
    }

    private void allowed(QualityResource resource, IngestSourceCategory category) {
        boolean complexCategory = category == IngestSourceCategory.MYHOME_COMPLEX
                || category == IngestSourceCategory.LH_LEASE_CATALOG;
        if ((resource == QualityResource.COMPLEX) != complexCategory) {
            throw new IllegalArgumentException("대상에 맞는 원천 분류를 선택해 주세요.");
        }
    }

    private QualityComparison checked(QualityResource resource, long id, String target, String expected) {
        var current = detail(resource, id, target);
        if (!current.snapshotToken().equals(expected)) {
            throw new AdminDataConflictException("등록값 또는 DB 원천이 변경되었습니다. 입력을 보관하고 다시 조회하세요.");
        }
        return current;
    }

    private void lock(QualityResource resource, long id) {
        if (resource == QualityResource.COMPLEX) {
            complexes.findByIdForUpdate(id).orElseThrow(
                    com.toadzip.backend.housing.exception.AdminHousingComplexNotFoundException::new);
            return;
        }
        announcements.findByIdForUpdate(id).orElseThrow(
                com.toadzip.backend.announcement.exception.AnnouncementNotFoundException::new);
    }

    private String token(Object detail, List<Source> evidence) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsString(List.of(detail, evidence)).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private void record(QualityResource resource, long id, String action, String actor, Object before, Object after) {
        changes.save(new AdminDataChange(resource.name(), id, action, actor,
                json.writeValueAsString(before), json.writeValueAsString(after)));
    }
}

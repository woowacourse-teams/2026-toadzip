package com.toadzip.backend.admin.quality.service;

import com.toadzip.backend.admin.domain.AdminDataChange;
import com.toadzip.backend.admin.exception.AdminDataConflictException;
import com.toadzip.backend.admin.quality.domain.QualityResource;
import com.toadzip.backend.admin.quality.dto.SaveReviewDraft;
import com.toadzip.backend.admin.quality.dto.SharedReviewDraft;
import com.toadzip.backend.admin.quality.repository.ReviewDocumentRepository;
import com.toadzip.backend.admin.quality.repository.SharedReviewDraftRepository;
import com.toadzip.backend.admin.repository.AdminDataChangeRepository;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(readOnly = true)
public class SharedReviewDraftService {
    private final SharedReviewDraftRepository drafts;
    private final ReviewDocumentRepository documents;
    private final QualityTargetService targets;
    private final AdminDataChangeRepository changes;
    private final ObjectMapper json;

    public SharedReviewDraftService(SharedReviewDraftRepository drafts, ReviewDocumentRepository documents,
            QualityTargetService targets, AdminDataChangeRepository changes, ObjectMapper json) {
        this.drafts = drafts;
        this.documents = documents;
        this.targets = targets;
        this.changes = changes;
        this.json = json;
    }

    public SharedReviewDraft find(QualityResource resource, long id, String target) {
        targets.target(targets.detail(resource, id), target);
        return drafts.find(resource, id, target).orElse(null);
    }

    @Transactional
    public SharedReviewDraft save(QualityResource resource, long id, SaveReviewDraft request, String actor) {
        targets.target(targets.detail(resource, id), request.target());
        validate(resource, id, request);
        if (!documents.exists(resource, id, request.sha256())) {
            throw new IllegalArgumentException("같은 대상의 PDF 원본을 먼저 서버에 보관해 주세요.");
        }
        var before = drafts.find(resource, id, request.target());
        var saved = drafts.save(resource, id, request, actor).orElseThrow(() ->
                new AdminDataConflictException("다른 관리자가 공유 초안을 변경했습니다. 현재 입력을 보관하고 최신 초안을 확인하세요."));
        changes.save(new AdminDataChange(resource.name(), id, "SAVE_REVIEW_DRAFT", actor,
                json.writeValueAsString(before.orElse(null)), json.writeValueAsString(saved)));
        return saved;
    }

    private void validate(QualityResource resource, long id, SaveReviewDraft request) {
        URI url = URI.create(request.sourceUrl());
        if (!("https".equalsIgnoreCase(url.getScheme()) || "http".equalsIgnoreCase(url.getScheme()))
                || url.getHost() == null || url.getUserInfo() != null
                || request.resultJson().getBytes(StandardCharsets.UTF_8).length > 1000000) {
            throw new IllegalArgumentException("공식 출처 URL과 수정안 크기를 확인해 주세요.");
        }
        if (request.resultJson().isBlank()) {
            return;
        }
        JsonNode result;
        try {
            result = json.readTree(request.resultJson());
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("수정안 JSON 문법을 확인해 주세요.");
        }
        String kind = "announcements";
        if (resource == QualityResource.COMPLEX) {
            kind = "complexes";
        }
        if (result == null || !result.isObject()
                || !result.path("schemaVersion").asText().equals("document-review/v1")
                || !result.path("targetKey").asText().equals(kind + ":" + id + ":" + request.target())
                || !result.path("sha256").asText().equals(request.sha256())
                || !result.path("version").isIntegralNumber()
                || result.path("version").asLong() != request.documentVersion()
                || !result.path("proposals").isArray() || result.path("proposals").size() > 100
                || !result.path("unresolved").isArray() || result.path("unresolved").size() > 100) {
            throw new IllegalArgumentException("수정안의 대상·버전·원본 해시와 배열을 확인해 주세요.");
        }
    }
}

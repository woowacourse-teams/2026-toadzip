package com.toadzip.backend.announcement.dto.correction;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.toadzip.backend.announcement.domain.StoredCorrectionDraft;
import com.toadzip.backend.announcement.dto.request.RejectUnknownJsonFields;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import tools.jackson.databind.node.ObjectNode;

/** 관리자별·공고별 PDF 보정 초안의 요청과 응답. payload는 화면의 작업 상태이며 서버는 해석하지 않는다. */
public final class CorrectionDraft {
    private CorrectionDraft() { }

    /**
     * @param expectedRevision 화면이 마지막으로 본 revision. 첫 저장이면 null이다.
     * @param baseSnapshotToken 작업을 시작할 때 본 PDF 보정 작업 공간(json-corrections)의 snapshotToken
     * @param pdfSha256 고른 공고문 PDF의 SHA-256(소문자 16진수). 아직 고르지 않았으면 null이다.
     * @param extractionId 작업에 쓴 PDF 추출 작업 ID. 없으면 null이다.
     * @param payload 작업 상태 JSON 객체. 미완성·잘못된 값도 그대로 저장한다.
     */
    public record SaveRequest(@Positive Long expectedRevision,
            @NotBlank @Size(max = 64) String baseSnapshotToken,
            @Pattern(regexp = "[a-f0-9]{64}") String pdfSha256,
            @Positive Long extractionId,
            @NotNull ObjectNode payload) implements RejectUnknownJsonFields { }

    /** stale은 초안의 baseSnapshotToken이 지금 공고의 작업 공간 토큰과 다를 때 true다. */
    public record View(@JsonRawValue String payload, long revision, String baseSnapshotToken, String pdfSha256,
            Long extractionId, Instant updatedAt, boolean stale) {
        public static View of(StoredCorrectionDraft draft, String currentSnapshotToken) {
            var content = draft.content();
            return new View(content.payload(), draft.revision(), content.baseSnapshotToken(), content.pdfSha256(),
                    content.extractionId(), draft.updatedAt(), !content.baseSnapshotToken().equals(currentSnapshotToken));
        }
    }

    public record Saved(long revision, Instant updatedAt) {
        public static Saved of(StoredCorrectionDraft draft) {
            return new Saved(draft.revision(), draft.updatedAt());
        }
    }
}

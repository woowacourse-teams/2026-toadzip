package com.toadzip.backend.feedback.dto;

import com.toadzip.backend.feedback.domain.Feedback;
import com.toadzip.backend.feedback.domain.FeedbackStatus;
import com.toadzip.backend.feedback.repository.FeedbackTargetRepository.Target;
import java.time.Instant;

/**
 * relatedId는 지금 그 대상을 가리키는 ID(병합된 단지는 대표 단지)이고 relatedName은 현재 이름이다.
 * 대상 행을 찾지 못하면 저장된 ID를 그대로 두고 이름은 null이다.
 */
public record FeedbackSummary(long id, String content, Instant createdAt, long version, FeedbackStatus status,
        String note, String relatedResource, Long relatedId, String relatedName, String actor, Instant processedAt) {
    public static FeedbackSummary from(Feedback feedback, Target target) {
        return new FeedbackSummary(feedback.getId(), feedback.getContent(), feedback.getCreatedAt(),
                feedback.getVersion(), feedback.getStatus(), feedback.getNote(), feedback.getRelatedResource(),
                target == null ? feedback.getRelatedId() : Long.valueOf(target.id()),
                target == null ? null : target.name(),
                feedback.getActor(), feedback.getProcessedAt());
    }
}

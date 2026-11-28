package com.toadzip.backend.feedback.service;

import com.toadzip.backend.feedback.domain.Feedback;
import com.toadzip.backend.feedback.exception.FeedbackTargetNotFoundException;
import com.toadzip.backend.feedback.repository.FeedbackRepository;
import com.toadzip.backend.feedback.repository.FeedbackTargetRepository;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class FeedbackSubmissionService {

    private final FeedbackRepository repository;
    private final FeedbackTargetRepository targets;
    private final Clock clock;

    public FeedbackSubmissionService(FeedbackRepository repository, FeedbackTargetRepository targets, Clock clock) {
        this.repository = repository;
        this.targets = targets;
        this.clock = clock;
    }

    /**
     * relatedResource·relatedId는 둘 다 없거나 둘 다 있다. 있으면 그 단지·공고가 있어야 하며,
     * 보던 사이 병합된 단지는 대표 단지로 바꿔 저장한다.
     */
    public long submit(String content, String relatedResource, Long relatedId) {
        Long targetId = relatedId == null ? null
                : targets.resolve(relatedResource, relatedId).orElseThrow(FeedbackTargetNotFoundException::new);
        Feedback feedback = Feedback.submit(content, relatedResource == null ? "" : relatedResource, targetId,
                clock.instant());
        return repository.save(feedback).getId();
    }
}

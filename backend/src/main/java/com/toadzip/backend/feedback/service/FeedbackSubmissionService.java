package com.toadzip.backend.feedback.service;

import com.toadzip.backend.feedback.domain.Feedback;
import com.toadzip.backend.feedback.repository.FeedbackRepository;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class FeedbackSubmissionService {

    private final FeedbackRepository repository;
    private final Clock clock;

    public FeedbackSubmissionService(FeedbackRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public long submit(String content) {
        Feedback feedback = Feedback.submit(content, clock.instant());
        return repository.save(feedback).getId();
    }
}

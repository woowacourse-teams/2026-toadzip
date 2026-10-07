package com.toadzip.backend.feedback.repository;

import com.toadzip.backend.feedback.domain.Feedback;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeedbackRepository extends JpaRepository<Feedback, Long> {
}

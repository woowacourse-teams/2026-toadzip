package com.toadzip.backend.feedback.repository;

import com.toadzip.backend.feedback.domain.Feedback;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FeedbackRepository extends JpaRepository<Feedback, Long> {

    @Query("SELECT feedback FROM Feedback feedback WHERE LOWER(feedback.content.value) LIKE :pattern ESCAPE '!'")
    Page<Feedback> search(@Param("pattern") String pattern, Pageable pageable);
}

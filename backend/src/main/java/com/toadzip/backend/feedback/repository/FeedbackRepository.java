package com.toadzip.backend.feedback.repository;

import com.toadzip.backend.feedback.domain.Feedback;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FeedbackRepository extends JpaRepository<Feedback, Long> {

    @Query("SELECT feedback FROM Feedback feedback WHERE LOWER(feedback.content.value) LIKE :pattern ESCAPE '!'")
    List<Feedback> search(@Param("pattern") String pattern, Pageable pageable);

    @Query("""
            SELECT COUNT(feedback) FROM Feedback feedback
            WHERE LOWER(feedback.content.value) LIKE :pattern ESCAPE '!'
            """)
    long countMatching(@Param("pattern") String pattern);
}

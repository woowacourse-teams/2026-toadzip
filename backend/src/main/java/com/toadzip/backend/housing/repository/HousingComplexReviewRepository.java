package com.toadzip.backend.housing.repository;

import com.toadzip.backend.housing.domain.HousingComplexReview;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HousingComplexReviewRepository extends JpaRepository<HousingComplexReview, Long> {
    List<HousingComplexReview> findByHousingComplexIdOrderByIdDesc(long complexId, Pageable pageable);

    @Query(value = "SELECT CAST(" + ComplexVerificationSql.CURRENT_VALUES
            + " AS text) FROM housing_complexes c WHERE c.id = :id", nativeQuery = true)
    String currentValues(@Param("id") long id);

    @Query(value = "SELECT c.id AS id, " + ComplexVerificationSql.STATUS + " AS status, "
            + "(SELECT count(*) FROM jsonb_object_keys(COALESCE(verification_review.checked_values, '{}')))"
            + " AS fieldCount FROM housing_complexes c " + ComplexVerificationSql.LATEST_REVIEW
            + " WHERE c.id IN (:ids)", nativeQuery = true)
    List<ReviewStatus> currentStatuses(@Param("ids") Collection<Long> ids);

    interface ReviewStatus {
        long getId();
        String getStatus();
        int getFieldCount();
    }
}

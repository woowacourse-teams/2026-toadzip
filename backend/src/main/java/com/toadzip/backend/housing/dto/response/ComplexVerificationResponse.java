package com.toadzip.backend.housing.dto.response;

import com.toadzip.backend.housing.domain.ComplexReviewOutcome;
import com.toadzip.backend.housing.domain.ComplexVerificationField;
import com.toadzip.backend.housing.domain.ComplexVerificationStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ComplexVerificationResponse(long version, String snapshotToken, ComplexVerificationStatus status,
        Map<String, Object> currentValues, List<Source> sources, Review latestReview, List<Review> history) {

    public record Source(String sourceIdentifier, String name, String roadAddress, String pnu, String provider,
            String rentalType, Integer householdCount, String housingType, BigDecimal exclusiveArea,
            Instant collectedAt) { }

    public record Review(long id, ComplexReviewOutcome outcome, List<ComplexVerificationField> fields,
            Map<String, Object> checkedValues, String evidenceUrl, String evidenceNote, String actor,
            Instant reviewedAt) { }
}

package com.toadzip.backend.eligibility.dto;

import com.toadzip.backend.eligibility.domain.EligibilityContent;
import com.toadzip.backend.eligibility.domain.EligibilityDecision;
import java.util.List;
import java.time.Instant;

public record EligibilityDetail(
        long requestedAnnouncementId, long announcementId, String availability,
        Long ruleSetId, long profileVersion, EligibilityDecision result, EligibilityContent content,
        String selectedRegion, List<String> eligibleOptionIdsInRegion,
        String applicationStatus, String freshness, List<String> staleReasons,
        Instant evaluatedAt, Long evaluatedProfileVersion) {}

package com.toadzip.backend.basiccomparison.dto;

import com.toadzip.backend.basiccomparison.domain.ComparisonContent;
import com.toadzip.backend.basiccomparison.domain.EligibilityDecision;
import java.util.List;

public record EligibilityDetail(
        long requestedAnnouncementId, long announcementId, String availability,
        Long ruleSetId, long profileVersion, EligibilityDecision result, ComparisonContent content,
        String selectedRegion, List<String> eligibleOptionIdsInRegion) {}

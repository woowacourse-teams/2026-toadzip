package com.toadzip.backend.basiccomparison.domain;

import java.util.Set;

public record EligibilityBasis(
        long userId, long rootAnnouncementId, long announcementId, long profileVersion, long noticeGeneration,
        Long ruleSetId, String availability, String applicationStatus, String evaluatorVersion) {
    public boolean active() {
        return "AVAILABLE".equals(availability)
                && Set.of("APPLYING", "BEFORE_APPLICATION", "CONDITIONAL").contains(applicationStatus);
    }

    public String noticeKey() {
        return announcementId + ":" + ruleSetId + ":" + availability + ":" + applicationStatus + ":" + evaluatorVersion;
    }
}

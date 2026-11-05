package com.toadzip.backend.eligibility.dto;

import com.toadzip.backend.eligibility.domain.EligibilityCatalog;
import com.toadzip.backend.eligibility.domain.EligibilityProfile;
import com.toadzip.backend.eligibility.domain.EligibilityResult;
import com.toadzip.backend.eligibility.domain.InputFact;
import com.toadzip.backend.eligibility.domain.InputValue;
import com.toadzip.backend.eligibility.domain.RuleSetContent;
import com.toadzip.backend.eligibility.domain.StoredRuleSet;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class EligibilityResponses {
    private EligibilityResponses() {}

    public record Profile(long version, Instant updatedAt, Map<InputFact, InputValue> facts) {
        public static Profile of(EligibilityProfile profile) {
            return new Profile(profile.version(), profile.updatedAt(), profile.facts());
        }
    }

    public record RuleSet(
            long id,
            long announcementId,
            String status,
            long version,
            long revision,
            Instant updatedAt,
            Instant publishedAt,
            RuleSetContent content) {
        public static RuleSet of(StoredRuleSet stored, RuleSetContent content) {
            return new RuleSet(
                    stored.id(),
                    stored.announcementId(),
                    stored.status(),
                    stored.version(),
                    stored.revision(),
                    stored.updatedAt(),
                    stored.publishedAt(),
                    content);
        }
    }

    public record Detail(
            long requestedAnnouncementId,
            long announcementId,
            String availability,
            Long ruleSetId,
            Long ruleSetVersion,
            long profileVersion,
            com.toadzip.backend.eligibility.domain.RuleSetMetadata source,
            EligibilityResult result) {}

    public record Summary(
            Instant evaluatedAt,
            long profileVersion,
            int qualifiedAnnouncementCount,
            int additionalInputAnnouncementCount,
            int evaluatedAnnouncementCount,
            Map<InputFact, Integer> singleInputCounts,
            String policy,
            String evaluatorVersion) {}

    public record Catalog(
            List<EligibilityCatalog.Fact> facts,
            List<EligibilityCatalog.InputGap> inputGaps,
            List<InputFact> acceptsNotApplicable) {}
}

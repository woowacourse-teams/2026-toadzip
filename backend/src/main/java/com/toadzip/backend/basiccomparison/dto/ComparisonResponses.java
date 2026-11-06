package com.toadzip.backend.basiccomparison.dto;

import com.toadzip.backend.basiccomparison.domain.ComparisonContent;
import com.toadzip.backend.basiccomparison.domain.ComparisonProfile;
import com.toadzip.backend.basiccomparison.domain.ComparisonResult;
import com.toadzip.backend.basiccomparison.domain.FamilyDetailsState;
import com.toadzip.backend.basiccomparison.domain.ProfileField;
import com.toadzip.backend.basiccomparison.domain.StoredComparison;
import com.toadzip.backend.basiccomparison.domain.StoredComparisonProfile;
import com.toadzip.backend.basiccomparison.domain.ValueState;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ComparisonResponses {
    private ComparisonResponses() {}

    public record Profile(
            long version, int inputContractVersion, Instant updatedAt, ComparisonProfile profile,
            String reevaluationState) {
        public static Profile of(StoredComparisonProfile stored, String reevaluationState) {
            return new Profile(
                    stored.version(),
                    ComparisonProfile.CONTRACT_VERSION,
                    stored.updatedAt(),
                    stored.profile(), reevaluationState);
        }
    }

    public record InputField(
            ProfileField code,
            ProfileField.Type type,
            String label,
            String unit,
            ProfileField.Flow flow,
            boolean optional,
            List<String> choices,
            String note) {}

    public record Catalog(
            int inputContractVersion,
            List<InputField> fields,
            List<ValueState> valueStates,
            List<FamilyDetailsState> familyDetailsStates,
            Map<ProfileField.Flow, String> flowNotes,
            String scopeNote) {}

    public record Detail(
            long requestedAnnouncementId,
            long announcementId,
            String availability,
            Long ruleSetId,
            Long ruleSetVersion,
            long profileVersion,
            ComparisonResult result) {}

    public record Summary(
            Instant computedAt,
            long profileVersion,
            int generalBasicMatchedCount,
            int generalInputNeededCount,
            int requiredQualificationNoticeCount,
            int comparedAnnouncementCount,
            int notStructuredCount,
            String scope,
            String comparisonVersion,
            String scopeNote) {}

    public record RuleSet(
            long id,
            long announcementId,
            String status,
            long revision,
            long version,
            int schemaVersion,
            Instant updatedAt,
            Instant publishedAt,
            ComparisonContent content) {
        public static RuleSet of(StoredComparison stored, ComparisonContent content) {
            return new RuleSet(
                    stored.id(),
                    stored.announcementId(),
                    stored.status(),
                    stored.revision(),
                    stored.version(),
                    stored.schemaVersion(),
                    stored.updatedAt(),
                    stored.publishedAt(),
                    content);
        }
    }
}

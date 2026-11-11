package com.toadzip.backend.eligibility.dto;

import com.toadzip.backend.eligibility.domain.EligibilityContent;
import com.toadzip.backend.eligibility.domain.EligibilityProfile;
import com.toadzip.backend.eligibility.domain.BasicComparisonResult;
import com.toadzip.backend.eligibility.domain.FamilyDetailsState;
import com.toadzip.backend.eligibility.domain.ProfileField;
import com.toadzip.backend.eligibility.domain.StoredEligibilityRuleSet;
import com.toadzip.backend.eligibility.domain.StoredEligibilityProfile;
import com.toadzip.backend.eligibility.domain.ValueState;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class EligibilityResponses {
    private EligibilityResponses() {}

    public record Profile(
            long version, int inputContractVersion, Instant updatedAt, EligibilityProfile profile,
            String reevaluationState) {
        public static Profile of(StoredEligibilityProfile stored, String reevaluationState) {
            return new Profile(
                    stored.version(),
                    EligibilityProfile.CONTRACT_VERSION,
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

    public record BasicComparisonDetail(
            long requestedAnnouncementId,
            long announcementId,
            String availability,
            Long ruleSetId,
            Long ruleSetVersion,
            long profileVersion,
            BasicComparisonResult result) {}

    public record BasicComparisonSummary(
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

    /** AI 추출로 만든 초안과 관리자가 검토할 때 알아야 할 안내(공개 내용에는 넣지 않음). */
    public record ExtractionDraft(RuleSet ruleSet, List<String> notices) {}

    public record RuleSet(
            long id,
            long announcementId,
            String status,
            long revision,
            long version,
            int schemaVersion,
            Instant updatedAt,
            Instant publishedAt,
            EligibilityContent content) {
        public static RuleSet of(StoredEligibilityRuleSet stored, EligibilityContent content) {
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

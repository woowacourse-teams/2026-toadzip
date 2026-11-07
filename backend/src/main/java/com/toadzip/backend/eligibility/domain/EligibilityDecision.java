package com.toadzip.backend.eligibility.domain;

import com.toadzip.backend.eligibility.domain.EligibilityContent.RecruitmentStatus;
import com.toadzip.backend.eligibility.domain.BasicComparisonResult.CheckResult;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** Compact personal readings. Shared notice/source data remains in the authored content. */
public record EligibilityDecision(
        Status status, String headline, LocalDate referenceDate, String evaluatorVersion,
        List<CheckResult> commonChecks, List<PathDecision> paths, List<OptionDecision> options) {
    public EligibilityDecision {
        commonChecks = List.copyOf(commonChecks);
        paths = List.copyOf(paths);
        options = List.copyOf(options);
    }

    public enum Status {
        ELIGIBLE, INELIGIBLE, UNDETERMINED, NOT_EVALUATED
    }

    public record PathDecision(String pathId, List<CheckResult> checks) {
        public PathDecision {
            checks = List.copyOf(checks);
        }
    }

    public record OptionDecision(
            String optionId, String supplyUnitId, String unitLabel, List<String> regionCodes,
            String pathId, String pathLabel, RecruitmentStatus recruitmentStatus, Status status,
            String message, String missingInputMessage,
            List<ProfileField> missingInputs, List<String> requiredQualificationIds,
            List<String> directCheckGuidanceIds, List<String> exclusionGuidanceIds) {
        public OptionDecision {
            regionCodes = List.copyOf(regionCodes);
            missingInputs = List.copyOf(missingInputs);
            requiredQualificationIds = List.copyOf(requiredQualificationIds);
            directCheckGuidanceIds = List.copyOf(directCheckGuidanceIds);
            exclusionGuidanceIds = List.copyOf(exclusionGuidanceIds);
        }
    }

    public List<String> eligibleOptionIds() {
        return options.stream().filter(option -> option.status() == Status.ELIGIBLE)
                .map(OptionDecision::optionId).toList();
    }

    /** Region matching is scoped to a real eligible choice, not to any unit in the notice. */
    public List<String> eligibleOptionIdsIn(String regionCode, RegionMembership regions) {
        return options.stream().filter(option -> option.status() == Status.ELIGIBLE)
                .filter(option -> option.regionCodes().stream().anyMatch(code ->
                        regions.match(Set.of(regionCode), code) == RegionMembership.Match.MATCH))
                .map(OptionDecision::optionId).toList();
    }
}

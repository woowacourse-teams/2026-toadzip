package com.toadzip.backend.eligibility.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Calculated response data. None of these records is persisted. */
public record EligibilityResult(
        LocalDate referenceDate,
        Summary summary,
        String structuringStatus,
        List<OptionResult> options,
        List<ConditionResult> conditions,
        List<RuleSetData.Document> documents,
        List<RuleSetData.Evidence> evidences,
        List<RuleSetData.RentOffer> rentOffers,
        List<RuleSetData.RentConversion> rentConversions,
        List<RuleSetData.ScheduleEvent> schedules,
        List<RuleSetData.AnnouncementRule> rules,
        Supply supply,
        List<QuotaResult> quotas,
        String evaluatorVersion,
        List<String> limitations) {
    public enum Outcome {
        ELIGIBLE,
        ELIGIBLE_WITH_OBLIGATIONS,
        UNDETERMINED,
        NOT_ELIGIBLE,
        NOT_RECRUITING,
        NOT_FOR_INDIVIDUAL
    }

    public enum Summary {
        HAS_ELIGIBLE,
        HAS_CONDITIONAL,
        HAS_CONFIRMATION_ONLY,
        UNDETERMINED_ONLY,
        NONE_ELIGIBLE,
        OUT_OF_SCOPE;

        public boolean countsAsQualified() {
            return this == HAS_ELIGIBLE || this == HAS_CONDITIONAL || this == HAS_CONFIRMATION_ONLY;
        }
    }

    public record OptionResult(
            long applicationOptionId,
            long supplyUnitId,
            long eligibilityPathId,
            String unitLabel,
            String pathLabel,
            String recruitmentStatus,
            Outcome outcome,
            String undeterminedKind,
            Set<Long> blockingConditionIds,
            Set<UndeterminedReason> blockingReasons,
            Set<InputFact> missingFactCodes,
            Set<Long> obligationConditionIds,
            String message,
            Long rankTierMin,
            Long rankTierMax,
            BigDecimal scoreMin,
            BigDecimal scoreMax,
            List<Long> rentOfferIds,
            List<RuleSetData.SelectionStep> selectionSteps,
            List<SelectionResult> selectionResults) {}

    public record ConditionResult(
            long conditionId,
            ConditionOutcome outcome,
            UndeterminedReason undeterminedReason,
            Set<UndeterminedReason> reasons,
            Set<InputFact> missingFactCodes,
            Map<String, Object> shownValues,
            String statementAsWritten,
            String subjectCode,
            String conditionRole,
            long evidenceId,
            String inputGapCode,
            boolean confirmation,
            String semanticCaveat,
            String holdingPeriod,
            String referenceDateRule) {}

    public record Supply(
            List<RuleSetData.Complex> complexes,
            List<RuleSetData.HousingType> housingTypes,
            List<RuleSetData.SupplyUnit> supplyUnits,
            List<RuleSetData.SupplyHousingType> housingTypeLinks,
            List<RuleSetData.QuotaOption> quotaOptions,
            List<RuleSetData.QuotaAllocationRule> quotaAllocationRules) {}

    public record QuotaResult(
            RuleSetData.QuotaPool pool,
            ConditionOutcome entryOutcome,
            Set<UndeterminedReason> reasons,
            Set<InputFact> missingFactCodes) {}

    public record SelectionResult(
            long stepId,
            Long rankTierMin,
            Long rankTierMax,
            BigDecimal scoreMin,
            BigDecimal scoreMax,
            ConditionOutcome outcome,
            Set<UndeterminedReason> reasons,
            Set<InputFact> missingFactCodes) {}

    public boolean needsAdditionalInput() {
        return summary == Summary.UNDETERMINED_ONLY
                && options.stream().anyMatch(this::inputResolvable);
    }

    public boolean inputResolvable(OptionResult option) {
        if (option.outcome() != Outcome.UNDETERMINED
                || option.blockingConditionIds().isEmpty()
                || !"RECRUITING".equals(option.recruitmentStatus())
                || option.missingFactCodes().isEmpty()) {
            return false;
        }
        Map<Long, ConditionResult> byId =
                conditions.stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        ConditionResult::conditionId,
                                        java.util.function.Function.identity()));
        return option.blockingConditionIds().stream()
                .allMatch(
                        id -> {
                            ConditionResult condition = byId.get(id);
                            return condition != null
                                    && (condition.confirmation()
                                            || condition
                                                    .reasons()
                                                    .equals(
                                                            Set.of(
                                                                    UndeterminedReason
                                                                            .INPUT_MISSING)));
                        });
    }
}

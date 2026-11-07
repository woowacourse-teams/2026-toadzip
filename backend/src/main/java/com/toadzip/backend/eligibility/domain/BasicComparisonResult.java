package com.toadzip.backend.eligibility.domain;

import java.time.LocalDate;
import java.util.List;

/** Only current calculated values. A basic match never represents final application eligibility. */
public record BasicComparisonResult(
        Scope scope,
        LocalDate referenceDate,
        String headline,
        List<OptionResult> options,
        List<EligibilityContent.Guidance> directChecks,
        List<EligibilityContent.SourceDocument> documents,
        List<EligibilityContent.Evidence> evidences,
        List<EligibilityContent.SupplyUnit> supplyUnits,
        List<EligibilityContent.Quantity> quantities,
        List<EligibilityContent.RentOffer> rentOffers,
        List<EligibilityContent.Schedule> schedules,
        String scopeNote,
        String comparisonVersion) {
    public enum Scope {
        INDIVIDUAL,
        OUT_OF_SCOPE
    }

    public enum BasicStatus {
        MATCHED,
        NOT_MATCHED,
        INPUT_NEEDED,
        PARTIALLY_COMPARED,
        NOT_EVALUATED
    }

    public record MissingInput(ProfileField field, boolean optional, boolean skipped) {}

    public record CheckResult(
            String id,
            String label,
            RuleReading reading,
            List<String> evidenceIds,
            String directCheckId) {}

    public record OptionResult(
            String id,
            String unitLabel,
            String pathLabel,
            EligibilityContent.PathKind coverage,
            EligibilityContent.RecruitmentStatus recruitmentStatus,
            BasicStatus status,
            String message,
            List<CheckResult> checks,
            List<MissingInput> missingInputs,
            List<String> directCheckIds) {}

    public boolean generalBasicMatched() {
        return options.stream()
                .anyMatch(
                        option ->
                                option.coverage() == EligibilityContent.PathKind.GENERAL
                                        && option.recruitmentStatus()
                                                == EligibilityContent.RecruitmentStatus.RECRUITING
                                        && option.status() == BasicStatus.MATCHED);
    }

    public boolean generalInputNeeded() {
        return !generalBasicMatched()
                && options.stream()
                        .anyMatch(
                                option ->
                                        option.coverage() == EligibilityContent.PathKind.GENERAL
                                                && option.recruitmentStatus()
                                                        == EligibilityContent.RecruitmentStatus
                                                                .RECRUITING
                                                && option.status() == BasicStatus.INPUT_NEEDED);
    }

    public boolean requiresSeparateQualification() {
        return directChecks.stream()
                .anyMatch(
                        guidance ->
                                guidance.kind()
                                        == EligibilityContent.GuidanceKind.REQUIRED_QUALIFICATION);
    }
}

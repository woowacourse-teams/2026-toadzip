package com.toadzip.backend.basiccomparison.domain;

import java.time.LocalDate;
import java.util.List;

/** Only current calculated values. A basic match never represents final application eligibility. */
public record ComparisonResult(
        Scope scope,
        LocalDate referenceDate,
        String headline,
        List<OptionResult> options,
        List<ComparisonContent.Guidance> directChecks,
        List<ComparisonContent.SourceDocument> documents,
        List<ComparisonContent.Evidence> evidences,
        List<ComparisonContent.SupplyUnit> supplyUnits,
        List<ComparisonContent.Quantity> quantities,
        List<ComparisonContent.RentOffer> rentOffers,
        List<ComparisonContent.Schedule> schedules,
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
            ComparisonContent.PathKind coverage,
            ComparisonContent.RecruitmentStatus recruitmentStatus,
            BasicStatus status,
            String message,
            List<CheckResult> checks,
            List<MissingInput> missingInputs,
            List<String> directCheckIds) {}

    public boolean generalBasicMatched() {
        return options.stream()
                .anyMatch(
                        option ->
                                option.coverage() == ComparisonContent.PathKind.GENERAL
                                        && option.recruitmentStatus()
                                                == ComparisonContent.RecruitmentStatus.RECRUITING
                                        && option.status() == BasicStatus.MATCHED);
    }

    public boolean generalInputNeeded() {
        return !generalBasicMatched()
                && options.stream()
                        .anyMatch(
                                option ->
                                        option.coverage() == ComparisonContent.PathKind.GENERAL
                                                && option.recruitmentStatus()
                                                        == ComparisonContent.RecruitmentStatus
                                                                .RECRUITING
                                                && option.status() == BasicStatus.INPUT_NEEDED);
    }

    public boolean requiresSeparateQualification() {
        return directChecks.stream()
                .anyMatch(
                        guidance ->
                                guidance.kind()
                                        == ComparisonContent.GuidanceKind.REQUIRED_QUALIFICATION);
    }
}

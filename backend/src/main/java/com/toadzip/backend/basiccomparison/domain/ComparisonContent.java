package com.toadzip.backend.basiccomparison.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** One authored aggregate: basic comparisons, manual guidance and factual notice information. */
public record ComparisonContent(
        Metadata metadata,
        List<SourceDocument> documents,
        List<Evidence> evidences,
        List<SupplyUnit> supplyUnits,
        List<ComparisonPath> paths,
        List<ComparisonOption> options,
        List<Guidance> guidances,
        List<Quantity> quantities,
        List<RentOffer> rentOffers,
        List<Schedule> schedules) {
    public static final int SCHEMA_VERSION = 2;

    public ComparisonContent {
        documents = immutable(documents);
        evidences = immutable(evidences);
        supplyUnits = immutable(supplyUnits);
        paths = immutable(paths);
        options = immutable(options);
        guidances = immutable(guidances);
        quantities = immutable(quantities);
        rentOffers = immutable(rentOffers);
        schedules = immutable(schedules);
    }

    public static <T> List<T> immutable(List<T> values) {
        if (values == null) {
            return List.of();
        }
        return List.copyOf(values);
    }

    public enum ApplicantKind {
        INDIVIDUAL,
        ORGANIZATION
    }

    public enum AccessMode {
        GENERAL_ROUTE_AVAILABLE,
        REQUIRED_QUALIFICATION
    }

    public enum ReviewStatus {
        REVIEWING,
        REVIEWED
    }

    public enum PathKind {
        GENERAL,
        COMMON_ONLY
    }

    public enum RecruitmentStatus {
        RECRUITING,
        NOT_RECRUITING,
        UNKNOWN
    }

    public enum GuidanceKind {
        REQUIRED_QUALIFICATION,
        REQUIRED_CHECK,
        OTHER_ROUTE,
        PRIORITY_OPPORTUNITY,
        PROCEDURE
    }

    public enum TargetScope {
        ANNOUNCEMENT,
        UNIT,
        PATH,
        OPTION
    }

    public enum QuantityKind {
        CONSTRUCTED,
        VACANT,
        RECRUITED,
        WAITLIST_RECRUITED,
        EXISTING_WAITLIST,
        PERCENT
    }

    public record Metadata(
            LocalDate referenceDate,
            ApplicantKind applicantKind,
            AccessMode accessMode,
            ReviewStatus reviewStatus,
            String classificationEvidenceId,
            String scopeNote) {}

    public record SourceDocument(
            String id, String fileName, String downloadUrl, int pageCount, String sha256) {}

    public record Evidence(
            String id,
            String documentId,
            int firstPage,
            int lastPage,
            String location,
            String description) {}

    public record PhysicalHousing(
            String id,
            String complexLabel,
            String typeLabel,
            String rentalType,
            BigDecimal exclusiveArea,
            Long housingComplexId,
            Long housingTypeId) {}

    public record SupplyUnit(String id, String label, List<PhysicalHousing> housingTypes) {
        public SupplyUnit {
            housingTypes = immutable(housingTypes);
        }
    }

    public record ComparisonPath(String id, String label, PathKind kind, List<BasicCheck> checks) {
        public ComparisonPath {
            checks = immutable(checks);
        }
    }

    public record ComparisonOption(
            String id, String supplyUnitId, String pathId, RecruitmentStatus recruitmentStatus) {}

    public record BasicCheck(
            String id,
            String label,
            BasicRule rule,
            BasicRule appliesWhen,
            String fallbackGuidanceId,
            List<String> evidenceIds) {
        public BasicCheck {
            evidenceIds = immutable(evidenceIds);
        }
    }

    public record Guidance(
            String id,
            GuidanceKind kind,
            String title,
            String body,
            List<String> items,
            List<GuidanceTarget> targets,
            List<String> evidenceIds) {
        public Guidance {
            items = immutable(items);
            targets = immutable(targets);
            evidenceIds = immutable(evidenceIds);
        }

        public boolean appliesTo(ComparisonOption option) {
            return targets.stream().anyMatch(target -> target.appliesTo(option));
        }
    }

    public record GuidanceTarget(TargetScope scope, String pathId, String supplyUnitId) {
        public boolean appliesTo(ComparisonOption option) {
            return switch (scope) {
                case ANNOUNCEMENT -> true;
                case PATH -> pathId.equals(option.pathId());
                case UNIT -> supplyUnitId.equals(option.supplyUnitId());
                case OPTION ->
                        pathId.equals(option.pathId())
                                && supplyUnitId.equals(option.supplyUnitId());
            };
        }
    }

    public record Quantity(
            String id,
            String label,
            QuantityKind kind,
            BigDecimal value,
            List<String> supplyUnitIds,
            List<String> evidenceIds) {
        public Quantity {
            supplyUnitIds = immutable(supplyUnitIds);
            evidenceIds = immutable(evidenceIds);
        }
    }

    public record RentOffer(
            String id,
            String label,
            String applicabilityAsWritten,
            Long depositMinimum,
            Long depositMaximum,
            Long monthlyRentMinimum,
            Long monthlyRentMaximum,
            boolean pairedAmounts,
            String conversionAsWritten,
            List<String> supplyUnitIds,
            List<String> evidenceIds) {
        public RentOffer {
            supplyUnitIds = immutable(supplyUnitIds);
            evidenceIds = immutable(evidenceIds);
        }
    }

    public record Schedule(
            String id,
            String label,
            String targetAsWritten,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            String place,
            String applicationUrl,
            List<String> supplyUnitIds,
            List<String> evidenceIds) {
        public Schedule {
            supplyUnitIds = immutable(supplyUnitIds);
            evidenceIds = immutable(evidenceIds);
        }
    }
}

package com.toadzip.backend.eligibility.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Immutable, version-local source rows. Persistence assigns identities when a draft is saved. */
public final class RuleSetData {
    private RuleSetData() {}

    public record Document(
            Long id,
            Long attachmentId,
            String fileName,
            String mediaType,
            Long byteSize,
            String sha256,
            Long pageCount,
            String downloadUrl,
            String evidenceLevel,
            OffsetDateTime fetchedAt) {}

    public record Evidence(
            Long id,
            Long documentId,
            Long pdfPageFrom,
            Long pdfPageTo,
            String printedPageLabel,
            String locator,
            String excerpt,
            String verificationMethod) {}

    public record Relation(
            Long id,
            Long toAnnouncementId,
            String toDescription,
            LocalDate toAnnouncedOn,
            String relationType,
            Long evidenceId) {}

    public record Complex(
            Long id,
            Long housingComplexId,
            String complexNameAsWritten,
            String districtNameAsWritten,
            String addressAsWritten,
            String supplyMethod,
            String ownerAsWritten,
            Long totalUnitsAsWritten,
            LocalDate firstMoveInMonth,
            Long evidenceId) {}

    public record HousingType(
            Long id,
            Long announcementComplexId,
            Long housingTypeId,
            Long supplyRowId,
            String typeLabelAsWritten,
            BigDecimal exclusiveAreaM2,
            BigDecimal residentialCommonAreaM2,
            BigDecimal otherCommonAreaM2,
            BigDecimal parkingAreaM2,
            BigDecimal contractAreaM2,
            Boolean isAccessibleUnit,
            Long builtUnits,
            Long vacantUnits,
            String countNote,
            Long evidenceId) {}

    public record SupplyUnit(
            Long id,
            String unitLabelAsWritten,
            String rentalType,
            String unitKind,
            String allocationScope,
            Long existingWaitlistCount,
            String existingWaitlistNote,
            Long evidenceId) {}

    public record SupplyHousingType(Long supplyUnitId, Long announcementHousingTypeId) {}

    public record EligibilityPath(
            Long id,
            String pathLabelAsWritten,
            String targetGroupCode,
            String supplyCategory,
            String recruitmentStatus,
            Long eligibilityRootGroupId,
            Long selectionProcedureId,
            Long evidenceId) {}

    public record ApplicationOption(
            Long id,
            Long supplyUnitId,
            Long eligibilityPathId,
            Long optionConditionGroupId,
            String note,
            Long evidenceId) {}

    public record QuotaPool(
            Long id,
            String poolLabelAsWritten,
            String phase,
            String countKind,
            Long unitCount,
            BigDecimal ratioPercent,
            Long parentPoolId,
            Long entryConditionGroupId,
            String variabilityNote,
            Long evidenceId) {}

    public record QuotaOption(Long quotaPoolId, Long applicationOptionId) {}

    public record QuotaAllocationRule(
            Long id,
            String ruleKind,
            Long fromPoolId,
            Long toPoolId,
            Long fromPathId,
            Long toPathId,
            Long orderNo,
            Long appliesIfGroupId,
            String exceptionNote,
            String statementAsWritten,
            Long evidenceId) {}

    public record ConditionGroup(
            Long id,
            Long parentGroupId,
            String logic,
            String purpose,
            String label,
            Long orderNo,
            Long evidenceId) {}

    public record Condition(
            Long id,
            Long groupId,
            Long orderNo,
            String statementAsWritten,
            String conditionRole,
            String subjectCode,
            String factCode,
            String inputGapCode,
            Long referencedGroupId,
            String operator,
            BigDecimal valueNumber,
            BigDecimal valueNumberTo,
            String valueUnit,
            LocalDate valueDate,
            LocalDate valueDateTo,
            String valueCode,
            Long regionSetId,
            Long incomeStandardTableId,
            String householdSizeFactCode,
            String referenceDateRule,
            LocalDate referenceDate,
            String holdingPeriod,
            String interpretationStatus,
            String automationMode,
            String semanticCaveat,
            String inputMatch,
            Long exemptionGroupId,
            Long replacesConditionId,
            Long evidenceId) {}

    public record Adjustment(
            Long id,
            Long conditionId,
            Long triggerGroupId,
            String adjustmentKind,
            BigDecimal valueNumber,
            String stacking,
            Long orderNo,
            Long evidenceId) {}

    public record RegionSet(Long id, String label, Long evidenceId) {}

    public record RegionMember(Long regionSetId, String regionCode) {}

    public record SelectionProcedure(Long id, String labelAsWritten, Long evidenceId) {}

    public record SelectionStep(
            Long id,
            Long procedureId,
            Long stepNo,
            String stepKind,
            String usage,
            Long conditionGroupId,
            String sortFactCode,
            String sortInputGapCode,
            String sortDirection,
            String statementAsWritten,
            Long evidenceId) {}

    public record RankTier(
            Long id,
            Long stepId,
            Long tierNo,
            String labelAsWritten,
            Long conditionGroupId,
            Long evidenceId) {}

    public record ScoreItem(
            Long id,
            Long stepId,
            Long itemNo,
            String labelAsWritten,
            String aggregation,
            BigDecimal maxPoints,
            Long evidenceId) {}

    public record ScoreBand(
            Long id, Long scoreItemId, BigDecimal points, Long conditionGroupId, Long orderNo) {}

    public record RentOffer(
            Long id,
            String rentKind,
            String rentClassLabelAsWritten,
            Long rentClassGroupId,
            String amountPrecision,
            Long depositKrw,
            Long depositMaxKrw,
            Long downPaymentKrw,
            Long downPaymentMaxKrw,
            Long balanceKrw,
            Long balanceMaxKrw,
            Long monthlyRentKrw,
            Long monthlyRentMaxKrw,
            Long conversionDepositLimitKrw,
            String conversionNote,
            String asOfNote,
            Long evidenceId) {}

    public record RentSupplyUnit(Long rentOfferId, Long supplyUnitId) {}

    public record RentConversion(
            Long id,
            Long rentOfferId,
            String direction,
            Long deltaDepositKrw,
            Long depositKrw,
            Long depositMaxKrw,
            Long monthlyRentKrw,
            Long monthlyRentMaxKrw,
            BigDecimal ratePercent,
            Boolean isMaximum,
            Long evidenceId) {}

    public record ScheduleEvent(
            Long id,
            String eventKind,
            String channel,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            String timePrecision,
            String endConditionNote,
            Long rankTierId,
            Long eligibilityPathId,
            Long supplyUnitId,
            Long announcementComplexId,
            String rentalType,
            String labelAsWritten,
            Long evidenceId) {}

    public record AnnouncementRule(
            Long id,
            String ruleKind,
            Long eligibilityPathId,
            Long supplyUnitId,
            String rentalType,
            Long conditionGroupId,
            BigDecimal paramNumber,
            String paramUnit,
            String definitionStatus,
            String statementAsWritten,
            Long evidenceId) {}
}

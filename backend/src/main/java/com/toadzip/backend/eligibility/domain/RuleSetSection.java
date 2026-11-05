package com.toadzip.backend.eligibility.domain;

import static com.toadzip.backend.eligibility.domain.RuleSetData.*;

import java.util.List;
import java.util.function.Function;

/** Closed set of structured sections and their scalar contracts from the announcement design. */
public enum RuleSetSection {
    RULE_SET_DOCUMENTS(
            "documents",
            "rule_set_documents",
            Document.class,
            RuleSetContent::documents,
            List.of(
                new Field("id", "id", Kind.LONG, false, 0, List.of(), ""),
                new Field("attachmentId", "attachment_id", Kind.LONG, true, 0, List.of(), "announcement_attachments"),
                new Field("fileName", "file_name", Kind.STRING, false, 500, List.of(), ""),
                new Field("mediaType", "media_type", Kind.STRING, false, 100, List.of(), ""),
                new Field("byteSize", "byte_size", Kind.LONG, false, 0, List.of(), ""),
                new Field("sha256", "sha256", Kind.STRING, false, 64, List.of(), ""),
                new Field("pageCount", "page_count", Kind.LONG, true, 0, List.of(), ""),
                new Field("downloadUrl", "download_url", Kind.STRING, true, 2000, List.of(), ""),
                new Field("evidenceLevel", "evidence_level", Kind.STRING, false, 0, List.of("OFFICIAL_ATTACHMENT",
                        "OFFICIAL_ATTACHMENT_UNVERIFIED", "REPOSTED_PDF", "POLICY_GUIDE"), ""),
                new Field("fetchedAt", "fetched_at", Kind.OFFSETDATETIME, false, 0, List.of(), ""))),
    SOURCE_EVIDENCES(
            "evidences",
            "source_evidences",
            Evidence.class,
            RuleSetContent::evidences,
            List.of(
                new Field("id", "id", Kind.LONG, false, 0, List.of(), ""),
                new Field("documentId", "document_id", Kind.LONG, false, 0, List.of(), "rule_set_documents"),
                new Field("pdfPageFrom", "pdf_page_from", Kind.LONG, false, 0, List.of(), ""),
                new Field("pdfPageTo", "pdf_page_to", Kind.LONG, false, 0, List.of(), ""),
                new Field("printedPageLabel", "printed_page_label", Kind.STRING, true, 20, List.of(), ""),
                new Field("locator", "locator", Kind.STRING, true, 300, List.of(), ""),
                new Field("excerpt", "excerpt", Kind.STRING, false, 20000, List.of(), ""),
                new Field("verificationMethod", "verification_method", Kind.STRING, false, 0, List.of("TEXT",
                        "VISUAL"), ""))),
    ANNOUNCEMENT_RELATIONS(
            "relations",
            "announcement_relations",
            Relation.class,
            RuleSetContent::relations,
            List.of(
                new Field("id", "id", Kind.LONG, false, 0, List.of(), ""),
                new Field("toAnnouncementId", "to_announcement_id", Kind.LONG, true, 0, List.of(), "announcements"),
                new Field("toDescription", "to_description", Kind.STRING, true, 500, List.of(), ""),
                new Field("toAnnouncedOn", "to_announced_on", Kind.LOCALDATE, true, 0, List.of(), ""),
                new Field("relationType", "relation_type", Kind.STRING, false, 0, List.of(
                        "ADDITIONAL_RECRUITMENT_OF", "WAITLIST_RECRUITMENT_OF", "FOLLOWS_RULES_OF",
                        "CANCELS_WAITLIST_OF", "FOLLOW_UP_PLANNED", "CONVERTED_SUPPLY_FROM"), ""),
                new Field("evidenceId", "evidence_id", Kind.LONG, false, 0, List.of(), "source_evidences"))),
    ANNOUNCEMENT_COMPLEXES(
            "complexes",
            "eligibility_rule_set_complexes",
            Complex.class,
            RuleSetContent::complexes,
            List.of(
                new Field("id", "id", Kind.LONG, false, 0, List.of(), ""),
                new Field("housingComplexId", "housing_complex_id", Kind.LONG, true, 0, List.of(),
                        "housing_complexes"),
                new Field("complexNameAsWritten", "complex_name_as_written", Kind.STRING, false, 200, List.of(), ""),
                new Field("districtNameAsWritten", "district_name_as_written", Kind.STRING, true, 200, List.of(), ""),
                new Field("addressAsWritten", "address_as_written", Kind.STRING, true, 500, List.of(), ""),
                new Field("supplyMethod", "supply_method", Kind.STRING, false, 0, List.of("CONSTRUCTED", "PURCHASED",
                        "UNKNOWN"), ""),
                new Field("ownerAsWritten", "owner_as_written", Kind.STRING, true, 100, List.of(), ""),
                new Field("totalUnitsAsWritten", "total_units_as_written", Kind.LONG, true, 0, List.of(), ""),
                new Field("firstMoveInMonth", "first_move_in_month", Kind.LOCALDATE, true, 0, List.of(), ""),
                new Field("evidenceId", "evidence_id", Kind.LONG, false, 0, List.of(), "source_evidences"))),
    ANNOUNCEMENT_HOUSING_TYPES(
            "housingTypes",
            "announcement_housing_types",
            HousingType.class,
            RuleSetContent::housingTypes,
            List.of(
                new Field("id", "id", Kind.LONG, false, 0, List.of(), ""),
                new Field("announcementComplexId", "announcement_complex_id", Kind.LONG, false, 0, List.of(),
                        "announcement_complexes"),
                new Field("housingTypeId", "housing_type_id", Kind.LONG, true, 0, List.of(), "housing_types"),
                new Field("supplyRowId", "supply_row_id", Kind.LONG, true, 0, List.of(), "supply_rows"),
                new Field("typeLabelAsWritten", "type_label_as_written", Kind.STRING, false, 60, List.of(), ""),
                new Field("exclusiveAreaM2", "exclusive_area_m2", Kind.BIGDECIMAL, false, 0, List.of(), ""),
                new Field("residentialCommonAreaM2", "residential_common_area_m2", Kind.BIGDECIMAL, true, 0,
                        List.of(), ""),
                new Field("otherCommonAreaM2", "other_common_area_m2", Kind.BIGDECIMAL, true, 0, List.of(), ""),
                new Field("parkingAreaM2", "parking_area_m2", Kind.BIGDECIMAL, true, 0, List.of(), ""),
                new Field("contractAreaM2", "contract_area_m2", Kind.BIGDECIMAL, true, 0, List.of(), ""),
                new Field("isAccessibleUnit", "is_accessible_unit", Kind.BOOLEAN, false, 0, List.of(), ""),
                new Field("builtUnits", "built_units", Kind.LONG, true, 0, List.of(), ""),
                new Field("vacantUnits", "vacant_units", Kind.LONG, true, 0, List.of(), ""),
                new Field("countNote", "count_note", Kind.STRING, true, 200, List.of(), ""),
                new Field("evidenceId", "evidence_id", Kind.LONG, false, 0, List.of(), "source_evidences"))),
    SUPPLY_UNITS(
            "supplyUnits",
            "supply_units",
            SupplyUnit.class,
            RuleSetContent::supplyUnits,
            List.of(
                new Field("id", "id", Kind.LONG, false, 0, List.of(), ""),
                new Field("unitLabelAsWritten", "unit_label_as_written", Kind.STRING, false, 150, List.of(), ""),
                new Field("rentalType", "rental_type", Kind.STRING, false, 0, List.of("HAPPY_HOUSING",
                        "NATIONAL_RENTAL", "PERMANENT_RENTAL", "PUBLIC_RENTAL_5Y", "PUBLIC_RENTAL_10Y",
                        "PUBLIC_RENTAL_50Y", "INTEGRATED_PUBLIC_RENTAL", "REDEVELOPMENT_RENTAL", "LONG_TERM_JEONSE",
                        "ETC"), ""),
                new Field("unitKind", "unit_kind", Kind.STRING, false, 0, List.of("GENERAL", "ACCESSIBLE"), ""),
                new Field("allocationScope", "allocation_scope", Kind.STRING, false, 0, List.of("SINGLE_COMPLEX",
                        "MULTI_COMPLEX"), ""),
                new Field("existingWaitlistCount", "existing_waitlist_count", Kind.LONG, true, 0, List.of(), ""),
                new Field("existingWaitlistNote", "existing_waitlist_note", Kind.STRING, true, 200, List.of(), ""),
                new Field("evidenceId", "evidence_id", Kind.LONG, false, 0, List.of(), "source_evidences"))),
    SUPPLY_UNIT_HOUSING_TYPES(
            "supplyHousingTypes",
            "supply_unit_housing_types",
            SupplyHousingType.class,
            RuleSetContent::supplyHousingTypes,
            List.of(
                new Field("supplyUnitId", "supply_unit_id", Kind.LONG, false, 0, List.of(), "supply_units"),
                new Field("announcementHousingTypeId", "announcement_housing_type_id", Kind.LONG, false, 0,
                        List.of(), "announcement_housing_types"))),
    ELIGIBILITY_PATHS(
            "paths",
            "eligibility_paths",
            EligibilityPath.class,
            RuleSetContent::paths,
            List.of(
                new Field("id", "id", Kind.LONG, false, 0, List.of(), ""),
                new Field("pathLabelAsWritten", "path_label_as_written", Kind.STRING, false, 200, List.of(), ""),
                new Field("targetGroupCode", "target_group_code", Kind.STRING, true, 40, List.of(), ""),
                new Field("supplyCategory", "supply_category", Kind.STRING, false, 0, List.of("PRIORITY", "GENERAL",
                        "SPECIAL", "UNSPECIFIED"), ""),
                new Field("recruitmentStatus", "recruitment_status", Kind.STRING, false, 0, List.of("RECRUITING",
                        "NOT_RECRUITING", "UNCLEAR"), ""),
                new Field("eligibilityRootGroupId", "eligibility_root_group_id", Kind.LONG, false, 0, List.of(),
                        "condition_groups"),
                new Field("selectionProcedureId", "selection_procedure_id", Kind.LONG, true, 0, List.of(),
                        "selection_procedures"),
                new Field("evidenceId", "evidence_id", Kind.LONG, false, 0, List.of(), "source_evidences"))),
    APPLICATION_OPTIONS(
            "options",
            "application_options",
            ApplicationOption.class,
            RuleSetContent::options,
            List.of(
                new Field("id", "id", Kind.LONG, false, 0, List.of(), ""),
                new Field("supplyUnitId", "supply_unit_id", Kind.LONG, false, 0, List.of(), "supply_units"),
                new Field("eligibilityPathId", "eligibility_path_id", Kind.LONG, false, 0, List.of(),
                        "eligibility_paths"),
                new Field("optionConditionGroupId", "option_condition_group_id", Kind.LONG, true, 0, List.of(),
                        "condition_groups"),
                new Field("note", "note", Kind.STRING, true, 300, List.of(), ""),
                new Field("evidenceId", "evidence_id", Kind.LONG, false, 0, List.of(), "source_evidences"))),
    QUOTA_POOLS(
            "quotaPools",
            "quota_pools",
            QuotaPool.class,
            RuleSetContent::quotaPools,
            List.of(
                new Field("id", "id", Kind.LONG, false, 0, List.of(), ""),
                new Field("poolLabelAsWritten", "pool_label_as_written", Kind.STRING, false, 200, List.of(), ""),
                new Field("phase", "phase", Kind.STRING, false, 0, List.of("MOVE_IN", "WAITLIST", "UNSPECIFIED"), ""),
                new Field("countKind", "count_kind", Kind.STRING, false, 0, List.of("FIXED", "RATIO", "UNSTATED",
                        "NONE"), ""),
                new Field("unitCount", "unit_count", Kind.LONG, true, 0, List.of(), ""),
                new Field("ratioPercent", "ratio_percent", Kind.BIGDECIMAL, true, 0, List.of(), ""),
                new Field("parentPoolId", "parent_pool_id", Kind.LONG, true, 0, List.of(), "quota_pools"),
                new Field("entryConditionGroupId", "entry_condition_group_id", Kind.LONG, true, 0, List.of(),
                        "condition_groups"),
                new Field("variabilityNote", "variability_note", Kind.STRING, true, 300, List.of(), ""),
                new Field("evidenceId", "evidence_id", Kind.LONG, false, 0, List.of(), "source_evidences"))),
    QUOTA_POOL_OPTIONS(
            "quotaOptions",
            "quota_pool_options",
            QuotaOption.class,
            RuleSetContent::quotaOptions,
            List.of(
                new Field("quotaPoolId", "quota_pool_id", Kind.LONG, false, 0, List.of(), "quota_pools"),
                new Field("applicationOptionId", "application_option_id", Kind.LONG, false, 0, List.of(),
                        "application_options"))),
    QUOTA_ALLOCATION_RULES(
            "quotaAllocationRules",
            "quota_allocation_rules",
            QuotaAllocationRule.class,
            RuleSetContent::quotaAllocationRules,
            List.of(
                new Field("id", "id", Kind.LONG, false, 0, List.of(), ""),
                new Field("ruleKind", "rule_kind", Kind.STRING, false, 0, List.of("SHORTFALL_TRANSFER",
                        "LOSER_REAPPLIES", "AUTO_DUAL_APPLICATION", "CROSS_GROUP_TRANSFER", "REMAINDER_ORDER",
                        "UNIT_SUBSTITUTION", "INCOME_BAND_RATIO"), ""),
                new Field("fromPoolId", "from_pool_id", Kind.LONG, true, 0, List.of(), "quota_pools"),
                new Field("toPoolId", "to_pool_id", Kind.LONG, true, 0, List.of(), "quota_pools"),
                new Field("fromPathId", "from_path_id", Kind.LONG, true, 0, List.of(), "eligibility_paths"),
                new Field("toPathId", "to_path_id", Kind.LONG, true, 0, List.of(), "eligibility_paths"),
                new Field("orderNo", "order_no", Kind.LONG, true, 0, List.of(), ""),
                new Field("appliesIfGroupId", "applies_if_group_id", Kind.LONG, true, 0, List.of(),
                        "condition_groups"),
                new Field("exceptionNote", "exception_note", Kind.STRING, true, 500, List.of(), ""),
                new Field("statementAsWritten", "statement_as_written", Kind.STRING, false, 20000, List.of(), ""),
                new Field("evidenceId", "evidence_id", Kind.LONG, false, 0, List.of(), "source_evidences"))),
    CONDITION_GROUPS(
            "groups",
            "condition_groups",
            ConditionGroup.class,
            RuleSetContent::groups,
            List.of(
                new Field("id", "id", Kind.LONG, false, 0, List.of(), ""),
                new Field("parentGroupId", "parent_group_id", Kind.LONG, true, 0, List.of(), "condition_groups"),
                new Field("logic", "logic", Kind.STRING, false, 0, List.of("ALL", "ANY"), ""),
                new Field("purpose", "purpose", Kind.STRING, false, 0, List.of("ELIGIBILITY", "RANK_TIER",
                        "SCORE_BAND", "RENT_CLASS", "QUOTA_ENTRY", "EXEMPTION_TRIGGER", "ADJUSTMENT_TRIGGER",
                        "RULE_SCOPE", "OPTION_RESTRICTION", "SHARED_DEFINITION"), ""),
                new Field("label", "label", Kind.STRING, true, 200, List.of(), ""),
                new Field("orderNo", "order_no", Kind.LONG, false, 0, List.of(), ""),
                new Field("evidenceId", "evidence_id", Kind.LONG, true, 0, List.of(), "source_evidences"))),
    CONDITIONS(
            "conditions",
            "conditions",
            Condition.class,
            RuleSetContent::conditions,
            List.of(
                new Field("id", "id", Kind.LONG, false, 0, List.of(), ""),
                new Field("groupId", "group_id", Kind.LONG, false, 0, List.of(), "condition_groups"),
                new Field("orderNo", "order_no", Kind.LONG, false, 0, List.of(), ""),
                new Field("statementAsWritten", "statement_as_written", Kind.STRING, false, 20000, List.of(), ""),
                new Field("conditionRole", "condition_role", Kind.STRING, false, 0, List.of("REQUIREMENT",
                        "EXCLUSION", "OBLIGATION", "EXEMPTED", "REFERENCE_ONLY"), ""),
                new Field("subjectCode", "subject_code", Kind.STRING, false, 0, List.of("APPLICANT", "SPOUSE",
                        "APPLICANT_OR_SPOUSE", "APPLICANT_AND_PARENTS", "HOUSEHOLD_MEMBERS",
                        "HOUSEHOLD_IF_HEAD_ELSE_APPLICANT", "APPLICANT_IF_UNMARRIED_ELSE_HOUSEHOLD",
                        "FUTURE_HOUSEHOLD", "REPRESENTATIVE_APPLICANT", "ORGANIZATION", "NONE", "HOUSEHOLD"), ""),
                new Field("factCode", "fact_code", Kind.STRING, true, 60, List.of(), "eligibility_facts"),
                new Field("inputGapCode", "input_gap_code", Kind.STRING, true, 10, List.of(), "input_gaps"),
                new Field("referencedGroupId", "referenced_group_id", Kind.LONG, true, 0, List.of(),
                        "condition_groups"),
                new Field("operator", "operator", Kind.STRING, true, 0, List.of("LTE", "LT", "GTE", "GT", "EQ",
                        "NEQ", "BETWEEN", "IS_TRUE", "IS_FALSE", "IN_REGION_SET", "NOT_IN_REGION_SET",
                        "DATE_ON_OR_AFTER", "DATE_ON_OR_BEFORE", "DATE_BETWEEN", "EQ_CODE"), ""),
                new Field("valueNumber", "value_number", Kind.BIGDECIMAL, true, 0, List.of(), ""),
                new Field("valueNumberTo", "value_number_to", Kind.BIGDECIMAL, true, 0, List.of(), ""),
                new Field("valueUnit", "value_unit", Kind.STRING, true, 0, List.of("KRW", "KRW_PER_MONTH",
                        "PERCENT_OF_STANDARD", "YEARS", "MONTHS", "DAYS", "COUNT", "PERSONS", "M2"), ""),
                new Field("valueDate", "value_date", Kind.LOCALDATE, true, 0, List.of(), ""),
                new Field("valueDateTo", "value_date_to", Kind.LOCALDATE, true, 0, List.of(), ""),
                new Field("valueCode", "value_code", Kind.STRING, true, 60, List.of(), ""),
                new Field("regionSetId", "region_set_id", Kind.LONG, true, 0, List.of(), "region_sets"),
                new Field("incomeStandardTableId", "income_standard_table_id", Kind.LONG, true, 0, List.of(),
                        "income_standard_tables"),
                new Field("householdSizeFactCode", "household_size_fact_code", Kind.STRING, true, 60, List.of(),
                        "eligibility_facts"),
                new Field("referenceDateRule", "reference_date_rule", Kind.STRING, false, 0, List.of("BASE_DATE",
                        "SURVEY_DATE", "SPECIFIC_DATE", "MOVE_IN", "CONTRACT", "RENEWAL"), ""),
                new Field("referenceDate", "reference_date", Kind.LOCALDATE, true, 0, List.of(), ""),
                new Field("holdingPeriod", "holding_period", Kind.STRING, false, 0, List.of("AT_REFERENCE_DATE",
                        "UNTIL_MOVE_IN", "UNTIL_CONTRACT_END", "UNTIL_CONVERSION", "BY_MOVE_IN"), ""),
                new Field("interpretationStatus", "interpretation_status", Kind.STRING, false, 0, List.of(
                        "INTERPRETED", "UNINTERPRETED", "PENDING_DEFINITION", "AMBIGUOUS"), ""),
                new Field("automationMode", "automation_mode", Kind.STRING, false, 0, List.of("AUTOMATIC", "MANUAL",
                        "AGENCY_REVIEW"), ""),
                new Field("semanticCaveat", "semantic_caveat", Kind.STRING, true, 500, List.of(), ""),
                new Field("inputMatch", "input_match", Kind.STRING, false, 0, List.of("EXACT", "INPUT_BROADER",
                        "INPUT_NARROWER", "UNSETTLED"), ""),
                new Field("exemptionGroupId", "exemption_group_id", Kind.LONG, true, 0, List.of(),
                        "condition_groups"),
                new Field("replacesConditionId", "replaces_condition_id", Kind.LONG, true, 0, List.of(),
                        "conditions"),
                new Field("evidenceId", "evidence_id", Kind.LONG, false, 0, List.of(), "source_evidences"))),
    CONDITION_ADJUSTMENTS(
            "adjustments",
            "condition_adjustments",
            Adjustment.class,
            RuleSetContent::adjustments,
            List.of(
                new Field("id", "id", Kind.LONG, false, 0, List.of(), ""),
                new Field("conditionId", "condition_id", Kind.LONG, false, 0, List.of(), "conditions"),
                new Field("triggerGroupId", "trigger_group_id", Kind.LONG, false, 0, List.of(), "condition_groups"),
                new Field("adjustmentKind", "adjustment_kind", Kind.STRING, false, 0, List.of("ADD_PERCENT_POINTS",
                        "REPLACE_NUMBER", "REPLACE_PERCENT"), ""),
                new Field("valueNumber", "value_number", Kind.BIGDECIMAL, false, 0, List.of(), ""),
                new Field("stacking", "stacking", Kind.STRING, false, 0, List.of("SUM", "MAX"), ""),
                new Field("orderNo", "order_no", Kind.LONG, false, 0, List.of(), ""),
                new Field("evidenceId", "evidence_id", Kind.LONG, false, 0, List.of(), "source_evidences"))),
    REGION_SETS(
            "regionSets",
            "region_sets",
            RegionSet.class,
            RuleSetContent::regionSets,
            List.of(
                new Field("id", "id", Kind.LONG, false, 0, List.of(), ""),
                new Field("label", "label", Kind.STRING, false, 200, List.of(), ""),
                new Field("evidenceId", "evidence_id", Kind.LONG, true, 0, List.of(), "source_evidences"))),
    REGION_SET_MEMBERS(
            "regionMembers",
            "region_set_members",
            RegionMember.class,
            RuleSetContent::regionMembers,
            List.of(
                new Field("regionSetId", "region_set_id", Kind.LONG, false, 0, List.of(), "region_sets"),
                new Field("regionCode", "region_code", Kind.STRING, false, 10, List.of(), ""))),
    SELECTION_PROCEDURES(
            "selectionProcedures",
            "selection_procedures",
            SelectionProcedure.class,
            RuleSetContent::selectionProcedures,
            List.of(
                new Field("id", "id", Kind.LONG, false, 0, List.of(), ""),
                new Field("labelAsWritten", "label_as_written", Kind.STRING, false, 300, List.of(), ""),
                new Field("evidenceId", "evidence_id", Kind.LONG, false, 0, List.of(), "source_evidences"))),
    SELECTION_STEPS(
            "selectionSteps",
            "selection_steps",
            SelectionStep.class,
            RuleSetContent::selectionSteps,
            List.of(
                new Field("id", "id", Kind.LONG, false, 0, List.of(), ""),
                new Field("procedureId", "procedure_id", Kind.LONG, false, 0, List.of(), "selection_procedures"),
                new Field("stepNo", "step_no", Kind.LONG, false, 0, List.of(), ""),
                new Field("stepKind", "step_kind", Kind.STRING, false, 0, List.of("PRIORITY_GROUP", "RANK", "SCORE",
                        "SORT_BY_FACT", "LOTTERY", "ARRIVAL_ORDER", "AGENCY_ORDER", "SEVERITY_ORDER"), ""),
                new Field("usage", "usage", Kind.STRING, false, 0, List.of("SELECTION", "APPLICATION_ROUND", "BOTH"),
                        ""),
                new Field("conditionGroupId", "condition_group_id", Kind.LONG, true, 0, List.of(),
                        "condition_groups"),
                new Field("sortFactCode", "sort_fact_code", Kind.STRING, true, 60, List.of(), "eligibility_facts"),
                new Field("sortInputGapCode", "sort_input_gap_code", Kind.STRING, true, 10, List.of(), "input_gaps"),
                new Field("sortDirection", "sort_direction", Kind.STRING, true, 0, List.of("ASC", "DESC"), ""),
                new Field("statementAsWritten", "statement_as_written", Kind.STRING, false, 500, List.of(), ""),
                new Field("evidenceId", "evidence_id", Kind.LONG, false, 0, List.of(), "source_evidences"))),
    RANK_TIERS(
            "rankTiers",
            "rank_tiers",
            RankTier.class,
            RuleSetContent::rankTiers,
            List.of(
                new Field("id", "id", Kind.LONG, false, 0, List.of(), ""),
                new Field("stepId", "step_id", Kind.LONG, false, 0, List.of(), "selection_steps"),
                new Field("tierNo", "tier_no", Kind.LONG, false, 0, List.of(), ""),
                new Field("labelAsWritten", "label_as_written", Kind.STRING, false, 300, List.of(), ""),
                new Field("conditionGroupId", "condition_group_id", Kind.LONG, false, 0, List.of(),
                        "condition_groups"),
                new Field("evidenceId", "evidence_id", Kind.LONG, false, 0, List.of(), "source_evidences"))),
    SCORE_ITEMS(
            "scoreItems",
            "score_items",
            ScoreItem.class,
            RuleSetContent::scoreItems,
            List.of(
                new Field("id", "id", Kind.LONG, false, 0, List.of(), ""),
                new Field("stepId", "step_id", Kind.LONG, false, 0, List.of(), "selection_steps"),
                new Field("itemNo", "item_no", Kind.LONG, false, 0, List.of(), ""),
                new Field("labelAsWritten", "label_as_written", Kind.STRING, false, 300, List.of(), ""),
                new Field("aggregation", "aggregation", Kind.STRING, false, 0, List.of("BEST_BAND", "SUM_BANDS"), ""),
                new Field("maxPoints", "max_points", Kind.BIGDECIMAL, true, 0, List.of(), ""),
                new Field("evidenceId", "evidence_id", Kind.LONG, false, 0, List.of(), "source_evidences"))),
    SCORE_BANDS(
            "scoreBands",
            "score_bands",
            ScoreBand.class,
            RuleSetContent::scoreBands,
            List.of(
                new Field("id", "id", Kind.LONG, false, 0, List.of(), ""),
                new Field("scoreItemId", "score_item_id", Kind.LONG, false, 0, List.of(), "score_items"),
                new Field("points", "points", Kind.BIGDECIMAL, false, 0, List.of(), ""),
                new Field("conditionGroupId", "condition_group_id", Kind.LONG, false, 0, List.of(),
                        "condition_groups"),
                new Field("orderNo", "order_no", Kind.LONG, false, 0, List.of(), ""))),
    RENT_OFFERS(
            "rentOffers",
            "rent_offers",
            RentOffer.class,
            RuleSetContent::rentOffers,
            List.of(
                new Field("id", "id", Kind.LONG, false, 0, List.of(), ""),
                new Field("rentKind", "rent_kind", Kind.STRING, false, 0, List.of("MONTHLY", "JEONSE"), ""),
                new Field("rentClassLabelAsWritten", "rent_class_label_as_written", Kind.STRING, true, 200,
                        List.of(), ""),
                new Field("rentClassGroupId", "rent_class_group_id", Kind.LONG, true, 0, List.of(),
                        "condition_groups"),
                new Field("amountPrecision", "amount_precision", Kind.STRING, false, 0, List.of("EXACT", "RANGE",
                        "REPRESENTATIVE"), ""),
                new Field("depositKrw", "deposit_krw", Kind.LONG, true, 0, List.of(), ""),
                new Field("depositMaxKrw", "deposit_max_krw", Kind.LONG, true, 0, List.of(), ""),
                new Field("downPaymentKrw", "down_payment_krw", Kind.LONG, true, 0, List.of(), ""),
                new Field("downPaymentMaxKrw", "down_payment_max_krw", Kind.LONG, true, 0, List.of(), ""),
                new Field("balanceKrw", "balance_krw", Kind.LONG, true, 0, List.of(), ""),
                new Field("balanceMaxKrw", "balance_max_krw", Kind.LONG, true, 0, List.of(), ""),
                new Field("monthlyRentKrw", "monthly_rent_krw", Kind.LONG, true, 0, List.of(), ""),
                new Field("monthlyRentMaxKrw", "monthly_rent_max_krw", Kind.LONG, true, 0, List.of(), ""),
                new Field("conversionDepositLimitKrw", "conversion_deposit_limit_krw", Kind.LONG, true, 0, List.of(),
                        ""),
                new Field("conversionNote", "conversion_note", Kind.STRING, true, 500, List.of(), ""),
                new Field("asOfNote", "as_of_note", Kind.STRING, false, 300, List.of(), ""),
                new Field("evidenceId", "evidence_id", Kind.LONG, false, 0, List.of(), "source_evidences"))),
    RENT_OFFER_SUPPLY_UNITS(
            "rentSupplyUnits",
            "rent_offer_supply_units",
            RentSupplyUnit.class,
            RuleSetContent::rentSupplyUnits,
            List.of(
                new Field("rentOfferId", "rent_offer_id", Kind.LONG, false, 0, List.of(), "rent_offers"),
                new Field("supplyUnitId", "supply_unit_id", Kind.LONG, false, 0, List.of(), "supply_units"))),
    RENT_CONVERSIONS(
            "rentConversions",
            "rent_conversions",
            RentConversion.class,
            RuleSetContent::rentConversions,
            List.of(
                new Field("id", "id", Kind.LONG, false, 0, List.of(), ""),
                new Field("rentOfferId", "rent_offer_id", Kind.LONG, false, 0, List.of(), "rent_offers"),
                new Field("direction", "direction", Kind.STRING, false, 0, List.of("DEPOSIT_UP", "DEPOSIT_DOWN"), ""),
                new Field("deltaDepositKrw", "delta_deposit_krw", Kind.LONG, true, 0, List.of(), ""),
                new Field("depositKrw", "deposit_krw", Kind.LONG, false, 0, List.of(), ""),
                new Field("depositMaxKrw", "deposit_max_krw", Kind.LONG, true, 0, List.of(), ""),
                new Field("monthlyRentKrw", "monthly_rent_krw", Kind.LONG, false, 0, List.of(), ""),
                new Field("monthlyRentMaxKrw", "monthly_rent_max_krw", Kind.LONG, true, 0, List.of(), ""),
                new Field("ratePercent", "rate_percent", Kind.BIGDECIMAL, true, 0, List.of(), ""),
                new Field("isMaximum", "is_maximum", Kind.BOOLEAN, false, 0, List.of(), ""),
                new Field("evidenceId", "evidence_id", Kind.LONG, false, 0, List.of(), "source_evidences"))),
    SCHEDULE_EVENTS(
            "schedules",
            "schedule_events",
            ScheduleEvent.class,
            RuleSetContent::schedules,
            List.of(
                new Field("id", "id", Kind.LONG, false, 0, List.of(), ""),
                new Field("eventKind", "event_kind", Kind.STRING, false, 0, List.of("APPLICATION",
                        "DOCUMENT_TARGET_ANNOUNCEMENT", "DOCUMENT_SUBMISSION", "EXPLANATION", "WINNER_ANNOUNCEMENT",
                        "WAITLIST_ANNOUNCEMENT", "ELIGIBLE_ANNOUNCEMENT", "UNIT_DRAW", "CONTRACT", "MOVE_IN",
                        "SAMPLE_HOUSE", "OTHER"), ""),
                new Field("channel", "channel", Kind.STRING, true, 0, List.of("ONLINE", "ON_SITE", "MAIL", "EMAIL"),
                        ""),
                new Field("startsAt", "starts_at", Kind.OFFSETDATETIME, true, 0, List.of(), ""),
                new Field("endsAt", "ends_at", Kind.OFFSETDATETIME, true, 0, List.of(), ""),
                new Field("timePrecision", "time_precision", Kind.STRING, false, 0, List.of("DATETIME", "DATE",
                        "MONTH", "UNSPECIFIED"), ""),
                new Field("endConditionNote", "end_condition_note", Kind.STRING, true, 200, List.of(), ""),
                new Field("rankTierId", "rank_tier_id", Kind.LONG, true, 0, List.of(), "rank_tiers"),
                new Field("eligibilityPathId", "eligibility_path_id", Kind.LONG, true, 0, List.of(),
                        "eligibility_paths"),
                new Field("supplyUnitId", "supply_unit_id", Kind.LONG, true, 0, List.of(), "supply_units"),
                new Field("announcementComplexId", "announcement_complex_id", Kind.LONG, true, 0, List.of(),
                        "announcement_complexes"),
                new Field("rentalType", "rental_type", Kind.STRING, true, 0, List.of("HAPPY_HOUSING",
                        "NATIONAL_RENTAL", "PERMANENT_RENTAL", "PUBLIC_RENTAL_5Y", "PUBLIC_RENTAL_10Y",
                        "PUBLIC_RENTAL_50Y", "INTEGRATED_PUBLIC_RENTAL", "REDEVELOPMENT_RENTAL", "LONG_TERM_JEONSE",
                        "ETC"), ""),
                new Field("labelAsWritten", "label_as_written", Kind.STRING, false, 300, List.of(), ""),
                new Field("evidenceId", "evidence_id", Kind.LONG, false, 0, List.of(), "source_evidences"))),
    ANNOUNCEMENT_RULES(
            "rules",
            "announcement_rules",
            AnnouncementRule.class,
            RuleSetContent::rules,
            List.of(
                new Field("id", "id", Kind.LONG, false, 0, List.of(), ""),
                new Field("ruleKind", "rule_kind", Kind.STRING, false, 0, List.of("APPLICATION_METHOD",
                        "FIRST_COME", "UNIT_SELF_SELECTION", "CONTRACT_BEFORE_VERIFICATION", "PREFERENCE_CHOICE",
                        "DUPLICATE_APPLICATION_LIMIT", "WAITLIST_DUPLICATE_SELECTION", "LATER_ROUND_CUTOFF",
                        "DOCUMENT_REVIEW_POOL", "WAITLIST_VALIDITY", "WAITLIST_RECHECK", "MAX_RESIDENCE",
                        "RENEWAL_CONDITION", "RENEWAL_SURCHARGE", "POST_MOVE_IN_BENEFIT", "OTHER_NOTICE"), ""),
                new Field("eligibilityPathId", "eligibility_path_id", Kind.LONG, true, 0, List.of(),
                        "eligibility_paths"),
                new Field("supplyUnitId", "supply_unit_id", Kind.LONG, true, 0, List.of(), "supply_units"),
                new Field("rentalType", "rental_type", Kind.STRING, true, 0, List.of("HAPPY_HOUSING",
                        "NATIONAL_RENTAL", "PERMANENT_RENTAL", "PUBLIC_RENTAL_5Y", "PUBLIC_RENTAL_10Y",
                        "PUBLIC_RENTAL_50Y", "INTEGRATED_PUBLIC_RENTAL", "REDEVELOPMENT_RENTAL", "LONG_TERM_JEONSE",
                        "ETC"), ""),
                new Field("conditionGroupId", "condition_group_id", Kind.LONG, true, 0, List.of(),
                        "condition_groups"),
                new Field("paramNumber", "param_number", Kind.BIGDECIMAL, true, 0, List.of(), ""),
                new Field("paramUnit", "param_unit", Kind.STRING, true, 0, List.of("PERCENT", "YEARS", "MONTHS",
                        "COUNT", "MULTIPLE"), ""),
                new Field("definitionStatus", "definition_status", Kind.STRING, false, 0, List.of("DEFINED",
                        "PENDING_DEFINITION"), ""),
                new Field("statementAsWritten", "statement_as_written", Kind.STRING, false, 20000, List.of(), ""),
                new Field("evidenceId", "evidence_id", Kind.LONG, false, 0, List.of(), "source_evidences")));

    private final String property;
    private final String table;
    private final Class<?> rowType;
    private final Function<RuleSetContent, List<?>> rows;
    private final List<Field> fields;

    RuleSetSection(
            String property,
            String table,
            Class<?> rowType,
            Function<RuleSetContent, List<?>> rows,
            List<Field> fields) {
        this.property = property;
        this.table = table;
        this.rowType = rowType;
        this.rows = rows;
        this.fields = fields;
    }

    public String property() {
        return property;
    }

    public String table() {
        return table;
    }

    public Class<?> rowType() {
        return rowType;
    }

    public List<?> rows(RuleSetContent content) {
        return rows.apply(content);
    }

    public List<Field> fields() {
        return fields;
    }

    public Object value(Object row, String propertyName) {
        try {
            return rowType.getMethod(propertyName).invoke(row);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Invalid structured row accessor", exception);
        }
    }

    public boolean hasIdentity() {
        return fields.getFirst().name().equals("id");
    }

    public enum Kind {
        LONG,
        BIGDECIMAL,
        BOOLEAN,
        LOCALDATE,
        OFFSETDATETIME,
        STRING
    }

    public record Field(
            String name,
            String column,
            Kind kind,
            boolean nullable,
            int maxLength,
            List<String> allowedValues,
            String reference) {}
}

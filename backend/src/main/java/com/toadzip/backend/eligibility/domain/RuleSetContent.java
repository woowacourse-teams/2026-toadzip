package com.toadzip.backend.eligibility.domain;

import static com.toadzip.backend.eligibility.domain.RuleSetData.*;

import java.util.List;

/** One complete editable rule set; all references are within this object except shared catalogs. */
public record RuleSetContent(
        RuleSetMetadata metadata,
        List<Document> documents,
        List<Evidence> evidences,
        List<Relation> relations,
        List<Complex> complexes,
        List<HousingType> housingTypes,
        List<SupplyUnit> supplyUnits,
        List<SupplyHousingType> supplyHousingTypes,
        List<EligibilityPath> paths,
        List<ApplicationOption> options,
        List<QuotaPool> quotaPools,
        List<QuotaOption> quotaOptions,
        List<QuotaAllocationRule> quotaAllocationRules,
        List<ConditionGroup> groups,
        List<Condition> conditions,
        List<Adjustment> adjustments,
        List<RegionSet> regionSets,
        List<RegionMember> regionMembers,
        List<SelectionProcedure> selectionProcedures,
        List<SelectionStep> selectionSteps,
        List<RankTier> rankTiers,
        List<ScoreItem> scoreItems,
        List<ScoreBand> scoreBands,
        List<RentOffer> rentOffers,
        List<RentSupplyUnit> rentSupplyUnits,
        List<RentConversion> rentConversions,
        List<ScheduleEvent> schedules,
        List<AnnouncementRule> rules) {
    public RuleSetContent {
        documents = immutable(documents);
        evidences = immutable(evidences);
        relations = immutable(relations);
        complexes = immutable(complexes);
        housingTypes = immutable(housingTypes);
        supplyUnits = immutable(supplyUnits);
        supplyHousingTypes = immutable(supplyHousingTypes);
        paths = immutable(paths);
        options = immutable(options);
        quotaPools = immutable(quotaPools);
        quotaOptions = immutable(quotaOptions);
        quotaAllocationRules = immutable(quotaAllocationRules);
        groups = immutable(groups);
        conditions = immutable(conditions);
        adjustments = immutable(adjustments);
        regionSets = immutable(regionSets);
        regionMembers = immutable(regionMembers);
        selectionProcedures = immutable(selectionProcedures);
        selectionSteps = immutable(selectionSteps);
        rankTiers = immutable(rankTiers);
        scoreItems = immutable(scoreItems);
        scoreBands = immutable(scoreBands);
        rentOffers = immutable(rentOffers);
        rentSupplyUnits = immutable(rentSupplyUnits);
        rentConversions = immutable(rentConversions);
        schedules = immutable(schedules);
        rules = immutable(rules);
    }

    private static <T> List<T> immutable(List<T> values) {
        if (values == null) {
            return List.of();
        }
        return List.copyOf(values);
    }
}

package com.toadzip.backend.eligibility.domain;

import static com.toadzip.backend.eligibility.domain.ConditionOutcome.*;
import static com.toadzip.backend.eligibility.domain.RuleSetData.*;
import static com.toadzip.backend.eligibility.domain.UndeterminedReason.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** One evaluation session per profile and rule set. No shared mutable user state. */
public final class ConditionEvaluator {
    private static final Map<String, String> APPLICANT_COUNTERPART =
            Map.of(
                    "HOUSEHOLD_MONTHLY_INCOME", "APPLICANT_MONTHLY_INCOME",
                    "HOUSEHOLD_ASSETS", "APPLICANT_ASSETS",
                    "HOUSEHOLD_CAR_VALUE", "APPLICANT_CAR_VALUE",
                    "HOUSEHOLD_HAS_HOUSE_OWNER", "APPLICANT_OWNS_HOUSE",
                    "HOUSEHOLD_HAS_CAR_OWNER", "APPLICANT_OWNS_CAR");

    private final RuleSetContent content;
    private final EligibilityProfile profile;
    private final RegionMembership regions;
    private final ThresholdEvaluator thresholds;
    private final Map<Long, Condition> conditions;
    private final Map<Long, ConditionGroup> groups;
    private final Map<Long, List<Condition>> groupConditions;
    private final Map<Long, List<ConditionGroup>> childGroups;
    private final Map<Long, List<Adjustment>> adjustments;
    private final Map<Key, Judgment> cache = new HashMap<>();
    private final Set<Key> evaluating = new java.util.HashSet<>();

    public ConditionEvaluator(
            RuleSetContent content,
            EligibilityProfile profile,
            Map<Long, IncomeStandard> standards,
            RegionMembership regions) {
        this.content = content;
        this.profile = profile;
        this.regions = regions;
        this.thresholds = new ThresholdEvaluator(standards);
        this.conditions = index(content.conditions(), Condition::id);
        this.groups = index(content.groups(), ConditionGroup::id);
        this.groupConditions =
                content.conditions().stream()
                        .sorted(Comparator.comparing(Condition::orderNo))
                        .collect(Collectors.groupingBy(Condition::groupId));
        this.childGroups =
                content.groups().stream()
                        .filter(group -> group.parentGroupId() != null)
                        .sorted(Comparator.comparing(ConditionGroup::orderNo))
                        .collect(Collectors.groupingBy(ConditionGroup::parentGroupId));
        this.adjustments =
                content.adjustments().stream()
                        .sorted(Comparator.comparing(Adjustment::orderNo))
                        .collect(Collectors.groupingBy(Adjustment::conditionId));
    }

    FactResolver facts() {
        return new FactResolver(profile, content.metadata().eligibilityReferenceDate(), null);
    }

    public Judgment condition(long id) {
        return condition(id, content.metadata().eligibilityReferenceDate(), null);
    }

    public Judgment group(long id) {
        return group(id, content.metadata().eligibilityReferenceDate(), null);
    }

    private Judgment group(long id, LocalDate reference, Long size) {
        Key key = new Key(false, id, reference, size);
        if (cache.containsKey(key)) {
            return cache.get(key);
        }
        enter(key);
        List<Judgment> children = new ArrayList<>();
        for (ConditionGroup child : childGroups.getOrDefault(id, List.of())) {
            children.add(group(child.id(), reference, size));
        }
        for (Condition condition : groupConditions.getOrDefault(id, List.of())) {
            children.add(condition(condition.id(), reference, size));
        }
        Judgment result = Judgment.all(children);
        if ("ANY".equals(groups.get(id).logic())) {
            result = Judgment.any(children);
        }
        evaluating.remove(key);
        cache.put(key, result);
        return result;
    }

    private Judgment condition(long id, LocalDate reference, Long size) {
        Condition condition = conditions.get(id);
        if (condition.referenceDate() != null) {
            reference = condition.referenceDate();
        }
        Key key = new Key(true, id, reference, size);
        if (cache.containsKey(key)) {
            return cache.get(key);
        }
        enter(key);
        Judgment result = evaluate(condition, reference, size);
        evaluating.remove(key);
        cache.put(key, result);
        return result;
    }

    private Judgment evaluate(Condition condition, LocalDate reference, Long size) {
        if ("REFERENCE_ONLY".equals(condition.conditionRole())) {
            return Judgment.of(NOT_APPLICABLE);
        }
        if ("EXEMPTED".equals(condition.conditionRole())) {
            if (!"INTERPRETED".equals(condition.interpretationStatus())) {
                return raw(condition, reference, size);
            }
            return Judgment.of(EXEMPT);
        }
        Judgment exemption = Judgment.of(NOT_SATISFIED);
        if (condition.exemptionGroupId() != null) {
            exemption = group(condition.exemptionGroupId(), reference, size);
            if (exemption.outcome().passes()) {
                return Judgment.of(EXEMPT);
            }
        }
        Judgment result = raw(condition, reference, size);
        if ("EXCLUSION".equals(condition.conditionRole())) {
            result = result.negate(condition.id());
        }
        if ("OBLIGATION".equals(condition.conditionRole())
                && !result.outcome().passes()
                && "INTERPRETED".equals(condition.interpretationStatus())
                && "AUTOMATIC".equals(condition.automationMode())) {
            result = result.asObligation(condition.id());
        }
        if (exemption.outcome() == UNDETERMINED && result.outcome() == NOT_SATISFIED) {
            return new Judgment(
                    UNDETERMINED,
                    exemption.reasons(),
                    exemption.missingFacts(),
                    Set.of(condition.id()),
                    Set.of(),
                    result.shownValues());
        }
        return result;
    }

    private Judgment raw(Condition condition, LocalDate reference, Long size) {
        if (!"INTERPRETED".equals(condition.interpretationStatus())) {
            if ("PENDING_DEFINITION".equals(condition.interpretationStatus())) {
                return unknown(condition, PENDING_DEFINITION);
            }
            return unknown(condition, UNINTERPRETED);
        }
        if ("AGENCY_REVIEW".equals(condition.automationMode())) {
            return unknown(condition, AGENCY_REVIEW);
        }
        if ("MANUAL".equals(condition.automationMode())) {
            return unknown(condition, NOT_AUTOMATED);
        }
        if (!Set.of("BASE_DATE", "SPECIFIC_DATE").contains(condition.referenceDateRule())) {
            return unknown(condition, PENDING_DEFINITION);
        }
        if (condition.referencedGroupId() != null) {
            return group(condition.referencedGroupId(), reference, size);
        }
        if (condition.inputGapCode() != null) {
            return unknown(condition, NOT_IN_INPUT_LIST);
        }
        if ("UNSETTLED".equals(condition.inputMatch())) {
            return unknown(condition, MEANING_UNSETTLED);
        }
        return subject(condition, reference, size);
    }

    private Judgment subject(Condition condition, LocalDate reference, Long size) {
        String subject = condition.subjectCode();
        if ("HOUSEHOLD_IF_HEAD_ELSE_APPLICANT".equals(subject)) {
            String applicantFact = APPLICANT_COUNTERPART.get(condition.factCode());
            if (applicantFact == null) {
                return unknown(condition, NOT_AUTOMATED);
            }
            return agree(
                    condition,
                    List.of(
                            compare(condition, condition.factCode(), reference, null),
                            compare(condition, applicantFact, reference, 1L)));
        }
        if ("APPLICANT_IF_UNMARRIED_ELSE_HOUSEHOLD".equals(subject)) {
            return maritalSubject(condition, reference);
        }
        if ("SPOUSE".equals(subject)) {
            return unknown(condition, NOT_IN_INPUT_LIST);
        }
        if ("FUTURE_HOUSEHOLD".equals(subject)) {
            return unknown(condition, NOT_IN_INPUT_LIST);
        }
        if ("APPLICANT_OR_SPOUSE".equals(subject)) {
            return Judgment.any(
                    List.of(
                            compare(condition, condition.factCode(), reference, size),
                            unknown(condition, NOT_IN_INPUT_LIST)));
        }
        if ("ORGANIZATION".equals(subject)) {
            return unknown(condition, NOT_AUTOMATED);
        }
        return compare(condition, condition.factCode(), reference, size);
    }

    private Judgment maritalSubject(Condition condition, LocalDate reference) {
        String applicantFact =
                APPLICANT_COUNTERPART.getOrDefault(condition.factCode(), condition.factCode());
        FactReading married =
                new FactResolver(profile, reference, null).resolve("IS_MARRIED_AT_REFERENCE");
        if (married.state() == ValueState.PROVIDED) {
            if (Boolean.TRUE.equals(married.value())) {
                return compare(condition, condition.factCode(), reference, null);
            }
            return compare(condition, applicantFact, reference, 1L);
        }
        Judgment result =
                agree(
                        condition,
                        List.of(
                                compare(condition, condition.factCode(), reference, null),
                                compare(condition, applicantFact, reference, 1L)));
        if (result.reasons().contains(SUBJECT_AMBIGUOUS)) {
            return Judgment.unknown(condition.id(), INPUT_MISSING, married.inputs());
        }
        return result;
    }

    private Judgment compare(Condition condition, String code, LocalDate reference, Long size) {
        FactResolver facts = new FactResolver(profile, reference, size);
        FactReading value = facts.resolve(code);
        if (value.state() == ValueState.UNKNOWN) {
            return Judgment.unknown(condition.id(), INPUT_MISSING, value.inputs());
        }
        if (value.state() == ValueState.NOT_APPLICABLE) {
            return booleanResult("NOT_IN_REGION_SET".equals(condition.operator()));
        }
        if (value.value() instanceof Boolean flag) {
            return bool(condition, flag);
        }
        if (Set.of("IN_REGION_SET", "NOT_IN_REGION_SET").contains(condition.operator())) {
            return region(condition, value);
        }
        if (value.value() instanceof LocalDate date) {
            return date(condition, date);
        }
        if ("EQ_CODE".equals(condition.operator())) {
            return booleanResult(value.value().equals(condition.valueCode()));
        }
        ThresholdEvaluator.Bounds bounds =
                thresholds.evaluate(
                        condition,
                        adjustments.getOrDefault(condition.id(), List.of()),
                        facts,
                        id -> group(id, reference, size));
        if (bounds.low() == null) {
            return reblock(condition, Judgment.unresolved(bounds.uncertainty()));
        }
        BigDecimal upper = value.number();
        if (value.upperBound() != null) {
            upper = value.upperBound();
        }
        ConditionOutcome outcome =
                NumericComparison.compare(
                        condition.operator(),
                        value.number(),
                        upper,
                        bounds.low(),
                        bounds.high(),
                        condition.valueNumberTo());
        Map<String, Object> shown =
                Map.of(
                        "thresholdMin",
                        bounds.low(),
                        "thresholdMax",
                        bounds.high(),
                        "referenceDate",
                        reference,
                        "factCode",
                        code,
                        "valueMin",
                        value.number(),
                        "valueMax",
                        upper);
        if (outcome != UNDETERMINED) {
            return Judgment.of(outcome).withShownValues(shown);
        }
        List<Judgment> uncertainty = new ArrayList<>(bounds.uncertainty());
        if (value.upperBound() != null) {
            uncertainty.add(unknown(condition, NOT_IN_INPUT_LIST));
        }
        if (uncertainty.isEmpty()) {
            uncertainty.add(unknown(condition, UNINTERPRETED));
        }
        return reblock(condition, Judgment.unresolved(uncertainty)).withShownValues(shown);
    }

    private Judgment bool(Condition condition, boolean value) {
        if (("INPUT_BROADER".equals(condition.inputMatch()) && value)
                || ("INPUT_NARROWER".equals(condition.inputMatch()) && !value)) {
            return unknown(condition, MEANING_UNSETTLED);
        }
        return booleanResult(value == "IS_TRUE".equals(condition.operator()));
    }

    private Judgment date(Condition condition, LocalDate value) {
        boolean satisfied =
                switch (condition.operator()) {
                    case "DATE_ON_OR_AFTER" -> !value.isBefore(condition.valueDate());
                    case "DATE_ON_OR_BEFORE" -> !value.isAfter(condition.valueDate());
                    case "DATE_BETWEEN" ->
                            !value.isBefore(condition.valueDate())
                                    && !value.isAfter(condition.valueDateTo());
                    default -> throw new IllegalArgumentException("Unsupported date operator");
                };
        return booleanResult(satisfied);
    }

    private Judgment region(Condition condition, FactReading value) {
        Set<String> members =
                content.regionMembers().stream()
                        .filter(member -> member.regionSetId().equals(condition.regionSetId()))
                        .map(RegionMember::regionCode)
                        .collect(Collectors.toSet());
        RegionMembership.Match match = regions.match(members, (String) value.value());
        if (match == RegionMembership.Match.INSUFFICIENT_PRECISION) {
            return Judgment.unknown(condition.id(), INPUT_MISSING, value.inputs());
        }
        return booleanResult(
                (match == RegionMembership.Match.MATCH)
                        == "IN_REGION_SET".equals(condition.operator()));
    }

    private Judgment agree(Condition condition, List<Judgment> candidates) {
        List<Map<String, Object>> shownCandidates = new ArrayList<>();
        List<String> subjects = List.of("HOUSEHOLD", "APPLICANT");
        for (int index = 0; index < candidates.size(); index++) {
            Judgment candidate = candidates.get(index);
            shownCandidates.add(
                    Map.of(
                            "subject",
                            subjects.get(index),
                            "outcome",
                            candidate.outcome(),
                            "shownValues",
                            candidate.shownValues(),
                            "reasons",
                            candidate.reasons()));
        }
        Map<String, Object> shown = Map.of("subjectCandidates", List.copyOf(shownCandidates));
        if (candidates.stream().anyMatch(candidate -> candidate.outcome() == UNDETERMINED)) {
            return reblock(condition, Judgment.unresolved(candidates)).withShownValues(shown);
        }
        if (candidates.stream().map(Judgment::outcome).distinct().count() == 1) {
            return candidates.getFirst().withShownValues(shown);
        }
        return unknown(condition, SUBJECT_AMBIGUOUS).withShownValues(shown);
    }

    private Judgment reblock(Condition condition, Judgment result) {
        return new Judgment(
                UNDETERMINED,
                result.reasons(),
                result.missingFacts(),
                Set.of(condition.id()),
                result.obligations(),
                result.shownValues());
    }

    private Judgment unknown(Condition condition, UndeterminedReason reason) {
        return Judgment.unknown(condition.id(), reason, Set.of());
    }

    private Judgment booleanResult(boolean result) {
        if (result) {
            return Judgment.of(SATISFIED);
        }
        return Judgment.of(NOT_SATISFIED);
    }

    private void enter(Key key) {
        if (evaluating.size() >= 128 || !evaluating.add(key)) {
            throw new IllegalStateException("Published eligibility graph is cyclic or too deep");
        }
    }

    private static <T> Map<Long, T> index(List<T> rows, Function<T, Long> identity) {
        return rows.stream().collect(Collectors.toUnmodifiableMap(identity, Function.identity()));
    }

    private record Key(boolean condition, long id, LocalDate reference, Long size) {}
}

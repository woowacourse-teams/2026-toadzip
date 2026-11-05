package com.toadzip.backend.eligibility.domain;

import static com.toadzip.backend.eligibility.domain.EligibilityResult.*;
import static com.toadzip.backend.eligibility.domain.RuleSetData.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class EligibilityEvaluator {
    public static final String VERSION = "eligibility-1";

    public EligibilityResult evaluate(
            RuleSetContent content,
            EligibilityProfile profile,
            Map<Long, IncomeStandard> standards,
            RegionMembership regions) {
        ConditionEvaluator evaluation =
                new ConditionEvaluator(content, profile, standards, regions);
        Map<Long, EligibilityPath> paths = index(content.paths(), EligibilityPath::id);
        Map<Long, SupplyUnit> units = index(content.supplyUnits(), SupplyUnit::id);
        Map<Long, Condition> conditions = index(content.conditions(), Condition::id);
        List<OptionResult> options =
                content.options().stream()
                        .sorted(Comparator.comparing(ApplicationOption::id))
                        .map(
                                option ->
                                        option(
                                                content,
                                                option,
                                                paths.get(option.eligibilityPathId()),
                                                units.get(option.supplyUnitId()),
                                                conditions,
                                                evaluation))
                        .toList();
        List<ConditionResult> conditionResults =
                content.conditions().stream()
                        .sorted(Comparator.comparing(Condition::id))
                        .map(
                                condition ->
                                        condition(condition, evaluation.condition(condition.id())))
                        .toList();
        List<String> limitations = new ArrayList<>();
        limitations.add("당첨 또는 입주 가능성을 보증하지 않습니다. 공식 공고 원문과 기관 심사를 확인해 주세요.");
        if (!"COMPLETE".equals(content.metadata().structuringStatus())) {
            limitations.add("구조화 미완료: 아직 구조화하지 않은 원문 조건이 있을 수 있습니다.");
        }
        limitations.add("입력한 값과 공적자료의 금액, 세대 범위 및 법령상 정의가 다를 수 있습니다.");
        return new EligibilityResult(
                content.metadata().eligibilityReferenceDate(),
                summary(content, options),
                content.metadata().structuringStatus(),
                options,
                conditionResults,
                content.documents(),
                content.evidences(),
                content.rentOffers(),
                content.rentConversions(),
                content.schedules(),
                content.rules(),
                new Supply(
                        content.complexes(),
                        content.housingTypes(),
                        content.supplyUnits(),
                        content.supplyHousingTypes(),
                        content.quotaOptions(),
                        content.quotaAllocationRules()),
                quotas(content, evaluation),
                VERSION,
                List.copyOf(limitations));
    }

    private OptionResult option(
            RuleSetContent content,
            ApplicationOption option,
            EligibilityPath path,
            SupplyUnit unit,
            Map<Long, Condition> conditions,
            ConditionEvaluator evaluation) {
        List<Judgment> roots =
                new ArrayList<>(List.of(evaluation.group(path.eligibilityRootGroupId())));
        if (option.optionConditionGroupId() != null) {
            roots.add(evaluation.group(option.optionConditionGroupId()));
        }
        Judgment result = Judgment.all(roots);
        Outcome outcome = outcome(content, path, result);
        Set<Long> blockers = Set.of();
        Set<UndeterminedReason> reasons = Set.of();
        Set<InputFact> missing = Set.of();
        String kind = null;
        if (outcome == Outcome.UNDETERMINED) {
            blockers = result.blockers();
            Set<UndeterminedReason> collected = new LinkedHashSet<>(result.reasons());
            if ("UNCLEAR".equals(path.recruitmentStatus())) {
                collected.add(UndeterminedReason.PENDING_DEFINITION);
            }
            reasons = Set.copyOf(collected);
            missing = result.missingFacts();
            kind = "OPEN";
            if (!blockers.isEmpty()
                    && "RECRUITING".equals(path.recruitmentStatus())
                    && reasons.equals(Set.of(UndeterminedReason.NOT_IN_INPUT_LIST))
                    && blockers.stream().allMatch(id -> isConfirmation(conditions.get(id)))) {
                kind = "CONFIRMATION_ONLY";
            }
        }
        List<SelectionStep> steps =
                content.selectionSteps().stream()
                        .filter(step -> step.procedureId().equals(path.selectionProcedureId()))
                        .sorted(Comparator.comparing(SelectionStep::stepNo))
                        .toList();
        List<SelectionResult> selectionResults =
                steps.stream()
                        .map(step -> selectionStep(content, step, evaluation.facts(), evaluation))
                        .toList();
        SelectionRange range = summaryRange(steps, selectionResults);
        List<Long> rents =
                content.rentSupplyUnits().stream()
                        .filter(link -> link.supplyUnitId().equals(unit.id()))
                        .map(RentSupplyUnit::rentOfferId)
                        .distinct()
                        .filter(id -> candidateRent(content, id, evaluation))
                        .toList();
        return new OptionResult(
                option.id(),
                unit.id(),
                path.id(),
                unit.unitLabelAsWritten(),
                path.pathLabelAsWritten(),
                path.recruitmentStatus(),
                outcome,
                kind,
                blockers,
                reasons,
                missing,
                result.obligations(),
                message(outcome, kind, blockers.size(), result.obligations().size(), reasons),
                range.rankMin(),
                range.rankMax(),
                range.scoreMin(),
                range.scoreMax(),
                rents,
                steps,
                selectionResults);
    }

    private Outcome outcome(RuleSetContent content, EligibilityPath path, Judgment result) {
        if ("ORGANIZATION".equals(content.metadata().applicantKind())) {
            return Outcome.NOT_FOR_INDIVIDUAL;
        }
        if ("NOT_RECRUITING".equals(path.recruitmentStatus())) {
            return Outcome.NOT_RECRUITING;
        }
        if (result.outcome() == ConditionOutcome.NOT_SATISFIED) {
            return Outcome.NOT_ELIGIBLE;
        }
        if (result.outcome() == ConditionOutcome.UNDETERMINED
                || "UNCLEAR".equals(path.recruitmentStatus())) {
            return Outcome.UNDETERMINED;
        }
        if (!result.obligations().isEmpty()) {
            return Outcome.ELIGIBLE_WITH_OBLIGATIONS;
        }
        return Outcome.ELIGIBLE;
    }

    private ConditionResult condition(Condition condition, Judgment result) {
        UndeterminedReason primary = null;
        if (result.outcome() == ConditionOutcome.UNDETERMINED) {
            primary = result.reasons().stream().sorted().findFirst().orElseThrow();
        }
        return new ConditionResult(
                condition.id(),
                result.outcome(),
                primary,
                result.reasons(),
                result.missingFacts(),
                result.shownValues(),
                condition.statementAsWritten(),
                condition.subjectCode(),
                condition.conditionRole(),
                condition.evidenceId(),
                condition.inputGapCode(),
                isConfirmation(condition)
                        && result.outcome() == ConditionOutcome.UNDETERMINED
                        && result.reasons().equals(Set.of(UndeterminedReason.NOT_IN_INPUT_LIST)),
                condition.semanticCaveat(),
                condition.holdingPeriod(),
                condition.referenceDateRule());
    }

    public static boolean isConfirmation(Condition condition) {
        return condition != null
                && "EXCLUSION".equals(condition.conditionRole())
                && condition.inputGapCode() != null
                && "INTERPRETED".equals(condition.interpretationStatus())
                && "AUTOMATIC".equals(condition.automationMode());
    }

    private boolean candidateRent(
            RuleSetContent content, long rentId, ConditionEvaluator evaluation) {
        RentOffer offer =
                content.rentOffers().stream()
                        .filter(rent -> rent.id() == rentId)
                        .findFirst()
                        .orElseThrow();
        return offer.rentClassGroupId() == null
                || evaluation.group(offer.rentClassGroupId()).outcome()
                        != ConditionOutcome.NOT_SATISFIED;
    }

    private List<QuotaResult> quotas(RuleSetContent content, ConditionEvaluator evaluation) {
        return content.quotaPools().stream()
                .map(
                        pool -> {
                            Judgment entry = Judgment.of(ConditionOutcome.SATISFIED);
                            if (pool.entryConditionGroupId() != null) {
                                entry = evaluation.group(pool.entryConditionGroupId());
                            }
                            return new QuotaResult(
                                    pool, entry.outcome(), entry.reasons(), entry.missingFacts());
                        })
                .toList();
    }

    private SelectionResult selectionStep(
            RuleSetContent content,
            SelectionStep step,
            FactResolver facts,
            ConditionEvaluator evaluation) {
        if ("RANK".equals(step.stepKind())) {
            List<RankTier> tiers =
                    content.rankTiers().stream()
                            .filter(tier -> tier.stepId().equals(step.id()))
                            .sorted(Comparator.comparing(RankTier::tierNo))
                            .toList();
            List<RankTier> candidates =
                    tiers.stream()
                            .filter(
                                    tier ->
                                            evaluation.group(tier.conditionGroupId()).outcome()
                                                    != ConditionOutcome.NOT_SATISFIED)
                            .toList();
            Long minimum = null;
            Long maximum = null;
            if (!candidates.isEmpty()) {
                minimum = candidates.getFirst().tierNo();
                maximum =
                        candidates.stream()
                                .filter(
                                        tier ->
                                                evaluation
                                                        .group(tier.conditionGroupId())
                                                        .outcome()
                                                        .passes())
                                .map(RankTier::tierNo)
                                .findFirst()
                                .orElse(candidates.getLast().tierNo());
            }
            Judgment judgment =
                    Judgment.any(
                            tiers.stream()
                                    .map(tier -> evaluation.group(tier.conditionGroupId()))
                                    .toList());
            if (minimum != null && !minimum.equals(maximum)) {
                judgment =
                        Judgment.unresolved(
                                candidates.stream()
                                        .map(tier -> evaluation.group(tier.conditionGroupId()))
                                        .toList());
            }
            return selectionResult(step, minimum, maximum, null, null, judgment);
        }
        if ("SCORE".equals(step.stepKind())) {
            BigDecimal low = BigDecimal.ZERO;
            BigDecimal high = BigDecimal.ZERO;
            for (ScoreItem item :
                    content.scoreItems().stream()
                            .filter(item -> item.stepId().equals(step.id()))
                            .toList()) {
                ScoreRange range = score(content, item, evaluation);
                low = low.add(range.low());
                high = high.add(range.high());
            }
            Judgment judgment = Judgment.of(ConditionOutcome.SATISFIED);
            if (low.compareTo(high) != 0) {
                Set<Long> itemIds =
                        content.scoreItems().stream()
                                .filter(item -> item.stepId().equals(step.id()))
                                .map(ScoreItem::id)
                                .collect(Collectors.toSet());
                judgment =
                        Judgment.unresolved(
                                content.scoreBands().stream()
                                        .filter(band -> itemIds.contains(band.scoreItemId()))
                                        .map(band -> evaluation.group(band.conditionGroupId()))
                                        .toList());
            }
            return selectionResult(step, null, null, low, high, judgment);
        }
        if ("PRIORITY_GROUP".equals(step.stepKind())) {
            return selectionResult(
                    step, null, null, null, null, evaluation.group(step.conditionGroupId()));
        }
        if ("SORT_BY_FACT".equals(step.stepKind()) && step.sortFactCode() != null) {
            FactReading reading = facts.resolve(step.sortFactCode());
            Judgment judgment = Judgment.of(ConditionOutcome.SATISFIED);
            if (reading.state() == ValueState.UNKNOWN) {
                judgment =
                        Judgment.unknown(
                                step.id(), UndeterminedReason.INPUT_MISSING, reading.inputs());
            }
            return selectionResult(step, null, null, null, null, judgment);
        }
        UndeterminedReason reason = UndeterminedReason.NOT_AUTOMATED;
        if (step.sortInputGapCode() != null) {
            reason = UndeterminedReason.NOT_IN_INPUT_LIST;
        }
        if ("AGENCY_ORDER".equals(step.stepKind())) {
            reason = UndeterminedReason.AGENCY_REVIEW;
        }
        return selectionResult(
                step, null, null, null, null, Judgment.unknown(step.id(), reason, Set.of()));
    }

    private SelectionResult selectionResult(
            SelectionStep step,
            Long rankMin,
            Long rankMax,
            BigDecimal scoreMin,
            BigDecimal scoreMax,
            Judgment judgment) {
        return new SelectionResult(
                step.id(),
                rankMin,
                rankMax,
                scoreMin,
                scoreMax,
                judgment.outcome(),
                judgment.reasons(),
                judgment.missingFacts());
    }

    private SelectionRange summaryRange(List<SelectionStep> steps, List<SelectionResult> results) {
        Map<Long, String> kinds =
                steps.stream()
                        .collect(Collectors.toMap(SelectionStep::id, SelectionStep::stepKind));
        List<SelectionResult> ranks =
                results.stream()
                        .filter(result -> "RANK".equals(kinds.get(result.stepId())))
                        .toList();
        List<SelectionResult> scores =
                results.stream()
                        .filter(result -> "SCORE".equals(kinds.get(result.stepId())))
                        .toList();
        Long rankMin = null;
        Long rankMax = null;
        BigDecimal scoreMin = null;
        BigDecimal scoreMax = null;
        if (ranks.size() == 1) {
            rankMin = ranks.getFirst().rankTierMin();
            rankMax = ranks.getFirst().rankTierMax();
        }
        if (scores.size() == 1) {
            scoreMin = scores.getFirst().scoreMin();
            scoreMax = scores.getFirst().scoreMax();
        }
        return new SelectionRange(rankMin, rankMax, scoreMin, scoreMax);
    }

    private ScoreRange score(
            RuleSetContent content, ScoreItem item, ConditionEvaluator evaluation) {
        BigDecimal low = BigDecimal.ZERO;
        BigDecimal high = BigDecimal.ZERO;
        List<BigDecimal> certain = new ArrayList<>();
        List<BigDecimal> possible = new ArrayList<>();
        for (ScoreBand band :
                content.scoreBands().stream()
                        .filter(band -> band.scoreItemId().equals(item.id()))
                        .toList()) {
            ConditionOutcome outcome = evaluation.group(band.conditionGroupId()).outcome();
            if (outcome.passes()) {
                certain.add(band.points());
                possible.add(band.points());
                low = low.add(band.points());
                high = high.add(band.points());
            }
            if (outcome == ConditionOutcome.UNDETERMINED) {
                possible.add(band.points());
                low = low.add(band.points().min(BigDecimal.ZERO));
                high = high.add(band.points().max(BigDecimal.ZERO));
            }
        }
        if ("BEST_BAND".equals(item.aggregation())) {
            low = certain.stream().max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
            high = possible.stream().max(BigDecimal::compareTo).orElse(BigDecimal.ZERO).max(low);
            if (certain.isEmpty()) {
                low =
                        possible.stream()
                                .min(BigDecimal::compareTo)
                                .orElse(BigDecimal.ZERO)
                                .min(BigDecimal.ZERO);
            }
        }
        if (item.maxPoints() != null) {
            low = low.min(item.maxPoints());
            high = high.min(item.maxPoints());
        }
        return new ScoreRange(low, high);
    }

    private Summary summary(RuleSetContent content, List<OptionResult> options) {
        if ("ORGANIZATION".equals(content.metadata().applicantKind())) {
            return Summary.OUT_OF_SCOPE;
        }
        if (options.stream().anyMatch(option -> option.outcome() == Outcome.ELIGIBLE)) {
            return Summary.HAS_ELIGIBLE;
        }
        if (options.stream()
                .anyMatch(option -> option.outcome() == Outcome.ELIGIBLE_WITH_OBLIGATIONS)) {
            return Summary.HAS_CONDITIONAL;
        }
        if (options.stream()
                .anyMatch(option -> "CONFIRMATION_ONLY".equals(option.undeterminedKind()))) {
            return Summary.HAS_CONFIRMATION_ONLY;
        }
        if (options.stream().anyMatch(option -> option.outcome() == Outcome.UNDETERMINED)) {
            return Summary.UNDETERMINED_ONLY;
        }
        return Summary.NONE_ELIGIBLE;
    }

    private String message(
            Outcome outcome,
            String kind,
            int confirmations,
            int obligations,
            Set<UndeterminedReason> reasons) {
        if ("CONFIRMATION_ONLY".equals(kind)) {
            String message = "판정 가능한 조건 모두 충족 · 확인 사항 " + confirmations + "건";
            if (obligations > 0) {
                message += " · 입주 전 이행 " + obligations + "건";
            }
            return message;
        }
        return switch (outcome) {
            case ELIGIBLE -> "지원할 수 있어요";
            case ELIGIBLE_WITH_OBLIGATIONS -> "지원할 수 있어요 · 입주 전 이행 " + obligations + "건";
            case NOT_ELIGIBLE -> "지원할 수 없어요";
            case NOT_RECRUITING -> "이번 모집 없음";
            case NOT_FOR_INDIVIDUAL -> "개인 신청 대상이 아니에요";
            case UNDETERMINED -> undeterminedMessage(reasons);
        };
    }

    private String undeterminedMessage(Set<UndeterminedReason> reasons) {
        if (reasons.contains(UndeterminedReason.INPUT_MISSING)) {
            return "정보를 더 입력하면 더 정확해요";
        }
        return "서비스가 판단할 수 없는 조건이 있어요";
    }

    private static <T> Map<Long, T> index(List<T> rows, Function<T, Long> identity) {
        return rows.stream().collect(Collectors.toMap(identity, Function.identity()));
    }

    private record SelectionRange(
            Long rankMin, Long rankMax, BigDecimal scoreMin, BigDecimal scoreMax) {}

    private record ScoreRange(BigDecimal low, BigDecimal high) {}
}

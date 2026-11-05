package com.toadzip.backend.eligibility.domain;

import static com.toadzip.backend.eligibility.domain.RuleSetData.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Bounds over possible adjustments; unknown triggers never silently become true or false. */
final class ThresholdEvaluator {
    private final Map<Long, IncomeStandard> standards;

    ThresholdEvaluator(Map<Long, IncomeStandard> standards) {
        this.standards = standards;
    }

    Bounds evaluate(
            Condition condition,
            List<Adjustment> adjustments,
            FactResolver facts,
            Function<Long, Judgment> groups) {
        List<Judgment> uncertainty = new ArrayList<>();
        List<BigDecimal> bases = new ArrayList<>(List.of(condition.valueNumber()));
        BigDecimal sumLow = BigDecimal.ZERO;
        BigDecimal sumHigh = BigDecimal.ZERO;
        BigDecimal maxLow = BigDecimal.ZERO;
        BigDecimal maxHigh = BigDecimal.ZERO;
        List<BigDecimal> certainReplacements = new ArrayList<>();
        List<BigDecimal> uncertainReplacements = new ArrayList<>();
        for (Adjustment adjustment : adjustments) {
            Judgment trigger = groups.apply(adjustment.triggerGroupId());
            if (!trigger.outcome().passes() && trigger.outcome() != ConditionOutcome.UNDETERMINED) {
                continue;
            }
            boolean certain = trigger.outcome().passes();
            if (!certain) {
                uncertainty.add(trigger);
            }
            if (!"ADD_PERCENT_POINTS".equals(adjustment.adjustmentKind())) {
                bases.add(adjustment.valueNumber());
                if (certain) {
                    certainReplacements.add(adjustment.valueNumber());
                }
                if (!certain) {
                    uncertainReplacements.add(adjustment.valueNumber());
                }
                continue;
            }
            if ("MAX".equals(adjustment.stacking())) {
                maxHigh = maxHigh.max(adjustment.valueNumber());
                if (certain) {
                    maxLow = maxLow.max(adjustment.valueNumber());
                }
                continue;
            }
            sumHigh = sumHigh.add(adjustment.valueNumber());
            if (certain) {
                sumLow = sumLow.add(adjustment.valueNumber());
            }
        }
        if (certainReplacements.size() > 1) {
            return unresolved(condition, UndeterminedReason.UNINTERPRETED, Set.of());
        }
        if (!certainReplacements.isEmpty()) {
            bases = new ArrayList<>(certainReplacements);
            bases.addAll(uncertainReplacements);
        }
        BigDecimal low =
                bases.stream().min(BigDecimal::compareTo).orElseThrow().add(sumLow).add(maxLow);
        BigDecimal high =
                bases.stream().max(BigDecimal::compareTo).orElseThrow().add(sumHigh).add(maxHigh);
        if (!"PERCENT_OF_STANDARD".equals(condition.valueUnit())) {
            return new Bounds(low, high, uncertainty);
        }
        FactReading size = facts.resolve(condition.householdSizeFactCode());
        if (size.state() != ValueState.PROVIDED) {
            return unresolved(condition, UndeterminedReason.INPUT_MISSING, size.inputs());
        }
        IncomeStandard standard = standards.get(condition.incomeStandardTableId());
        if (standard == null) {
            return unresolved(condition, UndeterminedReason.PENDING_DEFINITION, Set.of());
        }
        var amount = standard.amount(size.number().longValueExact());
        if (amount.isEmpty()) {
            return unresolved(condition, UndeterminedReason.PENDING_DEFINITION, Set.of());
        }
        return new Bounds(won(amount.get(), low), won(amount.get(), high), uncertainty);
    }

    private BigDecimal won(BigDecimal amount, BigDecimal percent) {
        return amount.multiply(percent).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
    }

    private Bounds unresolved(
            Condition condition, UndeterminedReason reason, Set<InputFact> missing) {
        return new Bounds(null, null, List.of(Judgment.unknown(condition.id(), reason, missing)));
    }

    record Bounds(BigDecimal low, BigDecimal high, List<Judgment> uncertainty) {}
}

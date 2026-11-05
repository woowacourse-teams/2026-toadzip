package com.toadzip.backend.eligibility.domain;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Evaluation state carries only reasons that still determine its enclosing expression. */
public record Judgment(
        ConditionOutcome outcome,
        Set<UndeterminedReason> reasons,
        Set<InputFact> missingFacts,
        Set<Long> blockers,
        Set<Long> obligations,
        Map<String, Object> shownValues) {
    public Judgment {
        reasons = Set.copyOf(reasons);
        missingFacts = Set.copyOf(missingFacts);
        blockers = Set.copyOf(blockers);
        obligations = Set.copyOf(obligations);
        shownValues = Map.copyOf(shownValues);
    }

    public static Judgment of(ConditionOutcome outcome) {
        return new Judgment(outcome, Set.of(), Set.of(), Set.of(), Set.of(), Map.of());
    }

    public static Judgment unknown(
            long conditionId, UndeterminedReason reason, Set<InputFact> missing) {
        return new Judgment(
                ConditionOutcome.UNDETERMINED,
                Set.of(reason),
                missing,
                Set.of(conditionId),
                Set.of(),
                Map.of());
    }

    public Judgment withShownValues(Map<String, Object> values) {
        return new Judgment(outcome, reasons, missingFacts, blockers, obligations, values);
    }

    public Judgment asObligation(long conditionId) {
        return new Judgment(
                ConditionOutcome.OBLIGATION_PENDING,
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(conditionId),
                shownValues);
    }

    public Judgment negate(long conditionId) {
        if (outcome == ConditionOutcome.UNDETERMINED) {
            return new Judgment(
                    outcome, reasons, missingFacts, Set.of(conditionId), Set.of(), shownValues);
        }
        if (outcome.passes()) {
            return of(ConditionOutcome.NOT_SATISFIED).withShownValues(shownValues);
        }
        return of(ConditionOutcome.SATISFIED).withShownValues(shownValues);
    }

    public static Judgment all(List<Judgment> children) {
        if (children.stream().anyMatch(child -> child.outcome == ConditionOutcome.NOT_SATISFIED)) {
            return of(ConditionOutcome.NOT_SATISFIED);
        }
        if (children.stream().anyMatch(child -> child.outcome == ConditionOutcome.UNDETERMINED)) {
            return unresolved(children);
        }
        Set<Long> obligations = new LinkedHashSet<>();
        children.forEach(child -> obligations.addAll(child.obligations));
        return new Judgment(
                ConditionOutcome.SATISFIED, Set.of(), Set.of(), Set.of(), obligations, Map.of());
    }

    public static Judgment any(List<Judgment> children) {
        List<Judgment> passing = children.stream().filter(child -> child.outcome.passes()).toList();
        if (!passing.isEmpty()) {
            return passing.stream()
                    .filter(child -> child.obligations.isEmpty())
                    .findFirst()
                    .orElse(passing.getFirst());
        }
        if (children.stream().anyMatch(child -> child.outcome == ConditionOutcome.UNDETERMINED)) {
            return unresolved(children);
        }
        return of(ConditionOutcome.NOT_SATISFIED);
    }

    public static Judgment unresolved(List<Judgment> children) {
        Set<UndeterminedReason> reasons = new LinkedHashSet<>();
        Set<InputFact> missing = new LinkedHashSet<>();
        Set<Long> blockers = new LinkedHashSet<>();
        Set<Long> obligations = new LinkedHashSet<>();
        for (Judgment child : children) {
            reasons.addAll(child.reasons);
            missing.addAll(child.missingFacts);
            blockers.addAll(child.blockers);
            obligations.addAll(child.obligations);
        }
        return new Judgment(
                ConditionOutcome.UNDETERMINED, reasons, missing, blockers, obligations, Map.of());
    }
}

package com.toadzip.backend.eligibility.domain;

public enum ConditionOutcome {
    SATISFIED,
    NOT_SATISFIED,
    UNDETERMINED,
    NOT_APPLICABLE,
    EXEMPT,
    OBLIGATION_PENDING;

    public boolean passes() {
        return this == SATISFIED || this == OBLIGATION_PENDING;
    }
}

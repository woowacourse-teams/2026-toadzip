package com.toadzip.backend.eligibility.exception;

import java.util.List;

public class InvalidEligibilityException extends RuntimeException {
    private final List<String> violations;

    public InvalidEligibilityException(String violation) {
        this(List.of(violation));
    }

    public InvalidEligibilityException(List<String> violations) {
        super("자격 판단 입력이 올바르지 않습니다.");
        this.violations = List.copyOf(violations);
    }

    public List<String> violations() {
        return violations;
    }
}

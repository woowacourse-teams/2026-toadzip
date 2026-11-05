package com.toadzip.backend.eligibility.domain;

import java.util.List;

public final class EligibilityCatalog {
    private EligibilityCatalog() {}

    public record Fact(
            String code,
            String label,
            String factKind,
            String valueType,
            String subjectLevel,
            String meaningStatus,
            String meaningNote,
            List<String> dependsOn) {
        public Fact {
            dependsOn = List.copyOf(dependsOn);
        }
    }

    public record InputGap(
            String code,
            String label,
            String neededFact,
            String subjectDescription,
            String whyNotComputable,
            String followUpQuestion) {}
}

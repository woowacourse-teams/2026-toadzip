package com.toadzip.backend.eligibility.domain;

import java.time.LocalDate;
import java.time.OffsetDateTime;

public record RuleSetMetadata(
        String recruitmentKind,
        String applicantKind,
        Boolean hasEligibilityRelaxation,
        String sourceUrl,
        OffsetDateTime sourcePublishedAt,
        OffsetDateTime collectedAt,
        LocalDate eligibilityReferenceDate,
        String structuringStatus) {}

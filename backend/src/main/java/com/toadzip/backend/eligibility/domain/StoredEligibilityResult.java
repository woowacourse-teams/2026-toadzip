package com.toadzip.backend.eligibility.domain;

import java.time.Instant;

public record StoredEligibilityResult(EligibilityBasis basis, EligibilityDecision result, Instant evaluatedAt) {}

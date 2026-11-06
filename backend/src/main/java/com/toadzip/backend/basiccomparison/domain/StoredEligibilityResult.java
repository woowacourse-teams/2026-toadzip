package com.toadzip.backend.basiccomparison.domain;

import java.time.Instant;

public record StoredEligibilityResult(EligibilityBasis basis, EligibilityDecision result, Instant evaluatedAt) {}

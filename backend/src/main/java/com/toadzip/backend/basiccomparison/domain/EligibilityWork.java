package com.toadzip.backend.basiccomparison.domain;

import java.time.Instant;
import java.util.UUID;

public record EligibilityWork(Kind kind, long id, long revision, long cursor, UUID token, Instant leaseUntil,
        boolean retired) {
    public enum Kind { PROFILE, NOTICE }
}

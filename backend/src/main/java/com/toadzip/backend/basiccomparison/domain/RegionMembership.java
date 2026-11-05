package com.toadzip.backend.basiccomparison.domain;

import java.util.Set;

public interface RegionMembership {
    Match match(Set<String> members, String userRegion);

    boolean isKnown(String code);

    enum Match {
        MATCH,
        NO_MATCH,
        INSUFFICIENT_PRECISION
    }
}

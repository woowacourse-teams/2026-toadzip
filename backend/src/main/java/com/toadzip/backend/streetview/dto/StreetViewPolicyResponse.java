package com.toadzip.backend.streetview.dto;

import com.toadzip.backend.streetview.domain.StreetViewPolicy;
import java.time.Instant;

public record StreetViewPolicyResponse(boolean enabled, long version, String reason,
        String updatedBy, Instant updatedAt) {
    public static StreetViewPolicyResponse from(StreetViewPolicy policy) {
        return new StreetViewPolicyResponse(policy.isEnabled(), policy.getVersion(), policy.getChangeReason(),
                policy.getUpdatedBy(), policy.getUpdatedAt());
    }
}

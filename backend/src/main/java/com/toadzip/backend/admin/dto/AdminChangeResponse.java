package com.toadzip.backend.admin.dto;

import com.toadzip.backend.admin.domain.AdminDataChange;
import java.time.Instant;

public record AdminChangeResponse(long id, String action, String actor, Instant occurredAt,
        String beforeValue, String afterValue) {
    public static AdminChangeResponse from(AdminDataChange change) {
        return new AdminChangeResponse(change.getId(), change.getAction(), change.getActor(),
                change.getOccurredAt(), change.getBeforeValue(), change.getAfterValue());
    }
}

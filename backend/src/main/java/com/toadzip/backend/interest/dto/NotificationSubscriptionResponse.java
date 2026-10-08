package com.toadzip.backend.interest.dto;

import com.toadzip.backend.interest.domain.NotificationTargetType;
import java.util.List;

public record NotificationSubscriptionResponse(
        boolean emailConfirmed,
        List<Target> targets
) {
    public record Target(NotificationTargetType targetType, String targetId, String targetName) {
        public Target(NotificationTargetType targetType, String targetId) {
            this(targetType, targetId, null);
        }
    }
}

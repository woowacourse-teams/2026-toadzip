package com.toadzip.backend.interest.domain;

public enum NotificationEventSource {
    SETTING, REGION_SEARCH, COMPLEX_DETAIL, ANNOUNCEMENT_DETAIL;

    public boolean supports(NotificationTargetType targetType) {
        return switch (this) {
            case SETTING -> targetType == NotificationTargetType.REGION || targetType == NotificationTargetType.COMPLEX;
            case REGION_SEARCH -> targetType == NotificationTargetType.REGION;
            case COMPLEX_DETAIL -> targetType == NotificationTargetType.COMPLEX;
            case ANNOUNCEMENT_DETAIL -> targetType == NotificationTargetType.ANNOUNCEMENT;
        };
    }
}

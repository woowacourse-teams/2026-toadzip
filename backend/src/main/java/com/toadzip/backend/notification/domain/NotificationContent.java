package com.toadzip.backend.notification.domain;

public record NotificationContent(String type, String targetType, String targetId, String targetName,
        String message, ScheduleReminder schedule) {
    public static NotificationContent announcement(String targetType, String targetId, String targetName) {
        String message = "관심 단지에 새 공고가 올라왔어요.";
        if ("REGION".equals(targetType)) {
            message = targetName + "에 새 공고가 올라왔어요.";
        }
        return new NotificationContent("ANNOUNCEMENT_CREATED", targetType, targetId, targetName, message, null);
    }
    public static NotificationContent reminder(ScheduleReminder schedule) {
        return new NotificationContent("SCHEDULE_REMINDER", "ANNOUNCEMENT", Long.toString(schedule.announcementId()),
                schedule.name(), schedule.message(), schedule);
    }
}

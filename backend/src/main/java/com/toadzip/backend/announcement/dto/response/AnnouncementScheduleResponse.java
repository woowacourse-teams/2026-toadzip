package com.toadzip.backend.announcement.dto.response;

import com.toadzip.backend.announcement.domain.ScheduleType;
import java.time.LocalDateTime;

public record AnnouncementScheduleResponse(
        long scheduleId,
        ScheduleType type,
        String name,
        LocalDateTime startAt,
        LocalDateTime endAt
) {
}

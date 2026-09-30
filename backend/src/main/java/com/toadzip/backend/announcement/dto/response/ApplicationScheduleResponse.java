package com.toadzip.backend.announcement.dto.response;

import com.toadzip.backend.announcement.domain.ApplicationScheduleState;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.LocalTime;

public record ApplicationScheduleResponse(
        long scheduleId,
        @Schema(description = "null이면 공고의 모든 단지에 적용") Long housingComplexId,
        String complexName,
        String supplyRank,
        @Schema(description = "CONFIRMED 확정 접수, CONDITIONAL 진행 조건이 있는 접수") ApplicationScheduleState state,
        String condition,
        LocalDate startDate,
        LocalDate endDate,
        @Schema(description = "공식 공고문에 시각이 없으면 null. 시간대는 Asia/Seoul") LocalTime startTime,
        LocalTime endTime,
        String sourceUrl,
        int sourcePage
) {
}

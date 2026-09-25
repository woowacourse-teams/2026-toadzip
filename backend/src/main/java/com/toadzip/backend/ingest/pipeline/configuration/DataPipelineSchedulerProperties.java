package com.toadzip.backend.ingest.pipeline.configuration;

import java.time.Duration;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ingest.scheduler")
public record DataPipelineSchedulerProperties(
        boolean enabled,
        long pollIntervalMillis,
        Duration announcementInterval,
        String zone,
        DayOfWeek complexWeeklyDay,
        LocalTime complexWeeklyTime
) {

    public DataPipelineSchedulerProperties {
        if (pollIntervalMillis <= 0) {
            throw new IllegalArgumentException("스케줄러 확인 주기는 0보다 커야 합니다.");
        }
        if (announcementInterval == null || announcementInterval.isNegative()
                || announcementInterval.isZero()
                || announcementInterval.compareTo(Duration.ofDays(1)) > 0
                || announcementInterval.getNano() != 0
                || Duration.ofDays(1).getSeconds() % announcementInterval.getSeconds() != 0) {
            throw new IllegalArgumentException("공고 스케줄 주기는 하루를 나누는 양수여야 합니다.");
        }
        Objects.requireNonNull(zone, "스케줄러 시간대는 필수입니다.");
        Objects.requireNonNull(complexWeeklyDay, "단지 주간 실행 요일은 필수입니다.");
        Objects.requireNonNull(complexWeeklyTime, "단지 주간 실행 시각은 필수입니다.");
        ZoneId.of(zone);
    }
}

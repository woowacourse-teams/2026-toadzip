package com.toadzip.backend.ingest.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.ingest.pipeline.configuration.DataPipelineSchedulerProperties;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineSchedule;
import java.time.Duration;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class DataPipelineScheduleSlotPolicyTest {

    private final DataPipelineScheduleSlotPolicy policy = new DataPipelineScheduleSlotPolicy(
            new DataPipelineSchedulerProperties(
                    false,
                    60_000,
                    Duration.ofHours(6),
                    "Asia/Seoul",
                    DayOfWeek.MONDAY,
                    LocalTime.of(3, 0)
            )
    );

    @Test
    void 공고_스케줄은_서울_시간_기준_6시간_슬롯으로_계산한다() {
        Instant now = Instant.parse("2026-09-21T05:15:00Z");

        assertThat(policy.currentSlot(DataPipelineSchedule.ANNOUNCEMENT, now))
                .isEqualTo(Instant.parse("2026-09-21T03:00:00Z"));
        assertThat(policy.previousSlot(
                DataPipelineSchedule.ANNOUNCEMENT,
                Instant.parse("2026-09-21T03:00:00Z")
        )).isEqualTo(Instant.parse("2026-09-20T21:00:00Z"));
    }

    @Test
    void 단지_스케줄은_주간_실행_시각_이전이면_직전_주간_슬롯을_사용한다() {
        Instant beforeWeeklyRun = Instant.parse("2026-09-20T17:00:00Z");

        assertThat(policy.currentSlot(DataPipelineSchedule.COMPLEX, beforeWeeklyRun))
                .isEqualTo(Instant.parse("2026-09-13T18:00:00Z"));
    }
}

package com.toadzip.backend.ingest.pipeline.service;

import com.toadzip.backend.ingest.pipeline.configuration.DataPipelineSchedulerProperties;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineSchedule;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import org.springframework.stereotype.Component;

@Component
public class DataPipelineScheduleSlotPolicy {

    private static final Duration COMPLEX_INTERVAL = Duration.ofDays(7);

    private final DataPipelineSchedulerProperties properties;
    private final ZoneId zoneId;

    public DataPipelineScheduleSlotPolicy(DataPipelineSchedulerProperties properties) {
        this.properties = properties;
        this.zoneId = ZoneId.of(properties.zone());
    }

    public Instant currentSlot(DataPipelineSchedule schedule, Instant now) {
        if (schedule == DataPipelineSchedule.ANNOUNCEMENT) {
            return announcementSlot(now);
        }
        return complexSlot(now);
    }

    public Instant previousSlot(DataPipelineSchedule schedule, Instant slot) {
        if (schedule == DataPipelineSchedule.ANNOUNCEMENT) {
            return slot.minus(properties.announcementInterval());
        }
        return slot.minus(COMPLEX_INTERVAL);
    }

    private Instant announcementSlot(Instant now) {
        ZonedDateTime localNow = now.atZone(zoneId);
        ZonedDateTime dayStart = localNow.toLocalDate().atStartOfDay(zoneId);
        long elapsedSeconds = Duration.between(dayStart.toInstant(), now).toSeconds();
        long intervalSeconds = properties.announcementInterval().toSeconds();
        long slotOffsetSeconds = elapsedSeconds / intervalSeconds * intervalSeconds;
        return dayStart.plusSeconds(slotOffsetSeconds).toInstant();
    }

    private Instant complexSlot(Instant now) {
        ZonedDateTime localNow = now.atZone(zoneId);
        LocalDate candidateDate = localNow.toLocalDate()
                .with(TemporalAdjusters.previousOrSame(properties.complexWeeklyDay()));
        ZonedDateTime candidate = candidateDate
                .atTime(properties.complexWeeklyTime())
                .atZone(zoneId);
        if (localNow.isBefore(candidate)) {
            return candidate.minus(COMPLEX_INTERVAL).toInstant();
        }
        return candidate.toInstant();
    }
}

package com.toadzip.backend.ingest.pipeline.dto;

import com.toadzip.backend.ingest.pipeline.domain.DataPipelineSchedule;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineScheduleDeferral;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineScheduleDeferralReason;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineScheduleStage;
import java.time.Instant;

public record DataPipelineScheduleDeferralResponse(
        DataPipelineSchedule schedule,
        DataPipelineScheduleStage stage,
        Instant scheduledAt,
        DataPipelineScheduleDeferralReason reason,
        String detail,
        Instant observedAt,
        Instant nextRetryAt,
        Instant resolvedAt,
        boolean active
) {

    public static DataPipelineScheduleDeferralResponse from(
            DataPipelineScheduleDeferral deferral
    ) {
        return new DataPipelineScheduleDeferralResponse(
                deferral.getSchedule(),
                deferral.getStage(),
                deferral.getScheduledAt(),
                deferral.getReason(),
                deferral.getDetail(),
                deferral.getObservedAt(),
                deferral.getNextRetryAt(),
                deferral.getResolvedAt(),
                deferral.isActive()
        );
    }
}

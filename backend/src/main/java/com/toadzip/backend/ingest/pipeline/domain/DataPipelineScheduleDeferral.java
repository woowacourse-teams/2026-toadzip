package com.toadzip.backend.ingest.pipeline.domain;

import static lombok.AccessLevel.PROTECTED;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "data_pipeline_schedule_deferrals")
@NoArgsConstructor(access = PROTECTED)
public class DataPipelineScheduleDeferral {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private DataPipelineSchedule schedule;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DataPipelineScheduleStage stage;

    @Column(nullable = false)
    private Instant scheduledAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private DataPipelineScheduleDeferralReason reason;

    @Column(length = 40)
    private String detail;

    @Column(nullable = false)
    private Instant observedAt;

    private Instant nextRetryAt;

    private Instant resolvedAt;

    public boolean isActive() {
        return resolvedAt == null;
    }
}

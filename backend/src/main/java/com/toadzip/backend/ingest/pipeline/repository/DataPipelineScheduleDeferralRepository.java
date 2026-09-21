package com.toadzip.backend.ingest.pipeline.repository;

import com.toadzip.backend.ingest.pipeline.domain.DataPipelineSchedule;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineScheduleDeferral;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DataPipelineScheduleDeferralRepository
        extends JpaRepository<DataPipelineScheduleDeferral, DataPipelineSchedule> {

    List<DataPipelineScheduleDeferral> findAllByOrderByScheduleAsc();

    @Modifying
    @Query(value = """
            insert into data_pipeline_schedule_deferrals (
                schedule,
                stage,
                scheduled_at,
                reason,
                detail,
                observed_at,
                next_retry_at,
                resolved_at
            ) values (
                :schedule,
                :stage,
                :scheduledAt,
                :reason,
                :detail,
                :observedAt,
                :nextRetryAt,
                null
            )
            on conflict (schedule) do update set
                stage = excluded.stage,
                scheduled_at = excluded.scheduled_at,
                reason = excluded.reason,
                detail = excluded.detail,
                observed_at = excluded.observed_at,
                next_retry_at = excluded.next_retry_at,
                resolved_at = null
            """, nativeQuery = true)
    int upsert(
            @Param("schedule") String schedule,
            @Param("stage") String stage,
            @Param("scheduledAt") Instant scheduledAt,
            @Param("reason") String reason,
            @Param("detail") String detail,
            @Param("observedAt") Instant observedAt,
            @Param("nextRetryAt") Instant nextRetryAt
    );

    @Modifying
    @Query("""
            update DataPipelineScheduleDeferral deferral
            set deferral.resolvedAt = :resolvedAt
            where deferral.schedule = :schedule
              and deferral.resolvedAt is null
            """)
    int resolve(
            @Param("schedule") DataPipelineSchedule schedule,
            @Param("resolvedAt") Instant resolvedAt
    );
}

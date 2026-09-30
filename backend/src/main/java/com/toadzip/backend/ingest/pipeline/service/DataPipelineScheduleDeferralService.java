package com.toadzip.backend.ingest.pipeline.service;

import com.toadzip.backend.ingest.pipeline.domain.DataPipelineSchedule;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineScheduleDeferralReason;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineScheduleStage;
import com.toadzip.backend.ingest.pipeline.dto.DataPipelineScheduleDeferralResponse;
import com.toadzip.backend.ingest.pipeline.repository.DataPipelineScheduleDeferralRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DataPipelineScheduleDeferralService {

    private final DataPipelineScheduleDeferralRepository deferralRepository;

    public DataPipelineScheduleDeferralService(
            DataPipelineScheduleDeferralRepository deferralRepository
    ) {
        this.deferralRepository = deferralRepository;
    }

    @Transactional
    public void defer(
            DataPipelineSchedule schedule,
            DataPipelineScheduleStage stage,
            Instant scheduledAt,
            DataPipelineScheduleDeferralReason reason,
            String detail,
            Instant observedAt,
            Instant nextRetryAt
    ) {
        deferralRepository.upsert(
                schedule.name(),
                stage.name(),
                scheduledAt,
                reason.name(),
                detail,
                observedAt,
                nextRetryAt
        );
    }

    @Transactional
    public void resolve(DataPipelineSchedule schedule, Instant resolvedAt) {
        deferralRepository.resolve(schedule, resolvedAt);
    }

    @Transactional(readOnly = true)
    public List<DataPipelineScheduleDeferralResponse> findAll() {
        return deferralRepository.findAllByOrderByScheduleAsc()
                .stream()
                .map(DataPipelineScheduleDeferralResponse::from)
                .toList();
    }
}

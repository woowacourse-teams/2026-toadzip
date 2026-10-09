package com.toadzip.backend.ingest.pipeline.service;

import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.collection.lh.domain.LhProviderPolicy;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementExternalCollectionService;
import com.toadzip.backend.ingest.collection.myhome.announcement.repository.MyHomeAnnouncementSourceReader;
import com.toadzip.backend.ingest.collection.myhome.announcement.service.MyHomeAnnouncementCollector;
import com.toadzip.backend.ingest.collection.service.ExternalDataRetryExecutor;
import com.toadzip.backend.ingest.exception.exception.AnnouncementRegistrationException;
import com.toadzip.backend.ingest.exception.exception.ExternalDataCallFailureException;
import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
import com.toadzip.backend.ingest.failure.service.IngestExecutionContext;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementMappingService;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineStep;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AnnouncementRegistrationService {

    private final AnnouncementRepository announcements;
    private final MyHomeAnnouncementCollector collector;
    private final ExternalDataRetryExecutor retry;
    private final MyHomeAnnouncementSourceReader sources;
    private final LhAnnouncementExternalCollectionService lhCollection;
    private final MyHomeAnnouncementMappingService mapping;
    private final DataPipelineStepResultAdapter resultAdapter;

    public DataPipelineStepResult execute(DataPipelineStep step, String identifier) {
        return switch (step) {
            case COLLECT_MYHOME_ANNOUNCEMENTS -> collect(identifier);
            case COLLECT_LH_ANNOUNCEMENT_SUPPLIES -> collectLh(identifier, ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY);
            case COLLECT_LH_ANNOUNCEMENT_DETAILS -> collectLh(identifier, ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);
            case MAP_MYHOME_ANNOUNCEMENTS -> resultAdapter.adapt(mapping.registerAnnouncement(identifier));
            default -> throw new IllegalArgumentException("단건 등록에서 지원하지 않는 단계입니다.");
        };
    }

    private DataPipelineStepResult collect(String identifier) {
        if (announcements.findBySourceAnnouncementIdentifier(identifier).isPresent()) {
            throw new AnnouncementRegistrationException("이미 등록된 공고입니다: " + identifier);
        }
        try {
            int count = collector.collectAnnouncement(identifier, retry);
            return new DataPipelineStepResult("{\"storedRowCount\":" + count + "}", 0, 0, 0);
        }
        catch (InvalidIngestRequestException exception) {
            throw new AnnouncementRegistrationException(exception.getMessage(), exception);
        }
        catch (ExternalDataCallFailureException exception) {
            if (exception.isRateLimited()) {
                throw new AnnouncementRegistrationException("외부 API 호출 제한에 도달했습니다. 나중에 재시도해 주세요.",
                        exception);
            }
            throw new AnnouncementRegistrationException("마이홈 외부 API 수집에 실패했습니다.", exception);
        }
    }

    private DataPipelineStepResult collectLh(String identifier, ExternalDataSource target) {
        var executionId = IngestExecutionContext.currentExecutionId().orElse(null);
        boolean hasLhSource = sources.findAllByPblancIdOrderByIdAsc(identifier).stream()
                .filter(source -> executionId == null || executionId.toString().equals(source.getLastSeenRunId()))
                .anyMatch(source -> LhProviderPolicy.isLh(source.getSuplyInsttNm()));
        if (!hasLhSource) {
            return DataPipelineStepResult.notApplicable("해당 없음: LH 외 기관의 공고는 LH 수집을 생략합니다.");
        }
        var report = refreshLh(target, identifier);
        if (report.rateLimitedRequestCount() > 0) {
            throw new AnnouncementRegistrationException("LH 외부 API 호출 제한에 도달했습니다. 나중에 재시도해 주세요.");
        }
        if (report.failedRequestCount() > 0 || report.successfulRequestCount() == 0) {
            throw new AnnouncementRegistrationException("LH 공급·상세 정보 수집에 실패했습니다. 정제는 실행하지 않았습니다.");
        }
        return resultAdapter.adapt(report);
    }

    private ExternalDataCollectionReport refreshLh(
            ExternalDataSource target, String identifier
    ) {
        var executionId = IngestExecutionContext.currentExecutionId().orElse(null);
        if (executionId != null) {
            return lhCollection.refresh(target, identifier, executionId);
        }
        return lhCollection.refresh(target, identifier);
    }
}

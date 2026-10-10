package com.toadzip.backend.ingest.collection.myhome.announcement.service;

import com.toadzip.backend.ingest.collection.history.domain.CollectionSource;
import com.toadzip.backend.ingest.collection.history.domain.CollectionStatus;
import com.toadzip.backend.ingest.collection.history.domain.SourceCollectionRecord;
import com.toadzip.backend.ingest.collection.history.repository.SourceCollectionRecordRepository;
import com.toadzip.backend.ingest.collection.myhome.announcement.repository.MyHomeAnnouncementRowRepository;
import com.toadzip.backend.ingest.collection.myhome.announcement.repository.MyHomeAnnouncementRunRepository;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.MyHomeAnnouncementSourceRow;
import java.time.Instant;
import com.toadzip.backend.ingest.pipeline.service.IngestWriteOwnershipGuard;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MyHomeAnnouncementLifecycleService {

    private static final Set<String> SUPPLY_TYPES = Set.of("01", "02", "03", "05", "06", "10", "12");

    private final SourceCollectionRecordRepository records;

    private final MyHomeAnnouncementRowRepository rows;

    private final MyHomeAnnouncementRunRepository completedRuns;

    private final IngestWriteOwnershipGuard ownership;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeRun(UUID runId) {
        ownership.verifyWrite();
        List<SourceCollectionRecord> run = records.findAllByExecutionIdAndSource(
                runId, CollectionSource.MYHOME_ANNOUNCEMENT);
        Set<String> completedTypes = run.stream().filter(record -> record.getStatus() == CollectionStatus.SUCCESS)
                .map(record -> record.getRequestParameters().get("suplyTy")).collect(Collectors.toSet());
        if (run.size() != SUPPLY_TYPES.size() || !completedTypes.equals(SUPPLY_TYPES)) {
            throw new IllegalStateException("모든 공급유형 수집이 성공한 실행만 미조회 판정을 할 수 있습니다.");
        }
        Instant completedAt = run.stream().map(SourceCollectionRecord::getFinishedAt)
                .max(Instant::compareTo).orElseThrow();
        if (!completedRuns.claim(runId, completedAt)) {
            return;
        }
        Instant startedAt = run.stream().map(SourceCollectionRecord::getStartedAt)
                .min(Instant::compareTo).orElseThrow();
        rows.findActiveNotSeen(runId.toString(), startedAt).forEach(MyHomeAnnouncementSourceRow::markMissed);
    }
}

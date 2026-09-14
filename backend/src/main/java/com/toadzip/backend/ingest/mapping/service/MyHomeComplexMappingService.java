package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.ingest.exception.exception.IngestAlreadyRunningException;
import com.toadzip.backend.ingest.mapping.dto.MyHomeComplexMappingFailureResponse;
import com.toadzip.backend.ingest.mapping.dto.MyHomeComplexMappingPreparationReport;
import com.toadzip.backend.ingest.mapping.dto.MyHomeComplexMappingReport;
import com.toadzip.backend.ingest.mapping.repository.MyHomeComplexMappingExecutionLock;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class MyHomeComplexMappingService {

    private static final int MAX_BATCH_SIZE = 1_000;

    private final MyHomeComplexMappingExecutionLock executionLock;
    private final MyHomeComplexMappingPreparer preparer;
    private final MyHomeComplexMappingBatchProcessor batchProcessor;
    private final MyHomeComplexMappingFailureQuery failureQuery;

    public MyHomeComplexMappingService(
            MyHomeComplexMappingExecutionLock executionLock,
            MyHomeComplexMappingPreparer preparer,
            MyHomeComplexMappingBatchProcessor batchProcessor,
            MyHomeComplexMappingFailureQuery failureQuery
    ) {
        this.executionLock = executionLock;
        this.preparer = preparer;
        this.batchProcessor = batchProcessor;
        this.failureQuery = failureQuery;
    }

    public MyHomeComplexMappingPreparationReport prepare() {
        return executionLock.tryRun(preparer::prepare)
                .orElseThrow(this::alreadyRunning);
    }

    public MyHomeComplexMappingReport mapNext(int batchSize) {
        validateBatchSize(batchSize);
        return executionLock.tryRun(() -> batchProcessor.mapNext(batchSize))
                .orElseThrow(this::alreadyRunning);
    }

    public MyHomeComplexMappingReport mapAll() {
        return executionLock.tryRun(this::mapAllUnlocked)
                .orElseThrow(this::alreadyRunning);
    }

    public List<MyHomeComplexMappingFailureResponse> findFailures() {
        return failureQuery.findAll();
    }

    private MyHomeComplexMappingReport mapAllUnlocked() {
        MyHomeComplexMappingPreparationReport preparation = preparer.prepare();
        MyHomeComplexMappingReport report = MyHomeComplexMappingReport.failedRows(
                preparation.failedSourceRowCount()
        );
        while (batchProcessor.hasProcessableCandidate()) {
            report = report.plus(batchProcessor.mapNext(MAX_BATCH_SIZE));
        }
        return report;
    }

    private void validateBatchSize(int batchSize) {
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("배치 크기는 1 이상 1000 이하여야 합니다.");
        }
    }

    private IngestAlreadyRunningException alreadyRunning() {
        log.warn("마이홈 단지 매핑이 이미 실행 중이므로 중복 실행을 건너뜁니다.");
        return new IngestAlreadyRunningException("마이홈 단지 매핑이 이미 실행 중입니다.");
    }
}

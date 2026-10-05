package com.toadzip.backend.ingest.collection.history.service;

import com.toadzip.backend.ingest.collection.history.domain.CollectionStatus;
import com.toadzip.backend.ingest.collection.history.domain.SourceCollectionRecord;
import com.toadzip.backend.ingest.collection.history.dto.SourceCollectionRequest;
import com.toadzip.backend.ingest.collection.history.repository.SourceCollectionRecordRepository;
import java.time.Clock;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
public class SourceCollectionRecordService {

    private static final Pattern SERVICE_KEY_PATTERN = Pattern.compile("(?i)(serviceKey\\s*=\\s*)[^\\s&,]+");

    private final SourceCollectionRecordRepository repository;
    private final Clock clock;
    private final TransactionTemplate independentTransaction;

    public SourceCollectionRecordService(
            SourceCollectionRecordRepository repository, Clock clock, PlatformTransactionManager transactionManager
    ) {
        this.repository = repository;
        this.clock = clock;
        independentTransaction = new TransactionTemplate(transactionManager);
        independentTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public UUID start(SourceCollectionRequest request) {
        UUID id = UUID.randomUUID();
        independentTransaction.executeWithoutResult(status -> repository.saveAndFlush(SourceCollectionRecord.start(
                id, request.executionId(), request.source(), request.parameters(), request.startedAt())));
        return id;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public SourceCollectionRecord requireRunning(UUID id, SourceCollectionRequest request) {
        SourceCollectionRecord record = recordFor(id, request);
        if (record.getStatus() != CollectionStatus.RUNNING) {
            throw new IllegalStateException("진행 중인 수집 기록만 원천을 저장할 수 있습니다.");
        }
        return record;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(UUID id, SourceCollectionRequest request, int storedRowCount) {
        recordFor(id, request).complete(clock.instant(), storedRowCount);
        repository.flush();
    }

    public void fail(UUID id, SourceCollectionRequest request, RuntimeException failure) {
        try {
            // 소유권을 잃어도 자기 RUNNING 기록은 마감한다. 원천과 완료된 기록은 수정하지 않는다.
            independentTransaction.executeWithoutResult(status -> {
                SourceCollectionRecord record = recordFor(id, request);
                if (record.getStatus() != CollectionStatus.RUNNING) {
                    return;
                }
                record.fail(clock.instant(), failure.getClass().getSimpleName(), failureReason(failure));
                repository.flush();
            });
        } catch (RuntimeException recordFailure) {
            failure.addSuppressed(recordFailure);
            log.error("수집 실패 기록을 저장하지 못했습니다: recordId={}", id, recordFailure);
        }
    }

    @Transactional(readOnly = true)
    public int storedRowCount(UUID id) {
        SourceCollectionRecord record = repository.findById(id).orElseThrow();
        if (record.getStatus() != CollectionStatus.SUCCESS) {
            throw new IllegalStateException("성공한 수집 기록만 저장 행 수를 반환할 수 있습니다.");
        }
        return record.getStoredRowCount();
    }

    private SourceCollectionRecord recordFor(UUID id, SourceCollectionRequest request) {
        SourceCollectionRecord record = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("수집 기록을 찾을 수 없습니다."));
        record.verifyRequest(request.executionId(), request.source(), request.parameters(), request.startedAt());
        return record;
    }

    private String failureReason(RuntimeException failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return "원천 수집에 실패했습니다.";
        }
        String safe = SERVICE_KEY_PATTERN.matcher(message.replaceAll("[\\r\\n]+", " "))
                .replaceAll("$1[REDACTED]");
        return safe.substring(0, Math.min(safe.length(), 1000));
    }
}

package com.toadzip.backend.ingest.correction.service;

import com.toadzip.backend.ingest.correction.dto.IngestCorrectionRequest;
import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
import com.toadzip.backend.ingest.mapping.service.IngestCorrectionProcessor;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionOwnershipService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 보완 입력은 보존하고 최종 데이터만 별도의 트랜잭션에서 원자적으로 저장한다. */
@Service
@RequiredArgsConstructor
public class IngestCorrectionService {
    private final IngestCorrectionStore store;
    private final IngestCorrectionProcessor processor;
    private final IngestExecutionOwnershipService ownership;

    public long correct(String domain, String identifier, IngestCorrectionRequest request, String actor) {
        try (var lease = ownership.acquire()) {
            store.save(domain, identifier, request, actor);
            long id;
            try {
                id = processor.refine(domain, identifier, request, actor);
            }
            catch (InvalidIngestRequestException exception) {
                store.recordOutcome(domain, identifier, exception.getMessage());
                throw exception;
            }
            catch (RuntimeException exception) {
                store.recordOutcome(domain, identifier, "최종 저장에 실패했습니다. 보완값은 보존되어 있습니다.");
                throw exception;
            }
            store.recordOutcome(domain, identifier, null);
            return id;
        }
    }
}

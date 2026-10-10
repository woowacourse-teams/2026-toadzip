package com.toadzip.backend.ingest.collection.lh.leasecatalog.service;

import com.toadzip.backend.ingest.collection.history.domain.SourceCollectionRecord;
import com.toadzip.backend.ingest.collection.history.service.SourceCollectionRecordService;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.domain.LhLeaseCatalogSource;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.dto.LhLeaseCatalogCollectedResponse;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.dto.LhLeaseCatalogCollectionRequest;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.repository.LhLeaseCatalogSourceRepository;
import com.toadzip.backend.ingest.pipeline.service.IngestWriteOwnershipGuard;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LhLeaseCatalogStorageService {

    private final LhLeaseCatalogSourceRepository repository;
    private final SourceCollectionRecordService records;
    private final IngestWriteOwnershipGuard ownershipGuard;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(UUID id, LhLeaseCatalogCollectionRequest request, LhLeaseCatalogCollectedResponse response) {
        ownershipGuard.verifyWrite();
        SourceCollectionRecord record = records.requireRunning(id, request);
        response.validateFor(request);
        if (response.collectedAt().isAfter(clock.instant())) {
            throw new IllegalArgumentException("실제 수집 시각은 저장 실행 시각보다 늦을 수 없습니다.");
        }
        LhLeaseCatalogSource source = repository.findByScopeKey("ALL").orElseGet(LhLeaseCatalogSource::create);
        source.beginReplacement(response.collectedAt(), record);
        // 순번을 다시 넣기 전 기존 행을 삭제한다. 원천·성공 기록과 같은 트랜잭션이다.
        repository.saveAndFlush(source);
        source.addResponseRows(response.rows());
        repository.flush();
        records.complete(id, request, response.rows().size());
    }
}

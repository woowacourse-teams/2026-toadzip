package com.toadzip.backend.ingest.collection.lh.announcementcatalog.service;

import com.toadzip.backend.ingest.collection.history.domain.SourceCollectionRecord;
import com.toadzip.backend.ingest.collection.history.service.SourceCollectionRecordService;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.domain.LhAnnouncementCatalogEntry;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.domain.LhAnnouncementCatalogRow;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.dto.LhAnnouncementCatalogCollectedResponse;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.dto.LhAnnouncementCatalogCollectionRequest;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.repository.LhAnnouncementCatalogEntryRepository;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.repository.LhAnnouncementCatalogWriteLock;
import com.toadzip.backend.ingest.pipeline.service.IngestWriteOwnershipGuard;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LhAnnouncementCatalogStorageService {

    private final LhAnnouncementCatalogEntryRepository sources;

    private final LhAnnouncementCatalogWriteLock writeLock;

    private final SourceCollectionRecordService records;

    private final IngestWriteOwnershipGuard ownership;

    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public StoreResult complete(
            UUID recordId, LhAnnouncementCatalogCollectionRequest request,
            LhAnnouncementCatalogCollectedResponse response
    ) {
        ownership.verifyWrite();
        writeLock.lock();
        SourceCollectionRecord record = records.requireRunning(recordId, request);
        response.validateFor(request);
        if (response.collectedAt().isAfter(clock.instant())
                || sources.existsByCollectedAtAfter(response.collectedAt())) {
            throw new IllegalArgumentException("LH 공고 목록의 수집 시각이 올바르지 않습니다.");
        }
        List<String> keys = response.rows().stream().map(row -> row.snapshot().sourceKey()).toList();
        sources.markAbsent(keys);
        var stored = sources.findAllBySourceKeyIn(keys).stream()
                .collect(Collectors.toMap(LhAnnouncementCatalogEntry::getSourceKey, Function.identity()));
        int created = 0;
        int changed = 0;
        for (LhAnnouncementCatalogRow row : response.rows()) {
            String key = row.snapshot().sourceKey();
            LhAnnouncementCatalogEntry source = stored.get(key);
            if (source == null) {
                source = LhAnnouncementCatalogEntry.create(key);
                created++;
            }
            if (source.replace(row, response.startDate(), response.endDate(), response.collectedAt(), record)
                    && stored.containsKey(key)) {
                changed++;
            }
            sources.save(source);
        }
        sources.flush();
        records.complete(recordId, request, response.rows().size());
        return new StoreResult(response.rows().size(), created, changed);
    }

    public record StoreResult(int storedRowCount, int newRowCount, int changedRowCount) {

        public int unchangedRowCount() {
            return storedRowCount - newRowCount - changedRowCount;
        }
    }
}

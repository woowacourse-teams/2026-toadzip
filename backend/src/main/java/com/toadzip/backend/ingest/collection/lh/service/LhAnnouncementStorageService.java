package com.toadzip.backend.ingest.collection.lh.service;

import com.toadzip.backend.ingest.collection.history.domain.CollectionSource;
import com.toadzip.backend.ingest.collection.history.domain.SourceCollectionRecord;
import com.toadzip.backend.ingest.collection.history.service.SourceCollectionRecordService;
import com.toadzip.backend.ingest.collection.lh.detail.domain.LhAnnouncementDetailRow;
import com.toadzip.backend.ingest.collection.lh.detail.domain.LhAnnouncementDetailSourceSnapshot;
import com.toadzip.backend.ingest.collection.lh.detail.repository.LhAnnouncementDetailRowRepository;
import com.toadzip.backend.ingest.collection.lh.domain.LhAnnouncementQuerySource;
import com.toadzip.backend.ingest.collection.lh.dto.LhAnnouncementCollectionRequest;
import com.toadzip.backend.ingest.collection.lh.repository.LhAnnouncementQuerySourceRepository;
import com.toadzip.backend.ingest.collection.lh.supply.domain.LhAnnouncementSupplyRow;
import com.toadzip.backend.ingest.collection.lh.supply.domain.LhAnnouncementSupplySource;
import com.toadzip.backend.ingest.collection.lh.supply.domain.LhAnnouncementSupplySourceSnapshot;
import com.toadzip.backend.ingest.collection.lh.supply.domain.LhSupplySnapshot;
import com.toadzip.backend.ingest.collection.lh.supply.repository.LhAnnouncementSupplyRowRepository;
import com.toadzip.backend.ingest.collection.lh.supply.repository.VerifiedLhSupplyReplacementStore;
import com.toadzip.backend.ingest.exception.exception.EmptyLhDetailReplacementException;
import com.toadzip.backend.ingest.exception.exception.EmptyLhSupplyReplacementException;
import com.toadzip.backend.ingest.exception.exception.IncompleteLhSupplyReplacementException;
import com.toadzip.backend.ingest.pipeline.service.IngestWriteOwnershipGuard;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LhAnnouncementStorageService {

    private final LhAnnouncementQuerySourceRepository sources;

    private final LhAnnouncementSupplyRowRepository supplies;

    private final LhAnnouncementDetailRowRepository details;

    private final SourceCollectionRecordService records;

    private final VerifiedLhSupplyReplacementStore approvals;


    private final IngestWriteOwnershipGuard ownership;

    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int completeSupply(
            UUID id, LhAnnouncementCollectionRequest request, Instant collectedAt,
            List<LhAnnouncementSupplySourceSnapshot> snapshots
    ) {
        requireTime(request, collectedAt);
        requireSource(request, CollectionSource.LH_ANNOUNCEMENT_SUPPLY);
        ownership.verifyWrite();
        SourceCollectionRecord record = records.requireRunning(id, request);
        List<LhAnnouncementSupplySourceSnapshot> rows = List.copyOf(snapshots);
        rows.forEach(LhAnnouncementSupplySourceSnapshot::validateIdentity);
        LhAnnouncementQuerySource source = findSource(request);
        boolean verifiedEmpty = requireCompleteSupply(source, request, rows);
        source.replace(request.source(), request.query(), request.requestHash(), request.description(),
                request.parameters(), collectedAt.truncatedTo(ChronoUnit.MICROS), verifiedEmpty, record);
        sources.saveAndFlush(source);
        supplies.deleteRows(source.getId());
        List<LhAnnouncementSupplyRow> replacements = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            replacements.add(LhAnnouncementSupplyRow.from(source, index, rows.get(index)));
        }
        supplies.saveAllAndFlush(replacements);
        records.complete(id, request, rows.size());
        return rows.size();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int completeDetail(
            UUID id, LhAnnouncementCollectionRequest request, Instant collectedAt,
            List<LhAnnouncementDetailSourceSnapshot> snapshots
    ) {
        requireTime(request, collectedAt);
        requireSource(request, CollectionSource.LH_ANNOUNCEMENT_DETAIL);
        ownership.verifyWrite();
        SourceCollectionRecord record = records.requireRunning(id, request);
        List<LhAnnouncementDetailSourceSnapshot> rows = List.copyOf(snapshots);
        rows.forEach(LhAnnouncementDetailSourceSnapshot::validateContent);
        LhAnnouncementQuerySource source = findSource(request);
        boolean hasPrevious = source.getId() != null
                && !details.findAllBySourceIdOrderBySourceOrderAsc(source.getId()).isEmpty();
        if (rows.isEmpty() && hasPrevious) {
            throw new EmptyLhDetailReplacementException();
        }
        source.replace(request.source(), request.query(), request.requestHash(), request.description(),
                request.parameters(), collectedAt.truncatedTo(ChronoUnit.MICROS), false, record);
        sources.saveAndFlush(source);
        details.deleteRows(source.getId());
        List<LhAnnouncementDetailRow> replacements = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            replacements.add(LhAnnouncementDetailRow.from(source, index, rows.get(index)));
        }
        details.saveAllAndFlush(replacements);
        records.complete(id, request, rows.size());
        return rows.size();
    }

    private LhAnnouncementQuerySource findSource(LhAnnouncementCollectionRequest request) {
        return sources.findBySourceAndQueryHash(request.source(), request.query().identityHash())
                .orElseGet(() -> LhAnnouncementQuerySource.create(request.source(), request.query()));
    }

    private boolean requireCompleteSupply(
            LhAnnouncementQuerySource source, LhAnnouncementCollectionRequest request,
            List<LhAnnouncementSupplySourceSnapshot> rows
    ) {
        List<LhAnnouncementSupplySource> previous = List.of();
        if (source.getId() != null) {
            previous = supplies.findAllBySourceIdOrderBySourceOrderAsc(source.getId()).stream()
                    .map(row -> LhAnnouncementSupplySource.read(row.getId(), row.getSourceOrder(),
                            source.getPanId(), source.getRequestHash(), source.getCollectedAt(), row.snapshot()))
                    .toList();
        }
        boolean previousVerifiedEmpty = source.isVerifiedEmpty();
        List<LhAnnouncementSupplySource> current = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            current.add(LhAnnouncementSupplySource.read(null, index, request.query().panId(),
                    request.requestHash(), null, rows.get(index)));
        }
        long missing = LhSupplySnapshot.missingRowCount(previous, current);
        if (missing == 0) {
            return rows.isEmpty() && previousVerifiedEmpty;
        }
        String fingerprint = LhSupplySnapshot.fingerprint(current);
        if (approvals.consume(request.requestHash(), fingerprint)) {
            return rows.isEmpty();
        }
        if (rows.isEmpty()) {
            throw new EmptyLhSupplyReplacementException();
        }
        throw new IncompleteLhSupplyReplacementException(missing, fingerprint);
    }

    private void requireTime(LhAnnouncementCollectionRequest request, Instant collectedAt) {
        if (collectedAt == null || collectedAt.isBefore(request.startedAt()) || collectedAt.isAfter(clock.instant())) {
            throw new IllegalArgumentException("LH 응답의 실제 수집 시각이 올바르지 않습니다.");
        }
    }

    private void requireSource(LhAnnouncementCollectionRequest request, CollectionSource expected) {
        if (request.source() != expected) {
            throw new IllegalArgumentException("LH 저장 대상과 수집 원천이 다릅니다.");
        }
    }
}

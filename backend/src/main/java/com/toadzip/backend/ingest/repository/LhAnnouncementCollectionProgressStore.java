package com.toadzip.backend.ingest.repository;

import java.time.Clock;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.toadzip.backend.ingest.domain.ExternalDataSource;
import com.toadzip.backend.ingest.domain.LhAnnouncementCollectionCheckpoint;

@Repository
public class LhAnnouncementCollectionProgressStore {

    private final LhAnnouncementCollectionCheckpointRepository checkpointRepository;
    private final LhAnnouncementDetailSourceRepository detailRepository;
    private final LhAnnouncementSupplySourceRepository supplyRepository;
    private final Clock clock;

    public LhAnnouncementCollectionProgressStore(
            LhAnnouncementCollectionCheckpointRepository checkpointRepository,
            LhAnnouncementDetailSourceRepository detailRepository,
            LhAnnouncementSupplySourceRepository supplyRepository,
            Clock clock
    ) {
        this.checkpointRepository = checkpointRepository;
        this.detailRepository = detailRepository;
        this.supplyRepository = supplyRepository;
        this.clock = clock;
    }

    public boolean isCompleted(
            ExternalDataSource source,
            String requestDescription
    ) {
        return checkpointRepository.existsBySourceAndRequestHash(
                source,
                LhAnnouncementCollectionCheckpoint.requestHashOf(requestDescription)
        );
    }

    public boolean hasStoredRows(ExternalDataSource source, String panId) {
        if (source == ExternalDataSource.LH_ANNOUNCEMENT_DETAIL) {
            return detailRepository.existsByPanId(panId);
        }
        if (source == ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY) {
            return supplyRepository.existsByPanId(panId);
        }
        throw new IllegalArgumentException("LH 공고 상세·공급 원천만 확인할 수 있습니다.");
    }

    public boolean hasCollectionHistory(ExternalDataSource source, String panId) {
        return checkpointRepository.existsBySourceAndPanId(source, panId);
    }

    @Transactional
    public void complete(
            ExternalDataSource source,
            String sourceAnnouncementKey,
            String requestDescription,
            String panId
    ) {
        if (isCompleted(source, requestDescription)) {
            return;
        }
        checkpointRepository.save(LhAnnouncementCollectionCheckpoint.complete(
                source,
                sourceAnnouncementKey,
                requestDescription,
                panId,
                clock.instant()
        ));
    }
}

package com.toadzip.backend.ingest.collection.lh.supply.repository;

import com.toadzip.backend.ingest.collection.history.domain.CollectionSource;
import com.toadzip.backend.ingest.collection.lh.domain.LhAnnouncementQuery;
import com.toadzip.backend.ingest.collection.lh.repository.LhAnnouncementQuerySourceReader;
import com.toadzip.backend.ingest.collection.lh.supply.domain.LhAnnouncementSupplySource;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LhAnnouncementSupplySourceReader {

    private final LhAnnouncementQuerySourceReader sources;
    private final LhAnnouncementSupplyRowRepository rows;

    public List<LhAnnouncementSupplySource> findAllByPanIdAndRequestHashOrderBySourceOrderAsc(
            String panId, String requestHash
    ) {
        var source = sources.find(CollectionSource.LH_ANNOUNCEMENT_SUPPLY, panId, requestHash);
        if (source.isEmpty()) {
            return List.of();
        }
        var current = source.orElseThrow();
        if (!current.getRequestHash().equals(requestHash)) {
            return List.of();
        }
        return rows.findAllBySourceIdOrderBySourceOrderAsc(current.getId()).stream()
                .map(row -> LhAnnouncementSupplySource.read(row.getId(), row.getSourceOrder(), panId,
                        requestHash, row.getCollectedAt(), row.snapshot())).toList();
    }
    public boolean hasVerifiedEmptySupplies(String panId, String description) {
        String hash = LhAnnouncementQuery
                .requestHashOf(description);
        var source = sources.find(CollectionSource.LH_ANNOUNCEMENT_SUPPLY, panId, hash);
        if (source.isPresent()) {
            return source.orElseThrow().getRequestHash().equals(hash) && source.orElseThrow().isVerifiedEmpty();
        }
        return false;
    }

}

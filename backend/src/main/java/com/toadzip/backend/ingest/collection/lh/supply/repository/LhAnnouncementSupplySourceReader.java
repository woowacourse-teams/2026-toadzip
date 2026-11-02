package com.toadzip.backend.ingest.collection.lh.supply.repository;

import com.toadzip.backend.ingest.collection.history.domain.CollectionSource;
import com.toadzip.backend.ingest.collection.lh.repository.LhAnnouncementQuerySourceRepository;
import com.toadzip.backend.ingest.collection.lh.supply.domain.LhAnnouncementSupplySourceView;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LhAnnouncementSupplySourceReader {

    private final LhAnnouncementQuerySourceRepository sources;
    private final LhAnnouncementSupplyRowRepository rows;

    public List<LhAnnouncementSupplySourceView> findAllByPanIdAndRequestHashOrderBySourceOrderAsc(
            String panId, String requestHash
    ) {
        var source = sources.findBySourceAndPanIdAndRequestHash(
                CollectionSource.LH_ANNOUNCEMENT_SUPPLY, panId, requestHash);
        if (source.isEmpty()) {
            return List.of();
        }
        var current = source.orElseThrow();
        return rows.findAllBySourceIdOrderBySourceOrderAsc(current.getId()).stream()
                .map(row -> LhAnnouncementSupplySourceView.of(row, current)).toList();
    }

}

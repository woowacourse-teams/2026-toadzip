package com.toadzip.backend.ingest.collection.lh.detail.repository;

import com.toadzip.backend.ingest.collection.history.domain.CollectionSource;
import com.toadzip.backend.ingest.collection.lh.detail.domain.LhAnnouncementDetailSourceView;
import com.toadzip.backend.ingest.collection.lh.repository.LhAnnouncementQuerySourceRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LhAnnouncementDetailSourceReader {

    private final LhAnnouncementQuerySourceRepository sources;
    private final LhAnnouncementDetailRowRepository rows;

    public List<LhAnnouncementDetailSourceView> findAllByPanIdAndRequestHashOrderBySourceOrderAsc(
            String panId, String requestHash
    ) {
        var source = sources.findBySourceAndPanIdAndRequestHash(
                CollectionSource.LH_ANNOUNCEMENT_DETAIL, panId, requestHash);
        if (source.isEmpty()) {
            return List.of();
        }
        var current = source.orElseThrow();
        return rows.findAllBySourceIdOrderBySourceOrderAsc(current.getId()).stream()
                .map(row -> LhAnnouncementDetailSourceView.of(row, current)).toList();
    }
}

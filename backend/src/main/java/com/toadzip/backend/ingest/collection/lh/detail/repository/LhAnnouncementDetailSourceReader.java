package com.toadzip.backend.ingest.collection.lh.detail.repository;

import com.toadzip.backend.ingest.collection.history.domain.CollectionSource;
import com.toadzip.backend.ingest.collection.lh.detail.domain.LhAnnouncementDetailSource;
import com.toadzip.backend.ingest.collection.lh.repository.LhAnnouncementQuerySourceReader;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LhAnnouncementDetailSourceReader {

    private final LhAnnouncementQuerySourceReader sources;
    private final LhAnnouncementDetailRowRepository rows;

    public List<LhAnnouncementDetailSource> findAllByPanIdAndRequestHashOrderBySourceOrderAsc(
            String panId, String requestHash
    ) {
        var source = sources.find(CollectionSource.LH_ANNOUNCEMENT_DETAIL, panId, requestHash);
        if (source.isEmpty()) {
            return List.of();
        }
        var current = source.orElseThrow();
        if (!current.getRequestHash().equals(requestHash)) {
            return List.of();
        }
        return rows.findAllBySourceIdOrderBySourceOrderAsc(current.getId()).stream()
                .map(row -> LhAnnouncementDetailSource.read(row.getId(), row.getSourceOrder(), panId,
                        requestHash, row.getCollectedAt(), row.snapshot())).toList();
    }
}

package com.toadzip.backend.ingest.collection.fixture.repository;

import com.toadzip.backend.ingest.collection.fixture.dto.LhAnnouncementCatalogPage.Entry;
import com.toadzip.backend.ingest.collection.history.service.SourceCollectionRecordService;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.domain.LhAnnouncementCatalogRow;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.domain.LhAnnouncementCatalogSnapshot;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.dto.LhAnnouncementCatalogCollectedResponse;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.dto.LhAnnouncementCatalogCollectionRequest;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.service.LhAnnouncementCatalogStorageService;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
@RequiredArgsConstructor
@Profile("test")
public class LhCatalogStorageFixtures {

    private final LhAnnouncementCatalogStorageService storage;
    private final SourceCollectionRecordService records;
    private final Clock clock;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public StoreResult store(List<Entry> entries) {
        var at = clock.instant();
        var request = new LhAnnouncementCatalogCollectionRequest(null, 9999, 1000, at);
        var rows = entries.stream().map(entry -> new LhAnnouncementCatalogRow(
                mapper.convertValue(entry.snapshot(), LhAnnouncementCatalogSnapshot.class),
                entry.rawPayload())).toList();
        var result = storage.complete(records.start(request), request,
                new LhAnnouncementCatalogCollectedResponse(rows.size(), "20200101", "20301231", at, rows));
        return new StoreResult(result.storedRowCount(), result.newRowCount(), result.changedRowCount());
    }

    public record StoreResult(int storedRowCount, int newRowCount, int changedRowCount) {
        public int unchangedRowCount() {
            return storedRowCount - newRowCount - changedRowCount;
        }
    }
}

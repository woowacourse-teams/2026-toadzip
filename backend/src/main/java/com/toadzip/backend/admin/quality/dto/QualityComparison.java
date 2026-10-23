package com.toadzip.backend.admin.quality.dto;

import com.toadzip.backend.ingest.source.dto.IngestSourceCategory;
import com.toadzip.backend.ingest.source.dto.IngestSourcePageResponse.Item;
import java.util.List;

public record QualityComparison(String snapshotToken, String target, Object detail, List<Source> sources) {
    public record Source(IngestSourceCategory category, String sourceKey, boolean automatic,
            String evidenceNote, List<Item> rows) { }
}

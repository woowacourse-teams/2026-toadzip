package com.toadzip.backend.ingest.collection.history.dto;

import com.toadzip.backend.ingest.collection.history.domain.CollectionSource;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public interface SourceCollectionRequest {

    UUID executionId();

    CollectionSource source();

    Map<String, String> parameters();

    Instant startedAt();
}

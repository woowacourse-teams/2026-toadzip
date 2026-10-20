package com.toadzip.backend.admin.quality.dto;

import java.time.Instant;
import java.util.List;

public record QualityQueueItem(String resource, long id, String name, List<String> reasons,
        List<String> missingFields, List<String> sourceChanges, Instant updatedAt) { }

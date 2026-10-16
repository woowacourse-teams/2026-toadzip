package com.toadzip.backend.admin.quality.dto;

import java.time.Instant;

public record ReviewDocument(String sha256, String fileName, String sourceUrl, long byteCount,
        String actor, Instant createdAt) { }

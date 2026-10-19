package com.toadzip.backend.admin.quality.dto;

import java.time.Instant;

public record SharedReviewDraft(String target, long revision, long documentVersion, String sha256,
        String sourceUrl, String pages, String resultJson, String note, String actor, Instant updatedAt) { }

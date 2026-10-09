package com.toadzip.backend.privacy.dto;

import java.time.Instant;

public record PrivacyChoiceResponse(Receipt receipt, AnalyticsContextResponse current) {

    public record Receipt(String commandId, String decision, long revision, Instant recordedAt) {
    }
}

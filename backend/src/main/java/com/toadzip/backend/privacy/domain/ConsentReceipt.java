package com.toadzip.backend.privacy.domain;

import java.time.Instant;
import java.util.UUID;

public record ConsentReceipt(UUID commandId, String fingerprint, ConsentDecision decision,
        long revision, Instant recordedAt) {
}

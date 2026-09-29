package com.toadzip.backend.ingest.quality.service;

import com.toadzip.backend.ingest.quality.dto.LhAnnouncementQualityResponse;
import com.toadzip.backend.ingest.quality.repository.LhAnnouncementQualityStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LhAnnouncementQualityService {

    private final LhAnnouncementQualityStore store;
    private final Clock clock;
    private final Duration refreshTtl;

    public LhAnnouncementQualityService(LhAnnouncementQualityStore store, Clock clock,
            @Value("${ingest.lh-announcement-refresh-ttl}") Duration refreshTtl) {
        this.store = store;
        this.clock = clock;
        this.refreshTtl = refreshTtl;
    }

    @Transactional(readOnly = true)
    public LhAnnouncementQualityResponse snapshot() {
        Instant now = clock.instant();
        return store.snapshot(now, now.minus(refreshTtl));
    }
}

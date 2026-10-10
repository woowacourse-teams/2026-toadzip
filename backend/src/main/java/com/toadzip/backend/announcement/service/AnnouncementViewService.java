package com.toadzip.backend.announcement.service;

import com.toadzip.backend.announcement.exception.AnnouncementNotFoundException;
import com.toadzip.backend.announcement.repository.AnnouncementViewRepository;
import com.toadzip.backend.privacy.domain.AnalyticsCollectionPolicy;
import com.toadzip.backend.privacy.domain.AnalyticsConsent;
import com.toadzip.backend.privacy.repository.AnalyticsConsentRepository;
import com.toadzip.backend.privacy.repository.PrivacyNoticeCatalog;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnnouncementViewService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final AnnouncementViewRepository repository;
    private final Clock clock;
    private final AnalyticsConsentRepository consents;
    private final PrivacyNoticeCatalog notices;
    private final AnalyticsCollectionPolicy collectionPolicy;

    public AnnouncementViewService(AnnouncementViewRepository repository, Clock clock,
            AnalyticsConsentRepository consents, PrivacyNoticeCatalog notices, AnalyticsCollectionPolicy collectionPolicy) {
        this.repository = repository;
        this.clock = clock;
        this.consents = consents;
        this.notices = notices;
        this.collectionPolicy = collectionPolicy;
    }

    @Transactional
    public long recordView(long announcementId, UUID viewerId, Long userId, String guestToken) {
        AnalyticsConsent consent = consents.lockForCollection(userId, guestToken);
        Instant now = clock.instant();
        collectionPolicy.requireAllowed(consent, notices.requiredAnalyticsScope(), now);
        long current = repository.lockViewCount(announcementId).orElseThrow(AnnouncementNotFoundException::new);
        LocalDate today = now.atZone(SEOUL).toLocalDate();
        if (!repository.recordOncePerDay(announcementId, viewerId, today)) {
            return current;
        }
        return repository.increment(announcementId);
    }
}

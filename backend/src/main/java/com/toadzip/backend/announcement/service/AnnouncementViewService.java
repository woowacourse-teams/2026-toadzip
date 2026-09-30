package com.toadzip.backend.announcement.service;

import com.toadzip.backend.announcement.exception.AnnouncementNotFoundException;
import com.toadzip.backend.announcement.repository.AnnouncementViewRepository;
import java.time.Clock;
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

    public AnnouncementViewService(AnnouncementViewRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public long recordView(long announcementId, UUID viewerId) {
        long current = repository.lockViewCount(announcementId).orElseThrow(AnnouncementNotFoundException::new);
        LocalDate today = LocalDate.now(clock.withZone(SEOUL));
        if (!repository.recordOncePerDay(announcementId, viewerId, today)) {
            return current;
        }
        return repository.increment(announcementId);
    }
}

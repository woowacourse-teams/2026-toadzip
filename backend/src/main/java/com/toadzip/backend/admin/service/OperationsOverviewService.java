package com.toadzip.backend.admin.service;

import com.toadzip.backend.admin.dto.OperationsOverview;
import com.toadzip.backend.admin.repository.OperationsOverviewRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class OperationsOverviewService {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final OperationsOverviewRepository repository;
    private final Clock clock;

    public OperationsOverviewService(OperationsOverviewRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public OperationsOverview.Complexes complexes() {
        Instant now = now();
        return repository.complexes(today(now), now);
    }

    public OperationsOverview.Announcements announcements() {
        Instant now = now();
        return repository.announcements(today(now), now);
    }

    public OperationsOverview.Users users(int days) {
        if (days != 7 && days != 30) {
            throw new IllegalArgumentException("기간은 7일 또는 30일이어야 합니다.");
        }
        Instant now = now();
        LocalDate today = today(now);
        return repository.users(today.minusDays(days - 1L), today, LocalDateTime.ofInstant(now, SEOUL), days, now);
    }

    public OperationsOverview.Collections collections() {
        return repository.collections(now());
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private LocalDate today(Instant instant) {
        return instant.atZone(SEOUL).toLocalDate();
    }
}

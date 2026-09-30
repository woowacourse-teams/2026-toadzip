package com.toadzip.backend.interest.service;

import com.toadzip.backend.interest.repository.NotificationRetentionRepository;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationRetentionService {

    private final NotificationRetentionRepository repository;
    private final Clock clock;

    @Scheduled(cron = "0 */15 * * * *", zone = "Asia/Seoul")
    @Transactional
    public void purgeExpiredData() {
        repository.purge(clock.instant());
    }
}

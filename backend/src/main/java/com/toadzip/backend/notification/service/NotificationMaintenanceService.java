package com.toadzip.backend.notification.service;

import com.toadzip.backend.notification.repository.NotificationMaintenanceRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationMaintenanceService {
    private final NotificationMaintenanceRepository repository;

    @Transactional
    public void purge(Instant now) {
        repository.purgeDeliveries(now);
        repository.purgeInbox(now);
        repository.trimEvents(now);
    }
}

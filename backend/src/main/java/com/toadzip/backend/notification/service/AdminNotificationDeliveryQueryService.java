package com.toadzip.backend.notification.service;

import com.toadzip.backend.admin.dto.AdminPage;
import com.toadzip.backend.notification.dto.AdminNotificationDelivery;
import com.toadzip.backend.notification.repository.AdminNotificationDeliveryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@PreAuthorize("hasRole('ADMIN')")
public class AdminNotificationDeliveryQueryService {
    private final AdminNotificationDeliveryRepository deliveries;

    public AdminPage<AdminNotificationDelivery> search(Long userId, Long announcementId, String status, String keyword,
            int page, int size) {
        return deliveries.search(userId, announcementId, status, keyword, page, size);
    }
}

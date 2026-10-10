package com.toadzip.backend.interest.service;

import com.toadzip.backend.interest.dto.NotificationSubscriptionResponse;
import com.toadzip.backend.interest.repository.NotificationGuestSubscriptionRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GuestNotificationSubscriptionService {

    private final NotificationGuestSubscriptionRepository subscriptions;

    @Transactional(readOnly = true)
    public NotificationSubscriptionResponse findForClient(UUID clientId) {
        return subscriptions.findForClient(clientId);
    }
}

package com.toadzip.backend.notification.configuration;

import com.toadzip.backend.notification.service.NotificationDeliveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.notification.delivery-enabled", havingValue = "true")
public class NotificationDeliveryJob {
    private final NotificationDeliveryService service;

    @Scheduled(fixedDelayString = "${app.notification.delivery-delay:5000}", scheduler = "notificationDeliveryScheduler")
    public void deliver() {
        service.deliverAvailable(20);
    }
}

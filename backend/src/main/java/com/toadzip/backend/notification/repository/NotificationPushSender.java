package com.toadzip.backend.notification.repository;

import com.toadzip.backend.notification.domain.ClaimedNotificationDelivery;
import com.toadzip.backend.notification.domain.NotificationSendResult;

@FunctionalInterface
public interface NotificationPushSender {
    NotificationSendResult send(ClaimedNotificationDelivery delivery);
}

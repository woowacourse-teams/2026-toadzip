package com.toadzip.backend.notification.repository;

import com.toadzip.backend.notification.domain.ClaimedNotificationDelivery;
import com.toadzip.backend.notification.domain.NotificationSendResult;
import io.micrometer.core.instrument.MeterRegistry;

public record ObservedNotificationPushSender(NotificationPushSender delegate, MeterRegistry meters)
        implements NotificationPushSender {
    @Override
    public NotificationSendResult send(ClaimedNotificationDelivery delivery) {
        var sample = io.micrometer.core.instrument.Timer.start(meters);
        try {
            var result = delegate.send(delivery);
            meters.counter("notification.push.result", "result", result.status().name()).increment();
            return result;
        } catch (RuntimeException failure) {
            meters.counter("notification.push.result", "result", "EXCEPTION").increment();
            throw failure;
        } finally {
            sample.stop(meters.timer("notification.push.duration"));
        }
    }
}

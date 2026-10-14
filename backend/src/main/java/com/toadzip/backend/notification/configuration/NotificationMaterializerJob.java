package com.toadzip.backend.notification.configuration;

import com.toadzip.backend.notification.service.NotificationMaterializationService;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.notification.materializer-enabled", havingValue = "true")
public class NotificationMaterializerJob {
    private final NotificationMaterializationService service;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${app.notification.materializer-delay:10000}",
            scheduler = "notificationMaterializerScheduler")
    public void materialize() {
        for (int page = 0; page < 20; page++) {
            if (service.materializeNextPage(clock.instant(), 200) == 0) {
                return;
            }
        }
    }
}

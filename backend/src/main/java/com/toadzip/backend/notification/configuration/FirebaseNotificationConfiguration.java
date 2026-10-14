package com.toadzip.backend.notification.configuration;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.toadzip.backend.notification.repository.FirebaseNotificationPushSender;
import com.toadzip.backend.notification.repository.NotificationAudienceRepository;
import com.toadzip.backend.notification.repository.NotificationDeliveryRepository;
import com.toadzip.backend.notification.repository.NotificationPushSender;
import com.toadzip.backend.notification.repository.ObservedNotificationPushSender;
import io.micrometer.core.instrument.MeterRegistry;
import com.toadzip.backend.notification.service.NotificationDeliveryService;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.notification.delivery-enabled", havingValue = "true")
public class FirebaseNotificationConfiguration {
    @Bean(destroyMethod = "delete")
    public FirebaseApp notificationFirebaseApp(@Value("${app.notification.firebase-project-id}") String projectId)
            throws IOException {
        if (projectId.isBlank()) {
            throw new IllegalArgumentException("FCM project ID가 필요합니다.");
        }
        var options = FirebaseOptions.builder().setProjectId(projectId)
                .setCredentials(GoogleCredentials.getApplicationDefault())
                .setConnectTimeout(5000).setReadTimeout(10000).setWriteTimeout(10000).build();
        return FirebaseApp.initializeApp(options, "notification-delivery");
    }

    @Bean
    public NotificationPushSender notificationPushSender(@Qualifier("notificationFirebaseApp") FirebaseApp app,
            NotificationProperties properties, Clock clock, MeterRegistry meters) {
        return new ObservedNotificationPushSender(new FirebaseNotificationPushSender(FirebaseMessaging.getInstance(app), properties.frontendOrigin(), clock,
                Duration.ofSeconds(30)), meters);
    }

    @Bean
    public NotificationDeliveryService notificationDeliveryService(NotificationDeliveryRepository deliveries,
            NotificationAudienceRepository audience, NotificationPushSender sender,
            PlatformTransactionManager manager, Clock clock) {
        return new NotificationDeliveryService(deliveries, audience, sender, manager, clock);
    }
}

package com.toadzip.backend.notification.configuration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotificationProperties.class)
public class NotificationJobConfiguration {
    @Bean(defaultCandidate = false)
    @ConditionalOnProperty(name = "app.notification.maintenance-enabled", havingValue = "true")
    public ThreadPoolTaskScheduler notificationMaintenanceScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("notification-maintenance-");
        return scheduler;
    }

    @Bean(defaultCandidate = false)
    @ConditionalOnProperty(name = "app.notification.delivery-enabled", havingValue = "true")
    public ThreadPoolTaskScheduler notificationDeliveryScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("notification-delivery-");
        return scheduler;
    }

    @Bean(defaultCandidate = false)
    @ConditionalOnProperty(name = "app.notification.materializer-enabled", havingValue = "true")
    public ThreadPoolTaskScheduler notificationMaterializerScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("notification-materializer-");
        return scheduler;
    }
}

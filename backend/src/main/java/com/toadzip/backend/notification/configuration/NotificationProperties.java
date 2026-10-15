package com.toadzip.backend.notification.configuration;

import java.time.Instant;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.notification")
public record NotificationProperties(boolean publisherEnabled, boolean materializerEnabled, boolean deliveryEnabled,
        Instant activationAt, String frontendOrigin) {
    public NotificationProperties {
        if (publisherEnabled && activationAt == null) {
            throw new IllegalArgumentException("알림 생성 활성화에는 고정 activation-at 설정이 필요합니다.");
        }
    }
}

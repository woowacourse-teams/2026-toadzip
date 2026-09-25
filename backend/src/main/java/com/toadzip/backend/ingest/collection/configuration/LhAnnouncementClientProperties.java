package com.toadzip.backend.ingest.collection.configuration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ingest.lh-announcement-client")
public record LhAnnouncementClientProperties(
        int maxConcurrentRequests,
        Duration connectTimeout,
        Duration readTimeout
) {
    public LhAnnouncementClientProperties {
        if (maxConcurrentRequests < 1 || maxConcurrentRequests > 8) {
            throw new IllegalArgumentException("LH 공고 동시 요청 수는 1~8이어야 합니다.");
        }
        requirePositive(connectTimeout);
        requirePositive(readTimeout);
    }

    private static void requirePositive(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative() || timeout.toMillis() < 1) {
            throw new IllegalArgumentException("LH 공고 타임아웃은 1ms 이상이어야 합니다.");
        }
    }
}

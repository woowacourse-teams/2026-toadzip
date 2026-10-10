package com.toadzip.backend.privacy.configuration;

import com.toadzip.backend.privacy.domain.AnalyticsCollectionPolicy;
import com.toadzip.backend.privacy.domain.PrivacyRetentionPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PrivacyConfiguration {

    @Bean
    public PrivacyRetentionPolicy privacyRetentionPolicy() {
        return new PrivacyRetentionPolicy();
    }

    @Bean
    public AnalyticsCollectionPolicy analyticsCollectionPolicy() {
        return new AnalyticsCollectionPolicy();
    }
}

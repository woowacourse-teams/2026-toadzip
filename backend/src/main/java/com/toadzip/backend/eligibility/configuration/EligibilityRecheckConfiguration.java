package com.toadzip.backend.eligibility.configuration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "comparison.recheck.enabled", havingValue = "true", matchIfMissing = true)
public class EligibilityRecheckConfiguration {
    @Bean(defaultCandidate = false)
    public ThreadPoolTaskScheduler eligibilityTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("eligibility-recheck-");
        return scheduler;
    }
}

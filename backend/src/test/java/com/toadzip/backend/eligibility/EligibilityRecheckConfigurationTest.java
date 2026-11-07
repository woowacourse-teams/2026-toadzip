package com.toadzip.backend.eligibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.toadzip.backend.eligibility.configuration.EligibilityRecheckConfiguration;
import com.toadzip.backend.eligibility.service.EligibilityRecheckScheduler;
import com.toadzip.backend.eligibility.service.EligibilityRecheckWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class EligibilityRecheckConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withBean(EligibilityRecheckWorker.class, () -> mock(EligibilityRecheckWorker.class))
            .withBean("taskScheduler", ThreadPoolTaskScheduler.class, ThreadPoolTaskScheduler::new)
            .withUserConfiguration(Scheduling.class, EligibilityRecheckConfiguration.class,
                    EligibilityRecheckScheduler.class);

    @Test
    void 재판정은_기존_공통_스케줄러를_교체하지_않고_별도_스케줄러에서_실행한다() {
        context.run(application -> {
            assertThat(application).hasNotFailed();
            assertThat(application.getBean("eligibilityTaskScheduler"))
                    .isNotSameAs(application.getBean("taskScheduler"));
        });
    }

    @Test
    void 작업자_실행을_끄더라도_기존_공통_스케줄러는_유지한다() {
        context.withPropertyValues("comparison.recheck.enabled=false").run(application -> {
            assertThat(application).hasNotFailed().hasBean("taskScheduler")
                    .doesNotHaveBean("eligibilityTaskScheduler").doesNotHaveBean(EligibilityRecheckScheduler.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class Scheduling {}
}

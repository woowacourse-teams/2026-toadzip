package com.toadzip.backend.global.config;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfig {

    /** 공고 자동 수집과 공고문 PDF 자동 추출이 쓰는 스케줄러 이름. 둘 다 이 이름으로 실행한다. */
    public static final String AUTO_RUN_SCHEDULER = "autoRunScheduler";

    /**
     * 공고 자동 수집·PDF 자동 추출 전용 스케줄러. 이름을 정하지 않으면 신청자격 재판정의 단일 스레드에서 함께 돌아, 첨부 내려받기처럼
     * 오래 걸리는 확인이 재판정을 막을 수 있어 따로 둔다. 두 작업이 서로 막지 않게 스레드는 두 개다.
     * TaskScheduler가 아니라 기본 후보에서 빠진 실행기로 두어, 이름 없는 다른 예약 작업이 고르는 기본 스케줄러를 바꾸지 않는다.
     */
    @Bean(name = AUTO_RUN_SCHEDULER, destroyMethod = "shutdown", defaultCandidate = false)
    public ScheduledExecutorService autoRunScheduler() {
        return Executors.newScheduledThreadPool(2, Thread.ofPlatform().name("auto-run-", 1).factory());
    }
}

package com.toadzip.backend.basiccomparison.service;

import com.toadzip.backend.basiccomparison.domain.EligibilityWork.Kind;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "comparison.recheck.enabled", havingValue = "true", matchIfMissing = true)
public class EligibilityRecheckScheduler {
    private final EligibilityRecheckWorker worker;

    public EligibilityRecheckScheduler(EligibilityRecheckWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${comparison.recheck.poll-milliseconds:1000}",
            scheduler = "eligibilityTaskScheduler")
    public void tick() {
        worker.reconcileOnce();
        worker.runOnce(Kind.PROFILE);
        worker.runOnce(Kind.NOTICE);
    }
}

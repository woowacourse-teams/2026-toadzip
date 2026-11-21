package com.toadzip.backend.announcement.service;

import com.toadzip.backend.admin.exception.AdminDataConflictException;
import com.toadzip.backend.announcement.domain.PdfExtractionInput.PublicationMode;
import com.toadzip.backend.announcement.repository.PdfPublicationDecisions;
import com.toadzip.backend.global.config.SchedulingConfig;
import com.toadzip.backend.ingest.exception.exception.IngestAlreadyRunningException;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Slf4j
@Component
class PdfPublicationScheduler {
    static final String ACTOR = "pdf-publication-agent";
    private final PdfPublicationDecisions decisions;
    private final PdfPublicationService service;
    private final PublicationMode mode;

    PdfPublicationScheduler(PdfPublicationDecisions decisions, PdfPublicationService service,
            @Value("${announcement.pdf-publication.mode}") PublicationMode mode) {
        this.decisions = decisions;
        this.service = service;
        this.mode = mode;
    }

    @Scheduled(fixedDelayString = "${announcement.pdf-auto-extraction.check-interval}",
            initialDelayString = "${announcement.pdf-auto-extraction.initial-delay}",
            scheduler = SchedulingConfig.AUTO_RUN_SCHEDULER)
    void process() {
        if (mode == PublicationMode.OFF) {
            return;
        }
        var previous = SecurityContextHolder.getContext();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(ACTOR, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        try {
            SecurityContextHolder.setContext(context);
            for (Long job : decisions.pending()) {
                try {
                    service.process(job, mode);
                } catch (IngestAlreadyRunningException busy) {
                    // 수집·정제가 공고를 고치는 중이다. 결정을 남기지 않고 이번 주기를 끝내 다음 주기에 다시 판정한다.
                    return;
                } catch (RuntimeException failure) {
                    recordFailure(job, failure);
                }
            }
            if (mode == PublicationMode.APPLY) {
                for (Long job : decisions.awaitingEligibility()) {
                    try {
                        service.publishEligibility(job);
                    } catch (RuntimeException failure) {
                        recordEligibilityFailure(job, failure);
                    }
                }
            }
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    /** 다시 하면 풀리는 실패는 결정을 남기지 않아 다음 주기에 다시 판정한다. 그 밖의 실패는 검수로 넘긴다. */
    private void recordFailure(long job, RuntimeException failure) {
        if (service.retriesNextCycle(job, failure)) {
            log.warn("event=pdf.publication.deferred jobId={}", job, failure);
            return;
        }
        log.warn("event=pdf.publication.held jobId={}", job, failure);
        decisions.record(job, "REVIEW", reasonOf(failure), null, null);
    }

    /** 신청자격 공개도 다시 하면 풀리는 실패는 기록하지 않아 PENDING으로 남기고 다음 주기에 다시 공개한다. */
    private void recordEligibilityFailure(long job, RuntimeException failure) {
        if (service.retriesNextCycle(job, failure)) {
            log.warn("event=pdf.eligibility.deferred jobId={}", job, failure);
            return;
        }
        log.warn("event=pdf.eligibility.held jobId={}", job, failure);
        decisions.finishEligibility(job, "REVIEW", eligibilityReasonOf(failure));
    }

    /**
     * 보류 사유와 보정 서비스의 충돌 안내 문구만 검수함에 그대로 보인다. IllegalArgumentException은 JDK·내부 검증 문구가 섞여
     * 있어 보이지 않는다. 그 밖의 실패 원인은 로그에만 남긴다.
     */
    private static String reasonOf(RuntimeException failure) {
        boolean readable = failure instanceof PublicationHold || failure instanceof AdminDataConflictException;
        if (readable && failure.getMessage() != null) {
            return failure.getMessage();
        }
        return "자동 보정 실패 · 원문과 변경안을 확인하세요.";
    }

    /** 공고는 이미 공개했다. 신청자격을 공개하지 않은 보류 사유만 그대로 보이고 그 밖의 실패 원인은 로그에만 남긴다. */
    private static String eligibilityReasonOf(RuntimeException failure) {
        if (failure instanceof PublicationHold && failure.getMessage() != null) {
            return "공고 보정 완료 · " + failure.getMessage();
        }
        return "공고 보정 완료 · 신청자격 조건과 대상 연결을 확인하세요.";
    }
}

package com.toadzip.backend.announcement.service;

import com.toadzip.backend.announcement.domain.PdfExtractionInput.PublicationMode;
import com.toadzip.backend.announcement.repository.PdfPublicationDecisions;
import com.toadzip.backend.global.config.SchedulingConfig;
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
            initialDelayString = "${announcement.pdf-auto-extraction.initial-delay}", scheduler = SchedulingConfig.AUTO_RUN_SCHEDULER)
    void process() {
        if (mode == PublicationMode.OFF) { return; }
        var previous = SecurityContextHolder.getContext();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(ACTOR, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        try {
            SecurityContextHolder.setContext(context);
            for (Long job : decisions.pending()) {
                try {
                    service.process(job, mode);
                } catch (RuntimeException failure) {
                    log.warn("event=pdf.publication.held jobId={}", job, failure);
                    String reason = failure instanceof IllegalArgumentException ? failure.getMessage() : "자동 보정 실패 · 원문과 변경안을 확인하세요.";
                    decisions.record(job, "REVIEW", reason == null ? "공고문 확인 필요" : reason, null, null);
                }
            }
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }
}

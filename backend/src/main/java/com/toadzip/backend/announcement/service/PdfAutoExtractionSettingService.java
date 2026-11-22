package com.toadzip.backend.announcement.service;

import com.toadzip.backend.announcement.domain.PdfAutoExtractionSetting;
import com.toadzip.backend.announcement.domain.PdfExtractionInput.PublicationMode;
import com.toadzip.backend.announcement.domain.PdfExtractionJob;
import com.toadzip.backend.announcement.dto.response.PdfAutoExtractionStatus;
import com.toadzip.backend.announcement.repository.AnnouncementSearchRepository;
import com.toadzip.backend.announcement.repository.PdfAutoExtractionSettingRepository;
import com.toadzip.backend.announcement.repository.PdfExtractionJobRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PDF 자동 추출을 서버를 다시 띄우지 않고 켜고 끈다. 관리자가 바꾼 값은 DB에 남아 모든 서버의 스케줄러가 다음 주기에 읽는다.
 * 바꾼 적이 없으면 announcement.pdf-auto-extraction.enabled(환경 변수 PDF_AUTO_EXTRACTION_ENABLED)를 따른다.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class PdfAutoExtractionSettingService {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final PdfAutoExtractionSettingRepository settings;
    private final AnnouncementSearchRepository announcements;
    private final PdfExtractionJobRepository jobs;
    private final Clock clock;
    private final boolean defaultEnabled;
    private final PublicationMode publicationMode;
    private final int maxInFlight;

    public PdfAutoExtractionSettingService(
            PdfAutoExtractionSettingRepository settings,
            AnnouncementSearchRepository announcements,
            PdfExtractionJobRepository jobs,
            Clock clock,
            @Value("${announcement.pdf-auto-extraction.enabled}") boolean defaultEnabled,
            @Value("${announcement.pdf-publication.mode}") PublicationMode publicationMode,
            @Value("${announcement.pdf-auto-extraction.max-in-flight}") int maxInFlight
    ) {
        this.settings = settings;
        this.announcements = announcements;
        this.jobs = jobs;
        this.clock = clock;
        this.defaultEnabled = defaultEnabled;
        this.publicationMode = publicationMode;
        this.maxInFlight = maxInFlight;
    }

    public boolean enabled() {
        return stored().map(PdfAutoExtractionSetting::isEnabled).orElse(defaultEnabled);
    }

    public PdfAutoExtractionStatus status() {
        Instant now = clock.instant();
        Optional<PdfAutoExtractionSetting> stored = stored();
        long notExtracted = announcements.countOpenStages(LocalDate.ofInstant(now, SEOUL))
                .getOrDefault("NOT_EXTRACTED", 0L);
        long inFlight = jobs.countByKindAndRequestSourceAndStatusIn(PdfExtractionJob.KIND, PdfExtractionJob.BATCH,
                PdfAutoExtractionScheduler.ACTIVE);
        return new PdfAutoExtractionStatus(stored.map(PdfAutoExtractionSetting::isEnabled).orElse(defaultEnabled),
                stored.map(PdfAutoExtractionSetting::getUpdatedBy).orElse(null),
                stored.map(PdfAutoExtractionSetting::getUpdatedAt).orElse(null),
                publicationMode, notExtracted, inFlight, maxInFlight, now);
    }

    /** 끄면 다음 주기부터 새 작업을 넣지 않는다. 이미 넣은 작업은 그대로 처리된다. */
    @Transactional
    public PdfAutoExtractionStatus change(boolean enabled, String admin) {
        settings.save(enabled, admin, clock.instant());
        log.info("event=announcement.pdf_extraction.auto_toggled enabled={} admin={}", enabled, admin);
        return status();
    }

    private Optional<PdfAutoExtractionSetting> stored() {
        return settings.findById(PdfAutoExtractionSetting.ID);
    }
}

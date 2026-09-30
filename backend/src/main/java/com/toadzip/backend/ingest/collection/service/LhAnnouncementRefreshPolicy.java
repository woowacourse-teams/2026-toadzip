package com.toadzip.backend.ingest.collection.service;

import com.toadzip.backend.ingest.collection.domain.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementCollectionCandidateResolver.Candidate;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LhAnnouncementRefreshPolicy {

    private static final DateTimeFormatter COMPACT_DATE = DateTimeFormatter.ofPattern("uuuuMMdd")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter DOTTED_DATE = DateTimeFormatter.ofPattern("uuuu.MM.dd")
            .withResolverStyle(ResolverStyle.STRICT);

    private final Clock clock;
    private final Duration activeRefreshTtl;
    private final Duration recentEndedRefreshTtl;
    private final Duration unchangedRefreshTtl;
    private final Period recentEndedWindow;

    public LhAnnouncementRefreshPolicy(
            Clock clock,
            @Value("${ingest.lh-announcement-refresh-ttl}") Duration activeRefreshTtl,
            @Value("${ingest.lh-announcement-recent-ended-refresh-ttl}") Duration recentEndedRefreshTtl,
            @Value("${ingest.lh-announcement-unchanged-refresh-ttl}") Duration unchangedRefreshTtl,
            @Value("${ingest.lh-announcement-recent-ended-window}") Period recentEndedWindow
    ) {
        validatePositive(activeRefreshTtl, "진행 중 LH 공고 재수집 만료 시간");
        validatePositive(recentEndedRefreshTtl, "최근 종료 LH 공고 재수집 만료 시간");
        validatePositive(unchangedRefreshTtl, "변경 없는 LH 공고 재수집 만료 시간");
        if (unchangedRefreshTtl.compareTo(activeRefreshTtl) < 0) {
            throw new IllegalArgumentException("변경 없는 LH 공고 재수집 주기는 진행 중 공고보다 짧을 수 없습니다.");
        }
        if (recentEndedRefreshTtl.compareTo(activeRefreshTtl) < 0) {
            throw new IllegalArgumentException(
                    "최근 종료 LH 공고 재수집 주기는 진행 중 공고보다 짧을 수 없습니다."
            );
        }
        if (recentEndedWindow.isZero() || recentEndedWindow.isNegative()) {
            throw new IllegalArgumentException("최근 종료 LH 공고 범위는 0보다 커야 합니다.");
        }
        this.clock = clock;
        this.activeRefreshTtl = activeRefreshTtl;
        this.recentEndedRefreshTtl = recentEndedRefreshTtl;
        this.unchangedRefreshTtl = unchangedRefreshTtl;
        this.recentEndedWindow = recentEndedWindow;
    }

    public Optional<Duration> scheduledRefreshTtl(MyHomeAnnouncementSource source) {
        Optional<LocalDate> applicationEndDate = applicationEndDate(source.getEndDe());
        if (applicationEndDate.isEmpty()) {
            return Optional.of(activeRefreshTtl);
        }
        LocalDate today = LocalDate.now(clock);
        LocalDate endDate = applicationEndDate.orElseThrow();
        if (!endDate.isBefore(today)) {
            return Optional.of(activeRefreshTtl);
        }
        if (!endDate.isBefore(today.minus(recentEndedWindow))) {
            return Optional.of(recentEndedRefreshTtl);
        }
        return Optional.empty();
    }

    public Optional<Duration> scheduledRefreshTtl(MyHomeAnnouncementSource source, Candidate candidate) {
        Optional<LocalDate> endDate = applicationEndDate(source.getEndDe());
        if (endDate.isPresent() && endDate.orElseThrow().isAfter(LocalDate.now(clock).plusDays(2))
                && catalogIsCurrent(source, candidate)) {
            return Optional.of(unchangedRefreshTtl);
        }
        return scheduledRefreshTtl(source);
    }

    private boolean catalogIsCurrent(MyHomeAnnouncementSource source, Candidate candidate) {
        return candidate.catalogCollectedAt() != null && source.getCollectedAt() != null
                && !candidate.catalogCollectedAt().isBefore(source.getCollectedAt())
                && candidate.catalogCollectedAt().isAfter(clock.instant().minus(activeRefreshTtl));
    }

    private Optional<LocalDate> applicationEndDate(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.strip();
        DateTimeFormatter formatter = COMPACT_DATE;
        if (normalized.contains(".")) {
            formatter = DOTTED_DATE;
        }
        if (normalized.contains("-")) {
            formatter = DateTimeFormatter.ISO_LOCAL_DATE;
        }
        try {
            return Optional.of(LocalDate.parse(normalized, formatter));
        }
        catch (DateTimeParseException exception) {
            return Optional.empty();
        }
    }

    private void validatePositive(Duration duration, String fieldName) {
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(fieldName + "은 0보다 커야 합니다.");
        }
    }
}

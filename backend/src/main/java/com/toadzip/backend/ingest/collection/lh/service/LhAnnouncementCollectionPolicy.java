package com.toadzip.backend.ingest.collection.lh.service;

import com.toadzip.backend.ingest.collection.lh.announcementcatalog.domain.LhAnnouncementCatalogSourceView;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSourceView;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LhAnnouncementCollectionPolicy {

    private static final DateTimeFormatter COMPACT_DATE = DateTimeFormatter.ofPattern("uuuuMMdd")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter DOTTED_DATE = DateTimeFormatter.ofPattern("uuuu.MM.dd")
            .withResolverStyle(ResolverStyle.STRICT);

    private final Clock clock;
    private final Period recentEndedWindow;

    public LhAnnouncementCollectionPolicy(
            Clock clock,
            @Value("${ingest.lh-announcement-recent-ended-window}") Period recentEndedWindow
    ) {
        if (recentEndedWindow == null || recentEndedWindow.isNegative() || recentEndedWindow.isZero()) {
            throw new IllegalArgumentException("종료 공고 수집 범위는 양수여야 합니다.");
        }
        this.clock = clock;
        this.recentEndedWindow = recentEndedWindow;
    }

    public boolean isCollectionTarget(MyHomeAnnouncementSourceView source) {
        return applicationEndDate(source.getEndDe()).map(this::isRecentlyEnded).orElse(true);
    }

    /** 공고목록 공고도 마이홈 공고와 같은 기준으로 접수가 오래전에 끝난 공고는 받지 않는다. */
    public boolean isCollectionTarget(LhAnnouncementCatalogSourceView source) {
        return source.closingLocalDate().map(this::isRecentlyEnded).orElse(true);
    }

    private boolean isRecentlyEnded(LocalDate endDate) {
        return !endDate.isBefore(LocalDate.now(clock).minus(recentEndedWindow));
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


}

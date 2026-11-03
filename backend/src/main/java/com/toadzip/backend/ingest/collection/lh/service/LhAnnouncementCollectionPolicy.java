package com.toadzip.backend.ingest.collection.lh.service;

import com.toadzip.backend.ingest.collection.lh.announcementcatalog.domain.LhAnnouncementCatalogSourceView;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LhAnnouncementCollectionPolicy {

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

    /** 접수가 오래전에 끝난 공고는 받지 않는다. 마감일이 없거나 해석하지 못하면 받는다. */
    public boolean isCollectionTarget(LhAnnouncementCatalogSourceView source) {
        return source.closingLocalDate().map(this::isRecentlyEnded).orElse(true);
    }

    private boolean isRecentlyEnded(LocalDate endDate) {
        return !endDate.isBefore(LocalDate.now(clock).minus(recentEndedWindow));
    }
}

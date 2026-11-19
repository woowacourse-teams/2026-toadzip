package com.toadzip.backend.ingest.lh.collection.service;

import com.toadzip.backend.ingest.lh.collection.announcementcatalog.domain.LhAnnouncementCatalogSourceView;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LhAnnouncementCollectionPolicy {

    private final Clock clock;
    private final Period recentEndedWindow;
    private final Period incrementalEndedWindow;

    /**
     * @param recentEndedWindow 전체 수집이 다시 받는 마감 뒤 기간
     * @param incrementalEndedWindow 자동 실행의 증분 수집이 다시 받는 마감 뒤 기간
     */
    public LhAnnouncementCollectionPolicy(
            Clock clock,
            @Value("${ingest.lh-announcement-recent-ended-window}") Period recentEndedWindow,
            @Value("${ingest.lh-announcement-incremental-ended-window}") Period incrementalEndedWindow
    ) {
        requirePositive(recentEndedWindow);
        requirePositive(incrementalEndedWindow);
        this.clock = clock;
        this.recentEndedWindow = recentEndedWindow;
        this.incrementalEndedWindow = incrementalEndedWindow;
    }

    /**
     * 접수가 오래전에 끝난 공고는 받지 않는다. 마감일이 없거나 해석하지 못하면 받는다. 자동 실행의 증분 수집은 막 마감된
     * 공고까지만, 전체 수집은 마감 뒤 한 달 안의 공고까지 받는다.
     */
    public boolean isCollectionTarget(LhAnnouncementCatalogSourceView source) {
        return source.closingLocalDate().map(this::isRecentlyEnded).orElse(true);
    }

    private boolean isRecentlyEnded(LocalDate endDate) {
        Period window = recentEndedWindow;
        if (IngestExecutionScope.isIncrementalCollection()) {
            window = incrementalEndedWindow;
        }
        return !endDate.isBefore(LocalDate.now(clock).minus(window));
    }

    private static void requirePositive(Period window) {
        if (window == null || window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("종료 공고 수집 범위는 양수여야 합니다.");
        }
    }
}

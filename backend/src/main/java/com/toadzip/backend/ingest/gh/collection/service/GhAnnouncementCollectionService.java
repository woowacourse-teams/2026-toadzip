package com.toadzip.backend.ingest.gh.collection.service;

import com.toadzip.backend.ingest.collection.board.service.BoardSourceCollector;
import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** GH주택청약센터 임대주택 게시판을 돌며 공고 원천을 수집한다. */
@Service
public class GhAnnouncementCollectionService {

    private final GhAnnouncementCollector collector;
    private final BoardSourceCollector boardCollector;
    private final int lookbackDays;
    private final Duration requestInterval;

    public GhAnnouncementCollectionService(
            GhAnnouncementCollector collector, BoardSourceCollector boardCollector,
            @Value("${ingest.gh.lookback-days:90}") int lookbackDays,
            @Value("${ingest.gh.request-interval:1s}") Duration requestInterval
    ) {
        if (lookbackDays < 0 || requestInterval.isNegative()) {
            throw new IllegalArgumentException("GH 조회 기간과 요청 간격은 음수일 수 없습니다.");
        }
        this.collector = collector;
        this.boardCollector = boardCollector;
        this.lookbackDays = lookbackDays;
        this.requestInterval = requestInterval;
    }

    public ExternalDataCollectionReport collect() {
        IngestExecutionScope.requireHeld();
        return boardCollector.collect(collector, lookbackDays, requestInterval);
    }
}

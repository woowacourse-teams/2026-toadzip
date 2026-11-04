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

    private final GhAnnouncementBoardSource board;
    private final BoardSourceCollector collector;
    private final int lookbackDays;
    private final Duration requestInterval;

    public GhAnnouncementCollectionService(
            GhAnnouncementBoardSource board, BoardSourceCollector collector,
            @Value("${ingest.gh.lookback-days:90}") int lookbackDays,
            @Value("${ingest.gh.request-interval:1s}") Duration requestInterval
    ) {
        if (lookbackDays < 0 || requestInterval.isNegative()) {
            throw new IllegalArgumentException("GH 조회 기간과 요청 간격은 음수일 수 없습니다.");
        }
        this.board = board;
        this.collector = collector;
        this.lookbackDays = lookbackDays;
        this.requestInterval = requestInterval;
    }

    public ExternalDataCollectionReport collect() {
        IngestExecutionScope.requireHeld();
        return collector.collect(board, lookbackDays, requestInterval);
    }
}

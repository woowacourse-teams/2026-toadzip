package com.toadzip.backend.ingest.sh.collection.service;

import com.toadzip.backend.ingest.collection.board.service.BoardSourceCollector;
import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ShAnnouncementCollectionService {

    private final ShAnnouncementBoardSource board;
    private final BoardSourceCollector collector;
    private final int lookbackDays;
    private final Duration requestInterval;

    public ShAnnouncementCollectionService(
            ShAnnouncementBoardSource board, BoardSourceCollector collector,
            @Value("${ingest.sh.lookback-days:90}") int lookbackDays,
            @Value("${ingest.sh.request-interval:1s}") Duration requestInterval
    ) {
        if (lookbackDays < 0 || requestInterval.isNegative()) {
            throw new IllegalArgumentException("SH 조회 기간과 요청 간격은 음수일 수 없습니다.");
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

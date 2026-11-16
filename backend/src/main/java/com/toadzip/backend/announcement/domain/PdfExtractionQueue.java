package com.toadzip.backend.announcement.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * 공고문 PDF 추출 작업 큐의 한 시점 요약. 대기는 PENDING과 RETRY다. 성공·실패 수와 처리 시간은 window 안에 끝난 작업만 센다.
 * 대기가 없으면 oldestWait가, 성공한 작업이 없으면 처리 시간이 null이다.
 */
public record PdfExtractionQueue(
        long pending,
        long retrying,
        long running,
        Duration oldestWait,
        long succeeded,
        long failed,
        Duration averageProcessing,
        Duration maxProcessing,
        Duration window,
        Instant asOf
) {
    public long waiting() {
        return pending + retrying;
    }

    /** 기다리는 작업 하나의 큐 위치. ahead는 먼저 처리될 작업 수, estimatedWait는 시작까지의 추정 시간(모르면 null)이다. */
    public record Position(long ahead, Duration estimatedWait) {
    }
}

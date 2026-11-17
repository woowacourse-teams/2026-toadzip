package com.toadzip.backend.admin.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class OperationsOverview {
    private OperationsOverview() {
    }

    public record Complexes(long total, long recruiting, long missingCoordinates, long reviewRequired,
            Map<String, Long> agencies, Instant asOf) {
    }

    public record Announcements(Map<String, Long> statuses, long attention, Map<String, Long> reasons, Instant asOf) {
    }

    public record Users(long total, long newUsers, long previousNewUsers, List<DayCount> trend, Instant asOf) {
    }

    public record DayCount(LocalDate day, long count) {
    }

    public record Collections(List<Collection> sources, Instant asOf) {
    }

    public record Collection(String agency, Instant lastSuccessAt, Instant latestStartedAt, String latestStatus) {
    }

    /**
     * 처리할 일. stages는 휴지통을 뺀 마감 전 공고의 처리 단계별 수, unresolvedFailures는 공고 쪽 미해결 수집·정제 실패 수다.
     */
    public record Tasks(Map<String, Long> stages, AiQueue queue, long unresolvedFailures, Instant asOf) {
    }

    /** 공고문 PDF 추출 작업 큐. oldestWaitSeconds는 기다리는 작업이 없으면 null, recentFailed는 최근 24시간에 실패로 끝난 수다. */
    public record AiQueue(long waiting, long running, Long oldestWaitSeconds, long recentFailed) {
    }
}

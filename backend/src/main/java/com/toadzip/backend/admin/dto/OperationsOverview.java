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
}

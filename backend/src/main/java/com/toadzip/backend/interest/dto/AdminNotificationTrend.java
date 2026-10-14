package com.toadzip.backend.interest.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record AdminNotificationTrend(List<Day> days, Instant collectionStartedAt, Instant asOf) {
    public record Day(LocalDate day, Long activations, Long cancellations) {
    }
}

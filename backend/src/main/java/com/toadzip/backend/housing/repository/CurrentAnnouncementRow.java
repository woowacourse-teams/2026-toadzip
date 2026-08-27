package com.toadzip.backend.housing.repository;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CurrentAnnouncementRow(
        long announcementId,
        String title,
        String publicationType,
        LocalDate postedDate,
        LocalDate applicationStartAt,
        LocalDate applicationEndAt,
        BigDecimal actualCompetitionRate
) {
}

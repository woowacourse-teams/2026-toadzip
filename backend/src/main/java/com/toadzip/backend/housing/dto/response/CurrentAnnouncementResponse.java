package com.toadzip.backend.housing.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record CurrentAnnouncementResponse(
        long announcementId,
        String title,
        String publicationType,
        String applicationStatus,
        List<String> targets,
        LocalDate applicationStartAt,
        LocalDate applicationEndAt,
        Integer dDay,
        BigDecimal actualCompetitionRate
) {

    public CurrentAnnouncementResponse {
        targets = List.copyOf(targets);
    }
}

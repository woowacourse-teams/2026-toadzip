package com.toadzip.backend.announcement.dto.request;

import com.toadzip.backend.announcement.domain.ApplicationScheduleState;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record VerifiedApplicationSchedulesRequest(
        @NotEmpty @Size(max = 100) List<@NotNull @Valid Schedule> schedules
) {
    public record Schedule(
            @Positive Long housingComplexId,
            @Size(max = 255) String supplyRank,
            @NotNull ApplicationScheduleState state,
            @Size(max = 1000) String condition,
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate,
            LocalTime startTime,
            LocalTime endTime,
            @NotBlank @Size(max = 2000) @Pattern(regexp = "^https?://[^\\s]+$") String sourceUrl,
            @Positive int sourcePage
    ) {
    }
}

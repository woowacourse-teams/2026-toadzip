package com.toadzip.backend.admin.quality.dto;

import com.toadzip.backend.admin.quality.domain.ReviewTaskStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record SaveReviewTask(@NotNull @Min(0) Long expectedRevision, @NotNull @Size(max = 255) String assignee,
        LocalDate dueDate, @NotNull ReviewTaskStatus status, @NotBlank @Size(max = 2000) String note) { }

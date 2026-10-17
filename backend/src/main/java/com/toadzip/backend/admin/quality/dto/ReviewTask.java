package com.toadzip.backend.admin.quality.dto;

import com.toadzip.backend.admin.quality.domain.QualityResource;
import com.toadzip.backend.admin.quality.domain.ReviewTaskStatus;
import java.time.Instant;
import java.time.LocalDate;

public record ReviewTask(QualityResource resource, long id, String name, long revision, String assignee,
        LocalDate dueDate, ReviewTaskStatus status, String note, String actor, Instant updatedAt,
        boolean overdue, boolean sourceChanged) { }

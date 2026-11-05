package com.toadzip.backend.basiccomparison.dto;

import com.toadzip.backend.basiccomparison.domain.ComparisonContent;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

public final class ComparisonRequests {
    private ComparisonRequests() {}

    public record SaveDraft(
            @Positive Long expectedDraftId,
            @NotNull @PositiveOrZero Long expectedRevision,
            @NotNull ComparisonContent content) {}

    public record DraftVersion(
            @NotNull @Positive Long draftId, @NotNull @Positive Long expectedRevision) {}
}

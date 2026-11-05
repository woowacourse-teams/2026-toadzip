package com.toadzip.backend.basiccomparison.dto;

import com.toadzip.backend.basiccomparison.domain.ComparisonContent;
import com.toadzip.backend.basiccomparison.domain.ComparisonProfile;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

public final class ComparisonRequests {
    private ComparisonRequests() {}

    public record SaveProfile(
            @NotNull @PositiveOrZero Long expectedVersion,
            @NotNull @Min(2) @Max(2) Integer inputContractVersion,
            @NotNull ComparisonProfile profile) {}

    public record SaveDraft(
            @Positive Long expectedDraftId,
            @NotNull @PositiveOrZero Long expectedRevision,
            @NotNull ComparisonContent content) {}

    public record DraftVersion(
            @NotNull @Positive Long draftId, @NotNull @Positive Long expectedRevision) {}
}

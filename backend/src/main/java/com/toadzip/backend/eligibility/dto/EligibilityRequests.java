package com.toadzip.backend.eligibility.dto;

import com.toadzip.backend.eligibility.domain.EligibilityContent;
import com.toadzip.backend.eligibility.domain.EligibilityProfile;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

public final class EligibilityRequests {
    private EligibilityRequests() {}

    public record SaveProfile(
            @NotNull @PositiveOrZero Long expectedVersion,
            @NotNull @Min(3) @Max(3) Integer inputContractVersion,
            @NotNull EligibilityProfile profile) {}

    public record SaveDraft(
            @Positive Long expectedDraftId,
            @NotNull @PositiveOrZero Long expectedRevision,
            @NotNull EligibilityContent content) {}

    public record DraftVersion(
            @NotNull @Positive Long draftId, @NotNull @Positive Long expectedRevision) {}
}

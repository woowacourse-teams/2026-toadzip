package com.toadzip.backend.eligibility.dto;

import com.toadzip.backend.eligibility.domain.InputFact;
import com.toadzip.backend.eligibility.domain.InputValue;
import com.toadzip.backend.eligibility.domain.RuleSetContent;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.Map;

public final class EligibilityRequests {
    private EligibilityRequests() {}

    public record SaveProfile(
            @NotNull @PositiveOrZero Long expectedVersion,
            @NotNull Map<InputFact, InputValue> facts) {}

    public record SaveDraft(
            @Positive Long expectedDraftId,
            @NotNull @PositiveOrZero Long expectedRevision,
            @NotNull RuleSetContent content) {}

    public record DraftVersion(
            @NotNull @Positive Long draftId, @NotNull @Positive Long expectedRevision) {}
}

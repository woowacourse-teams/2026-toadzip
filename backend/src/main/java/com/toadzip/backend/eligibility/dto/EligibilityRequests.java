package com.toadzip.backend.eligibility.dto;

import com.toadzip.backend.eligibility.domain.EligibilityContent;
import com.toadzip.backend.eligibility.domain.EligibilityProfile;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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

    /** 최신 PDF 추출 결과로 초안 만들기. 화면이 본 PDF(pdfSha256)가 최신 추출과 다르면 거절한다. 초안이 있으면 그 ID·revision이어야 덮어쓴다. */
    public record DraftFromExtraction(
            @NotNull @Pattern(regexp = "[a-fA-F0-9]{64}") String pdfSha256,
            @Positive Long expectedDraftId,
            @NotNull @PositiveOrZero Long expectedRevision) {}

    public record DraftVersion(
            @NotNull @Positive Long draftId, @NotNull @Positive Long expectedRevision) {}
}

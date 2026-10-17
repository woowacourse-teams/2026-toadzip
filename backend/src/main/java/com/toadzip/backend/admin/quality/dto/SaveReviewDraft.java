package com.toadzip.backend.admin.quality.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SaveReviewDraft(
        @NotBlank @Pattern(regexp = "basic|(?:type|row|target):[1-9][0-9]{0,17}") String target,
        @Min(0) long expectedRevision, @Min(0) long documentVersion,
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String sha256,
        @NotBlank @Size(max = 2048) String sourceUrl,
        @NotBlank @Size(max = 500) @Pattern(regexp = "[0-9,\\s-]+") String pages,
        @NotNull @Size(max = 1000000) String resultJson, @NotNull @Size(max = 2000) String note) { }

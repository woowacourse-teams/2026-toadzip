package com.toadzip.backend.housing.dto.request;

import com.toadzip.backend.housing.domain.ComplexReviewOutcome;
import com.toadzip.backend.housing.domain.ComplexVerificationField;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ComplexReviewRequest(
        @NotNull @Min(0) Long version,
        @NotNull @Min(0) Long reviewId,
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String snapshotToken,
        @NotEmpty @Size(max = 6) List<@NotNull ComplexVerificationField> fields,
        @NotNull ComplexReviewOutcome outcome,
        @Size(max = 2048) @Pattern(regexp = "(?i)^(?:https?://[^\\s]+)?$") String evidenceUrl,
        @NotBlank @Size(max = 2000) String evidenceNote
) { }

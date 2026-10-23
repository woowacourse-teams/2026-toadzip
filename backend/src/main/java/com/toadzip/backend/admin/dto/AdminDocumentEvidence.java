package com.toadzip.backend.admin.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 관리자가 직접 확인한 PDF 근거. 서버가 원문을 자동 검증했다는 의미는 아니다. */
public record AdminDocumentEvidence(
        @NotBlank @Size(max = 2048) @Pattern(regexp = "^https?://[^\\s]+$") String sourceUrl,
        @NotBlank @Size(max = 255) String fileName,
        @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String sha256,
        @NotBlank @Size(max = 2000) String note,
        @NotNull @Size(min = 1, max = 100) List<@NotNull @Valid Citation> citations) {
    public record Citation(
            @NotBlank @Size(max = 100) @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9.]*$") String field,
            @NotNull @Positive @Max(10000) Integer page,
            @NotBlank @Size(max = 2000) String quote) { }
}

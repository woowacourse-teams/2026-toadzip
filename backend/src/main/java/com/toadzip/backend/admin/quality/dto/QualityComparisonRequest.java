package com.toadzip.backend.admin.quality.dto;

import com.toadzip.backend.ingest.source.dto.IngestSourceCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

public final class QualityComparisonRequest {
    private QualityComparisonRequest() { }

    public record Update(@NotBlank @Pattern(regexp = "basic|(?:type|row|target):[1-9][0-9]{0,17}") String target,
            @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String snapshotToken,
            @NotNull Map<String, Object> values, @NotBlank @Size(max = 2000) String evidenceNote) { }

    public record Link(@NotBlank @Pattern(regexp = "basic|(?:type|row|target):[1-9][0-9]{0,17}") String target,
            @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String snapshotToken,
            @NotNull IngestSourceCategory category, @NotBlank @Size(max = 512) String sourceKey,
            @NotBlank @Size(max = 2000) String evidenceNote) { }

    public record Check(@NotBlank @Pattern(regexp = "basic|(?:type|row|target):[1-9][0-9]{0,17}") String target,
            @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String snapshotToken,
            @NotBlank @Size(max = 2000) String evidenceNote) { }
}

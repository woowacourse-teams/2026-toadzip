package com.toadzip.backend.admin.quality.dto;

import com.toadzip.backend.ingest.source.dto.IngestSourceCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

public final class QualityComparisonRequest {
    /** 단지·공고 기본값(basic) 또는 주택형·공급행·공급대상 하나 */
    public static final String TARGET = "basic|(?:type|row|target):[1-9][0-9]{0,17}";
    public static final String SNAPSHOT_TOKEN = "[a-f0-9]{64}";

    private QualityComparisonRequest() { }

    public record Update(@NotBlank @Pattern(regexp = TARGET) String target,
            @NotBlank @Pattern(regexp = SNAPSHOT_TOKEN) String snapshotToken,
            @NotNull Map<String, Object> values, @NotBlank @Size(max = 2000) String evidenceNote) { }

    public record Link(@NotBlank @Pattern(regexp = TARGET) String target,
            @NotBlank @Pattern(regexp = SNAPSHOT_TOKEN) String snapshotToken,
            @NotNull IngestSourceCategory category, @NotBlank @Size(max = 512) String sourceKey,
            @NotBlank @Size(max = 2000) String evidenceNote) { }

    public record Check(@NotBlank @Pattern(regexp = TARGET) String target,
            @NotBlank @Pattern(regexp = SNAPSHOT_TOKEN) String snapshotToken,
            @NotBlank @Size(max = 2000) String evidenceNote) { }
}

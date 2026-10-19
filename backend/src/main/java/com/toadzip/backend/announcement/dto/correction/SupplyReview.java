package com.toadzip.backend.announcement.dto.correction;

import com.toadzip.backend.ingest.source.dto.IngestSourceCategory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

public final class SupplyReview {
    private SupplyReview() { }
    public record SourceScope(@NotNull IngestSourceCategory category, @NotBlank String sourceKey,
            @NotEmpty @Size(max = 100) List<@NotBlank String> fields) { }
    public record Request(@NotBlank String snapshotToken,
            @NotBlank @Pattern(regexp = "(?:row|target):[1-9][0-9]*") String target,
            @NotEmpty @Size(max = 30) List<@NotBlank String> fields,
            @NotBlank @Pattern(regexp = "VERIFIED|ON_HOLD") String outcome,
            @NotBlank @Size(max = 2000) String note,
            @NotBlank @Size(max = 2048) @Pattern(regexp = "^https?://[^\\s]+$") String sourceUrl,
            @Size(max = 100) String sourcePage,
            @NotNull @Size(max = 20) List<@NotNull @Valid SourceScope> sources) { }
    public record Item(long id, String target, String outcome, String status, boolean latest,
            JsonNode checkedValues, JsonNode currentValues, JsonNode evidence, JsonNode currentEvidence,
            String note, String sourceUrl, String sourcePage, String actor, Instant reviewedAt) { }
    public record Overview(Map<String, JsonNode> values, List<Item> records) { }
}

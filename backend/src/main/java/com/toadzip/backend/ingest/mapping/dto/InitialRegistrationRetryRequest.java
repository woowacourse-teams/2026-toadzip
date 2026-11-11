package com.toadzip.backend.ingest.mapping.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record InitialRegistrationRetryRequest(
        @NotEmpty @Size(max = 10000) List<@NotBlank @Size(max = 255)
                @Pattern(regexp = "[0-9]+:[A-Z_]+") String> sourceComplexIdentifiers) {
}

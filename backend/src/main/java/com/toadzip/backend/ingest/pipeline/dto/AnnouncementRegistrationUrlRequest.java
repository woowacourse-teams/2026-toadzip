package com.toadzip.backend.ingest.pipeline.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AnnouncementRegistrationUrlRequest(
        @NotBlank @Size(max = 2048) String url
) {
}

package com.toadzip.backend.announcement.dto.request;

import jakarta.validation.constraints.NotNull;

public record PdfAutoExtractionChangeRequest(@NotNull Boolean enabled) {
}

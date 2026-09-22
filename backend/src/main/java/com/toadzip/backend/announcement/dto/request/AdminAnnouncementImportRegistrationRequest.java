package com.toadzip.backend.announcement.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

public record AdminAnnouncementImportRegistrationRequest(
        @NotNull(message = "필수 값입니다.") @Valid AdminAnnouncementImportRequest importData,
        @NotEmpty(message = "한 개 이상이어야 합니다.") @Size(max = 200, message = "200개 이하여야 합니다.")
        List<@NotNull(message = "필수 값입니다.") @Valid ComplexSelectionRequest> complexSelections
) implements RejectUnknownJsonFields {

    public record ComplexSelectionRequest(
            @PositiveOrZero(message = "0 이상이어야 합니다.") int supplyRowIndex,
            @NotNull(message = "필수 값입니다.") @Positive(message = "양수여야 합니다.") Long housingComplexId
    ) implements RejectUnknownJsonFields {
    }
}

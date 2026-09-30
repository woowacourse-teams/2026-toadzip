package com.toadzip.backend.announcement.dto.request;

import com.toadzip.backend.announcement.domain.AttachmentType;
import com.toadzip.backend.announcement.domain.ReceptionMethod;
import com.toadzip.backend.announcement.domain.RecruitmentType;
import com.toadzip.backend.announcement.domain.ScheduleType;
import com.toadzip.backend.announcement.domain.SupplyCategory;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.RentalType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;

public record AdminAnnouncementImportRequest(
        @NotBlank(message = "필수 값입니다.")
        @Pattern(regexp = "admin-announcement-import/v1", message = "지원하지 않는 스키마 버전입니다.")
        String schemaVersion,
        @NotNull(message = "필수 값입니다.") @Valid SourceRequest source,
        @NotNull(message = "필수 값입니다.") @Valid AnnouncementRequest announcement,
        @NotNull(message = "필수 값입니다.") @Valid ReceptionPlaceRequest receptionPlace,
        @NotEmpty(message = "한 개 이상이어야 합니다.") @Size(max = 200, message = "200개 이하여야 합니다.")
        List<@NotNull(message = "필수 값입니다.") @Valid SupplyRowRequest> supplyRows,
        @NotNull(message = "필수 값입니다.") @Size(max = 100, message = "100개 이하여야 합니다.")
        List<@NotNull(message = "필수 값입니다.") @Valid ScheduleRequest> schedules,
        @NotNull(message = "필수 값입니다.") @Size(max = 100, message = "100개 이하여야 합니다.")
        List<@NotNull(message = "필수 값입니다.") @Valid AttachmentRequest> attachments,
        @NotNull(message = "필수 값입니다.") @Size(max = 200, message = "200개 이하여야 합니다.")
        List<@NotNull(message = "필수 값입니다.") @Valid UnresolvedFieldRequest> unresolvedFields
) implements RejectUnknownJsonFields {

    public record SourceRequest(
            @NotBlank(message = "필수 값입니다.") @Size(max = 255, message = "255자 이하여야 합니다.")
            @Pattern(regexp = "^https?://[^\\s]+$", message = "HTTP(S) URL이어야 합니다.")
            String originalUrl,
            @Size(max = 255, message = "255자 이하여야 합니다.")
            @Pattern(regexp = ".*\\S.*", message = "비어 있을 수 없습니다.") String sourceDocumentId,
            @NotNull(message = "필수 값입니다.") OffsetDateTime extractedAt
    ) implements RejectUnknownJsonFields {
    }

    public record AnnouncementRequest(
            @NotBlank(message = "필수 값입니다.") @Size(max = 255, message = "255자 이하여야 합니다.")
            String name,
            @NotNull(message = "필수 값입니다.") RentalType rentalType,
            @NotNull(message = "필수 값입니다.") RecruitmentType recruitmentType,
            @NotNull(message = "필수 값입니다.") AgencyCode agencyCode,
            @NotNull(message = "필수 값입니다.") LocalDate postedDate,
            @NotNull(message = "필수 값입니다.") LocalDate applicationStartDate,
            @NotNull(message = "필수 값입니다.") LocalDate applicationEndDate,
            @NotNull(message = "필수 값입니다.") LocalDate winnerAnnouncementDate
    ) implements RejectUnknownJsonFields {
    }

    public record ReceptionPlaceRequest(
            @NotBlank(message = "필수 값입니다.") @Size(max = 255, message = "255자 이하여야 합니다.")
            String name,
            @NotNull(message = "필수 값입니다.") ReceptionMethod method,
            @Size(max = 255, message = "255자 이하여야 합니다.") String address,
            @NotBlank(message = "필수 값입니다.") @Size(max = 255, message = "255자 이하여야 합니다.")
            String contact,
            @Size(max = 255, message = "255자 이하여야 합니다.")
            @Pattern(regexp = "^https?://[^\\s]+$", message = "HTTP(S) URL이어야 합니다.")
            String url
    ) implements RejectUnknownJsonFields {
    }

    public record SupplyRowRequest(
            @NotNull(message = "필수 값입니다.") @Valid ComplexReferenceRequest complexReference,
            @NotBlank(message = "필수 값입니다.") @Size(max = 255, message = "255자 이하여야 합니다.")
            String sourceHousingTypeName,
            YearMonth expectedMoveInMonth,
            @NotNull(message = "필수 값입니다.") SupplyCategory supplyCategory,
            @NotNull(message = "필수 값입니다.") @PositiveOrZero(message = "0 이상이어야 합니다.")
            Integer totalSupplyHouseholdCount,
            @NotNull(message = "필수 값입니다.") @Size(max = 100, message = "100개 이하여야 합니다.")
            List<@NotNull(message = "필수 값입니다.") @Valid SupplyTargetRequest> targets
    ) implements RejectUnknownJsonFields {
    }

    public record ComplexReferenceRequest(
            @NotBlank(message = "필수 값입니다.") @Size(max = 255, message = "255자 이하여야 합니다.")
            String sourceComplexName,
            @NotBlank(message = "필수 값입니다.")
            @Pattern(regexp = "^[0-9]{19}$", message = "19자리 숫자여야 합니다.")
            String pnu
    ) implements RejectUnknownJsonFields {
    }

    public record SupplyTargetRequest(
            @NotBlank(message = "필수 값입니다.") @Size(max = 255, message = "255자 이하여야 합니다.")
            String target,
            @Size(max = 255, message = "255자 이하여야 합니다.")
            @Pattern(regexp = ".*\\S.*", message = "비어 있을 수 없습니다.") String supplyRank,
            @PositiveOrZero(message = "0 이상이어야 합니다.") Integer supplyHouseholdCount,
            @PositiveOrZero(message = "0 이상이어야 합니다.") Integer reserveCount,
            @PositiveOrZero(message = "0 이상이어야 합니다.") @Digits(integer = 18, fraction = 0)
            BigDecimal rentalDeposit,
            @PositiveOrZero(message = "0 이상이어야 합니다.") @Digits(integer = 18, fraction = 0)
            BigDecimal monthlyRent,
            @PositiveOrZero(message = "0 이상이어야 합니다.") @Digits(integer = 18, fraction = 0)
            BigDecimal convertedDeposit,
            @Size(max = 255, message = "255자 이하여야 합니다.")
            @Pattern(regexp = ".*\\S.*", message = "비어 있을 수 없습니다.") String applicationCondition
    ) implements RejectUnknownJsonFields {
    }

    public record ScheduleRequest(
            @NotNull(message = "필수 값입니다.") ScheduleType scheduleType,
            @NotBlank(message = "필수 값입니다.") @Size(max = 255, message = "255자 이하여야 합니다.")
            String name,
            @NotNull(message = "필수 값입니다.") LocalDateTime startAt,
            @NotNull(message = "필수 값입니다.") LocalDateTime endAt
    ) implements RejectUnknownJsonFields {
    }

    public record AttachmentRequest(
            @NotBlank(message = "필수 값입니다.") @Size(max = 255, message = "255자 이하여야 합니다.")
            String fileName,
            @NotNull(message = "필수 값입니다.") AttachmentType fileType,
            @NotBlank(message = "필수 값입니다.") @Size(max = 255, message = "255자 이하여야 합니다.")
            @Pattern(regexp = "^https?://[^\\s]+$", message = "HTTP(S) URL이어야 합니다.")
            String fileUrl
    ) implements RejectUnknownJsonFields {
    }

    public record UnresolvedFieldRequest(
            @NotBlank(message = "필수 값입니다.") @Size(max = 500, message = "500자 이하여야 합니다.")
            String path,
            @NotBlank(message = "필수 값입니다.") @Size(max = 1000, message = "1000자 이하여야 합니다.")
            String reason
    ) implements RejectUnknownJsonFields {
    }
}

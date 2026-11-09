package com.toadzip.backend.announcement.dto.request;

import com.toadzip.backend.announcement.domain.AttachmentType;
import com.toadzip.backend.announcement.domain.ReceptionMethod;
import com.toadzip.backend.announcement.domain.ReceptionPlace;
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
        @NotEmpty(message = "한 개 이상이어야 합니다.") @Size(max = 20, message = "20개 이하여야 합니다.")
        List<@NotNull(message = "필수 값입니다.") @Valid ReceptionPlaceRequest> receptionPlaces,
        /** 접수처를 한 곳만 받던 예전 JSON의 접수처. 읽을 때 receptionPlaces로 옮기고 비운다. */
        @Valid ReceptionPlaceRequest receptionPlace,
        @NotEmpty(message = "한 개 이상이어야 합니다.") @Size(max = 200, message = "200개 이하여야 합니다.")
        List<@NotNull(message = "필수 값입니다.") @Valid SupplyRowRequest> supplyRows,
        @NotNull(message = "필수 값입니다.") @Size(max = 100, message = "100개 이하여야 합니다.")
        List<@NotNull(message = "필수 값입니다.") @Valid ScheduleRequest> schedules,
        @NotNull(message = "필수 값입니다.") @Size(max = 100, message = "100개 이하여야 합니다.")
        List<@NotNull(message = "필수 값입니다.") @Valid AttachmentRequest> attachments,
        @NotNull(message = "필수 값입니다.") @Size(max = 200, message = "200개 이하여야 합니다.")
        List<@NotNull(message = "필수 값입니다.") @Valid UnresolvedFieldRequest> unresolvedFields
) implements RejectUnknownJsonFields {

    public AdminAnnouncementImportRequest {
        if (receptionPlaces == null && receptionPlace != null) {
            receptionPlaces = List.of(receptionPlace);
            // 예전 JSON의 미확인 경로(receptionPlace.contact)도 첫 접수처 경로로 읽어 미확인 값이 적용되지 않게 한다.
            if (unresolvedFields != null) {
                unresolvedFields = unresolvedFields.stream().map(field -> field == null || field.path() == null ? field
                        : new UnresolvedFieldRequest(field.path().replaceFirst("^(\\$\\.)?receptionPlace(?=$|[.\\[])", "$1receptionPlaces[0]"),
                        field.reason())).toList();
            }
        }
        receptionPlace = null;
    }

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
            // 인터넷 접수처는 전화번호 없이 주소(URL)만 있는 경우가 많아 연락처를 접수처마다 요구하지 않는다.
            @Size(max = 255, message = "255자 이하여야 합니다.") String contact,
            @Size(max = 255, message = "255자 이하여야 합니다.")
            @Pattern(regexp = "^https?://[^\\s]+$", message = "HTTP(S) URL이어야 합니다.")
            String url,
            @Size(max = 1000, message = "1000자 이하여야 합니다.") String guidance
    ) implements RejectUnknownJsonFields {

        public ReceptionPlace toPlace() {
            return ReceptionPlace.create(name, method, address, contact, url, guidance);
        }
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
            List<@NotNull(message = "필수 값입니다.") @Valid SupplyTargetRequest> targets,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            @PositiveOrZero @Digits(integer = 6, fraction = 4) BigDecimal exclusiveArea,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            @PositiveOrZero @Digits(integer = 6, fraction = 4) BigDecimal supplyArea
    ) implements RejectUnknownJsonFields {
        public SupplyRowRequest(ComplexReferenceRequest complexReference, String sourceHousingTypeName,
                YearMonth expectedMoveInMonth, SupplyCategory supplyCategory, Integer totalSupplyHouseholdCount,
                List<SupplyTargetRequest> targets) {
            this(complexReference, sourceHousingTypeName, expectedMoveInMonth, supplyCategory,
                    totalSupplyHouseholdCount, targets, null, null);
        }
    }

    public record ComplexReferenceRequest(
            @NotBlank(message = "필수 값입니다.") @Size(max = 255, message = "255자 이하여야 합니다.")
            String sourceComplexName,
            /** 지번을 모르는 단지는 비운다. 값이 있으면 19자리 숫자다. */
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

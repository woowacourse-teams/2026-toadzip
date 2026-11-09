package com.toadzip.backend.announcement.dto.request;

import com.toadzip.backend.announcement.domain.RecruitmentType;
import com.toadzip.backend.announcement.domain.ReceptionMethod;
import com.toadzip.backend.announcement.domain.ReceptionPlace;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.admin.dto.AdminDocumentEvidence;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record AdminAnnouncementUpdateRequest(
        @NotNull(message = "필수 값입니다.")
        @PositiveOrZero Long version,
        @NotBlank(message = "필수 값입니다.")
        @Size(max = 255, message = "255자 이하여야 합니다.")
        String name,
        @NotNull(message = "필수 값입니다.")
        RentalType rentalType,
        @NotNull(message = "필수 값입니다.")
        RecruitmentType recruitmentType,
        @NotNull(message = "필수 값입니다.")
        AgencyCode agencyCode,
        @NotNull(message = "필수 값입니다.")
        LocalDate postedDate,
        @NotNull(message = "필수 값입니다.")
        LocalDate applicationStartDate,
        @NotNull(message = "필수 값입니다.")
        LocalDate applicationEndDate,
        @NotNull(message = "필수 값입니다.")
        LocalDate winnerAnnouncementDate,
        @NotBlank(message = "필수 값입니다.")
        @Size(max = 255, message = "255자 이하여야 합니다.")
        @Pattern(regexp = "^https?://[^\\s]+$", message = "HTTP(S) URL이어야 합니다.")
        String originalUrl,
        @Size(max = 20, message = "20개 이하여야 합니다.")
        List<@NotNull(message = "필수 값입니다.") @Valid ReceptionPlaceUpdateRequest> receptionPlaces,
        @Valid AdminDocumentEvidence documentEvidence,
        /** 접수처를 한 곳만 보내던 예전 화면의 접수처. 받기만 하고 receptionPlaces로 옮긴다(응답·이력에는 쓰지 않는다). */
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) @Valid ReceptionPlaceUpdateRequest receptionPlace
) {
    /** 접수처를 빼고 보내면 접수처가 없는 공고다. 예전 화면이 보낸 접수처 하나는 목록으로 읽어 지우지 않는다. */
    public AdminAnnouncementUpdateRequest {
        if (receptionPlaces == null && receptionPlace != null) {
            receptionPlaces = List.of(receptionPlace);
        }
        // null 원소도 그대로 두어 검증기가 receptionPlaces[n] 칸 오류로 알리게 한다(List.copyOf는 null에서 예외를 던진다).
        receptionPlaces = receptionPlaces == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(receptionPlaces));
        receptionPlace = null;
    }

    public AdminAnnouncementUpdateRequest(Long version, String name, RentalType rentalType,
            RecruitmentType recruitmentType, AgencyCode agencyCode, LocalDate postedDate,
            LocalDate applicationStartDate, LocalDate applicationEndDate, LocalDate winnerAnnouncementDate,
            String originalUrl, List<ReceptionPlaceUpdateRequest> receptionPlaces) {
        this(version, name, rentalType, recruitmentType, agencyCode, postedDate, applicationStartDate,
                applicationEndDate, winnerAnnouncementDate, originalUrl, receptionPlaces, null, null);
    }

    public List<ReceptionPlace> places() {
        return receptionPlaces.stream().map(ReceptionPlaceUpdateRequest::toPlace).toList();
    }

    public record ReceptionPlaceUpdateRequest(
            @NotBlank(message = "필수 값입니다.") @Size(max = 255) String name,
            @NotNull(message = "필수 값입니다.") ReceptionMethod method,
            @Size(max = 255) String address,
            @Size(max = 255) String contact,
            @Size(max = 255) @Pattern(regexp = "^https?://[^\\s]+$") String url,
            @Size(max = 1000) String guidance
    ) {
        public static ReceptionPlaceUpdateRequest of(ReceptionPlace place) {
            return new ReceptionPlaceUpdateRequest(place.getName(), place.getMethod(), place.getAddress(),
                    place.getContact(), place.getUrl(), place.getGuidance());
        }

        ReceptionPlace toPlace() {
            return ReceptionPlace.create(name, method, address, contact, url, guidance);
        }
    }

}

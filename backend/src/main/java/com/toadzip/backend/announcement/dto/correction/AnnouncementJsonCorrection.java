package com.toadzip.backend.announcement.dto.correction;

import com.toadzip.backend.announcement.dto.request.AdminAnnouncementImportRequest;
import com.toadzip.backend.announcement.dto.request.AdminAnnouncementImportRequest.AttachmentRequest;
import com.toadzip.backend.announcement.dto.request.AdminAnnouncementImportRequest.ScheduleRequest;
import com.toadzip.backend.announcement.dto.request.RejectUnknownJsonFields;
import com.toadzip.backend.announcement.dto.response.AdminAnnouncementDetail;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

public final class AnnouncementJsonCorrection {
    private AnnouncementJsonCorrection() { }

    public record Event(long id, ScheduleRequest data, String complexName) { }
    public record Workspace(String snapshotToken, AdminAnnouncementDetail detail,
            SupplyCorrection.Workspace supply, List<Event> events) { }
    public record Selection(@NotNull @PositiveOrZero Integer inputIndex, @Positive Long existingId) { }
    public record Request(@NotBlank @Size(max = 64) String snapshotToken,
            @NotNull AdminAnnouncementImportRequest document,
            @NotNull @Size(max = 20) List<@NotBlank String> fields,
            @NotNull @Size(max = 100) List<@NotNull @Valid Selection> schedules,
            @NotNull @Size(max = 100) List<@NotNull @Valid Selection> attachments,
            @NotNull @Valid SupplyCorrection.Request correction,
            @Size(max = 500) List<@NotNull @Valid HousingTypeValue> housingTypeValues,
            Boolean publish) implements RejectUnknownJsonFields {
        /** publish는 관리자가 단지를 확인하고 미확정 공고를 공개하겠다는 요청이다. 생략하면 공개하지 않는다. */
        public Request {
            if (housingTypeValues == null) { housingTypeValues = List.of(); }
            if (publish == null) { publish = false; }
        }
    }
    /**
     * 공고문에서 읽은 공급행 주택형의 값. 저장할 때 그 행이 고른 주택형(새로 만드는 주택형 포함)에 반영한다.
     * 연결 주소가 없으면 대표 주택형, linkedHousingTypeId는 함께 모집하는 기존 주택형, linkedNewHousingTypeArea는
     * 함께 모집하는 새 주택형(전용면적)이다. 둘 중 하나만 준다. 한 행이 같은 면적의 새 주택형을 여럿 만들면(59A·59A1)
     * linkedNewHousingTypeName(새 주택형 이름)으로 그중 하나를 가리킨다.
     */
    public record HousingTypeValue(@NotBlank @Size(max = 64) String rowKey,
            @Size(max = 255) String name,
            @PositiveOrZero @Digits(integer = 6, fraction = 4) BigDecimal supplyArea,
            @PositiveOrZero Integer householdCount,
            @PositiveOrZero Long basicDeposit,
            @PositiveOrZero Long basicMonthlyRent,
            @Positive Long linkedHousingTypeId,
            @Positive @Digits(integer = 6, fraction = 4) BigDecimal linkedNewHousingTypeArea,
            @Size(max = 255) String linkedNewHousingTypeName) {
        public HousingTypeValue(String rowKey, String name, BigDecimal supplyArea, Integer householdCount) {
            this(rowKey, name, supplyArea, householdCount, null, null, null, null, null);
        }
    }
    public record TypeValues(String name, BigDecimal supplyArea, Integer householdCount, Long basicDeposit,
            Long basicMonthlyRent) {
        public TypeValues(String name, BigDecimal supplyArea, Integer householdCount) {
            this(name, supplyArea, householdCount, null, null);
        }
    }
    /** 저장하면 바뀌는 주택형 값. housingTypeId가 없으면 이 저장에서 새로 만드는 주택형이다. */
    public record HousingTypeChange(String rowKey, Long housingTypeId, String typeName, TypeValues before,
            TypeValues after) { }
    public record Apply(@NotNull @Valid Request request, @NotBlank @Size(max = 64) String confirmationToken) { }
    public record Change(String field, Object before, Object after) { }
    public record EventChange(int inputIndex, Long existingId, ScheduleRequest before, ScheduleRequest after) { }
    public record AttachmentChange(int inputIndex, Long existingId, AttachmentRequest before, AttachmentRequest after) { }
    /** unchanged는 저장할 변경이 하나도 없다는 뜻이다. 적용해도 아무것도 쓰지 않고 지금 값을 돌려준다. */
    public record Preview(String confirmationToken, List<Change> fields, SupplyCorrection.Preview supply,
            List<EventChange> schedules, List<AttachmentChange> attachments, List<HousingTypeChange> housingTypes,
            List<String> housingTypeWarnings, List<String> warnings, boolean publishes, boolean unchanged) { }
}

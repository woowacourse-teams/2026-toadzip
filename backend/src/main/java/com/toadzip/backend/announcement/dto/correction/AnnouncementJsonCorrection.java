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
            @Size(max = 500) List<@NotNull @Valid HousingTypeValue> housingTypeValues) implements RejectUnknownJsonFields {
        public Request {
            if (housingTypeValues == null) { housingTypeValues = List.of(); }
        }
    }
    /** 공고문에서 읽은 공급행 주택형의 값. 저장할 때 그 행이 고른 주택형(새로 만드는 주택형 포함)에 반영한다. */
    public record HousingTypeValue(@NotBlank @Size(max = 64) String rowKey,
            @Size(max = 255) String name,
            @PositiveOrZero @Digits(integer = 6, fraction = 4) BigDecimal supplyArea,
            @PositiveOrZero Integer householdCount) { }
    public record TypeValues(String name, BigDecimal supplyArea, Integer householdCount) { }
    /** 저장하면 바뀌는 주택형 값. housingTypeId가 없으면 이 저장에서 새로 만드는 주택형이다. */
    public record HousingTypeChange(String rowKey, Long housingTypeId, String typeName, TypeValues before,
            TypeValues after) { }
    public record Apply(@NotNull @Valid Request request, @NotBlank @Size(max = 64) String confirmationToken) { }
    public record Change(String field, Object before, Object after) { }
    public record EventChange(int inputIndex, Long existingId, ScheduleRequest before, ScheduleRequest after) { }
    public record AttachmentChange(int inputIndex, Long existingId, AttachmentRequest before, AttachmentRequest after) { }
    public record Preview(String confirmationToken, List<Change> fields, SupplyCorrection.Preview supply,
            List<EventChange> schedules, List<AttachmentChange> attachments, List<HousingTypeChange> housingTypes,
            List<String> housingTypeWarnings, List<String> warnings) { }
}

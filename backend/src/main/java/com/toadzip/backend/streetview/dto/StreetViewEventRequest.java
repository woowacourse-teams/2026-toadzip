package com.toadzip.backend.streetview.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.toadzip.backend.streetview.domain.StreetViewEvent.Phase;
import com.toadzip.backend.streetview.domain.StreetViewEvent.ReasonCode;
import com.toadzip.backend.streetview.domain.StreetViewEvent.Type;
import com.toadzip.backend.streetview.exception.InvalidStreetViewRequestException;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;
import tools.jackson.databind.annotation.JsonDeserialize;

@Schema(description = "최초 거리뷰 초기화의 시작/종료 보고. 원문 오류, URL, 좌표, 개인정보는 전송하지 않습니다.")
public record StreetViewEventRequest(
        @NotNull @Schema(description = "모달 열기마다 생성한 UUID") UUID attemptId,
        @NotNull @Positive @JsonDeserialize(using = StrictEventLongDeserializer.class) Long complexId,
        @NotNull @PositiveOrZero @JsonDeserialize(using = StrictEventLongDeserializer.class) Long policyRevision,
        @NotNull @JsonDeserialize(using = StrictEventEnumDeserializers.EventType.class) Type type,
        @NotNull @JsonDeserialize(using = StrictEventEnumDeserializers.EventPhase.class) Phase phase,
        @JsonDeserialize(using = StrictEventEnumDeserializers.EventReason.class) ReasonCode reasonCode,
        @NotNull @PositiveOrZero @Max(600_000)
        @JsonDeserialize(using = StrictEventLongDeserializer.class)
        @Schema(description = "시도 시작부터 경과한 밀리초. STARTED는 0", minimum = "0", maximum = "600000")
        Long durationMs
) {
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new InvalidStreetViewRequestException("event", "정의되지 않은 필드는 허용하지 않습니다.");
    }
}

package com.toadzip.backend.ingest.pipeline.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AnnouncementRegistrationRequest(
        @NotBlank(message = "마이홈 공고 ID를 입력해 주세요.")
        @Size(max = 100, message = "마이홈 공고 ID는 100자 이하입니다.")
        String pblancId
) {
}

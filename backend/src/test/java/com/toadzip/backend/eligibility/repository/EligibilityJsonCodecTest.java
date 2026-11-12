package com.toadzip.backend.eligibility.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.eligibility.domain.EligibilityContent.RecruitmentStatus;
import com.toadzip.backend.eligibility.domain.EligibilityDecision;
import com.toadzip.backend.eligibility.domain.EligibilityDecision.OptionDecision;
import com.toadzip.backend.eligibility.domain.EligibilityDecision.Status;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class EligibilityJsonCodecTest {
    private final EligibilityJsonCodec codec = new EligibilityJsonCodec(JsonMapper.builder().build(), null);

    @Test
    void 신청_전_확인_안내_필드가_생기기_전에_저장한_판정_결과도_읽는다() {
        OptionDecision option = new OptionDecision("option", "unit", "21A", List.of(), "path", "청년",
                RecruitmentStatus.RECRUITING, Status.ELIGIBLE, "지원할 수 있어요.", null, List.of(), List.of(), List.of(),
                List.of(), List.of("list-path"));
        String stored = codec.write(new EligibilityDecision(Status.ELIGIBLE, "지원할 수 있어요.", LocalDate.of(2026, 9, 30),
                "eligibility-1", List.of(), List.of(), List.of(option)))
                .replace(",\"reminderGuidanceIds\":[\"list-path\"]", "");

        assertThat(stored).doesNotContain("reminderGuidanceIds");
        assertThat(codec.decision(stored).options().getFirst().reminderGuidanceIds()).isEmpty();
    }
}

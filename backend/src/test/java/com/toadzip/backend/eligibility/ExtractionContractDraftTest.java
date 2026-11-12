package com.toadzip.backend.eligibility;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.eligibility.domain.EligibilityContent;
import com.toadzip.backend.eligibility.domain.ExtractedEligibilityDraft;
import com.toadzip.backend.eligibility.domain.ExtractedEligibilityDraft.Document;
import com.toadzip.backend.eligibility.domain.ExtractedEligibilityDraft.Source;
import com.toadzip.backend.eligibility.domain.ExtractedEligibilityDraft.Target;
import com.toadzip.backend.eligibility.domain.ExtractedEligibilityDraft.Unit;
import com.toadzip.backend.eligibility.domain.ProfileField;
import com.toadzip.backend.eligibility.domain.ProfileRule;
import com.toadzip.backend.eligibility.repository.ExtractedEligibilityReader;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * 워커 계약 예시(ai-worker/contract)의 data.eligibility를 서버 해석기와 초안 변환에 그대로 통과시킨다.
 * 해석기는 형식이 틀린 칸을 조용히 비우므로, 워커가 필드 이름을 바꾸면 조건이 직접 확인으로 떨어지는 것을 여기서 잡는다.
 */
class ExtractionContractDraftTest {
    private static final Path EXAMPLE = Path.of("../ai-worker/contract/announcement-pdf-extraction/result.example.json");

    @Test
    void 계약_예시의_신청자격은_소득_자산_나이_조건이_된다() throws Exception {
        JsonMapper json = JsonMapper.builder().build();
        String extraction = json.readTree(Files.readString(EXAMPLE)).path("json").asString();

        EligibilityContent content = ExtractedEligibilityDraft.build(new Source(LocalDate.of(2026, 9, 30),
                new Document("notice.pdf", "https://apply.lh.or.kr/notice.pdf", 35, "a".repeat(64)),
                List.of(new Unit(1, "인천부평", "16A", new BigDecimal("16.37"), null, null, "행복주택", List.of(),
                        List.of(new Target("청년", 78)))),
                new ExtractedEligibilityReader(json).read(extraction),
                Map.of("URBAN_WORKER_MONTHLY_INCOME:2025", 1L), name -> List.of())).content();

        List<ProfileRule> common = content.commonChecks().stream().map(EligibilityContent.ProfileCheck::rule).toList();
        assertThat(common).extracting(ProfileRule::operator).containsExactly(
                ProfileRule.Operator.EQUALS, ProfileRule.Operator.ANY, ProfileRule.Operator.MONEY_CEILING);
        // 출산자녀 가산: 기본 상한 이하이거나, 가산을 받을 수 있는 사람(자녀·임신이 있는 한부모·부부)이면 충족이다.
        ProfileRule income = common.get(1).children().getFirst();
        assertThat(income.operator()).isEqualTo(ProfileRule.Operator.INCOME_CEILING);
        assertThat(income.incomeLimit().standardTableId()).isEqualTo(1L);
        assertThat(income.incomeLimit().percent()).isEqualTo(100);
        assertThat(income.incomeLimit().sizeExtraPercent()).isEqualTo(Map.of(1L, 20, 2L, 10));
        assertThat(common.get(2).maximum()).isEqualTo(337_000_000L);
        ProfileRule age = content.paths().getFirst().checks().getFirst().rule();
        assertThat(List.of(age.field(), age.minimum(), age.maximum())).containsExactly(ProfileField.BIRTH_DATE, 19L, 39L);
        assertThat(content.guidances()).extracting(EligibilityContent.Guidance::kind)
                .contains(EligibilityContent.GuidanceKind.APPLICATION_EXCLUSION);
        // 입력으로 판정할 수 없는 청약통장 요건은 판정을 막지 않는 목록으로만 보인다.
        EligibilityContent.Guidance listed = content.guidances().stream()
                .filter(guidance -> guidance.id().equals("list-path-1")).findFirst().orElseThrow();
        assertThat(listed.kind()).isEqualTo(EligibilityContent.GuidanceKind.REQUIRED_CHECK);
        assertThat(listed.items()).containsExactly("입주 전까지 주택청약종합저축 가입");
        assertThat(content.paths().getFirst().checks()).extracting(EligibilityContent.ProfileCheck::fallbackGuidanceId)
                .doesNotContain(listed.id());
    }
}

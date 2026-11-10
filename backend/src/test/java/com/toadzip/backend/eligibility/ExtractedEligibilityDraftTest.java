package com.toadzip.backend.eligibility;

import static com.toadzip.backend.eligibility.BasicComparisonFixtures.STANDARDS;
import static com.toadzip.backend.eligibility.BasicComparisonFixtures.b;
import static com.toadzip.backend.eligibility.BasicComparisonFixtures.with;
import static com.toadzip.backend.eligibility.domain.ProfileField.APPLICANT_MONTHLY_INCOME;
import static com.toadzip.backend.eligibility.domain.ProfileField.BIRTH_DATE;
import static com.toadzip.backend.eligibility.domain.ProfileField.FAMILY_TYPE;
import static com.toadzip.backend.eligibility.domain.ProfileField.HAS_CHILDREN_OR_PREGNANCY;
import static com.toadzip.backend.eligibility.domain.ProfileField.HOUSEHOLD_MONTHLY_INCOME;
import static com.toadzip.backend.eligibility.domain.ProfileField.IS_DUAL_INCOME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.eligibility.domain.EligibilityContent;
import com.toadzip.backend.eligibility.domain.EligibilityContentValidator;
import com.toadzip.backend.eligibility.domain.EligibilityContent.Guidance;
import com.toadzip.backend.eligibility.domain.EligibilityContent.GuidanceKind;
import com.toadzip.backend.eligibility.domain.EligibilityContent.RecruitmentStatus;
import com.toadzip.backend.eligibility.domain.EligibilityDecision;
import com.toadzip.backend.eligibility.domain.EligibilityDecision.Status;
import com.toadzip.backend.eligibility.domain.EligibilityEvaluator;
import com.toadzip.backend.eligibility.domain.EligibilityProfile;
import com.toadzip.backend.eligibility.domain.ExtractedEligibility;
import com.toadzip.backend.eligibility.domain.ExtractedEligibility.Group;
import com.toadzip.backend.eligibility.domain.ExtractedEligibility.Qualification;
import com.toadzip.backend.eligibility.domain.ExtractedEligibility.Quote;
import com.toadzip.backend.eligibility.domain.ExtractedEligibility.Requirement;
import com.toadzip.backend.eligibility.domain.ExtractedEligibility.Route;
import com.toadzip.backend.eligibility.domain.ExtractedEligibilityDraft;
import com.toadzip.backend.eligibility.domain.ExtractedEligibilityDraft.Document;
import com.toadzip.backend.eligibility.domain.ExtractedEligibilityDraft.Source;
import com.toadzip.backend.eligibility.domain.ExtractedEligibilityDraft.Target;
import com.toadzip.backend.eligibility.domain.ExtractedEligibilityDraft.Unit;
import com.toadzip.backend.eligibility.domain.RegionMembership;
import com.toadzip.backend.eligibility.exception.EligibilityDraftUnavailableException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** AI 추출 신청자격으로 만든 초안을 실제 판정기에 넣어, 변환이 판정 결과를 넓히거나 좁히지 않는지 본다. */
class ExtractedEligibilityDraftTest {
    private static final LocalDate POSTED = LocalDate.of(2026, 9, 30);
    private static final String COMMON = "eligibility.common";
    private static final String YOUTH = "eligibility.targets[0]";
    private static final String COUPLE = "eligibility.targets[1]";
    private static final String WELFARE = "eligibility.targets[2]";
    private static final String MISSING = "eligibility.targets[3]";

    private final RegionMembership regions = new RegionMembership() {
        public Match match(Set<String> members, String supplied) {
            return members.stream().anyMatch(supplied::startsWith) ? Match.MATCH : Match.NO_MATCH;
        }

        public boolean isKnown(String code) {
            return code != null;
        }
    };

    @Test
    void 청년_나이_경계와_세대_소득_상한을_공고_기준일과_기준표로_판정한다() {
        EligibilityContent content = draft(evidence());

        // 공고 기준일 2026-09-30에 만 39세(1986-10-01생)는 충족, 만 40세(1986-09-30생)는 미충족.
        assertThat(youth(content, with(b(), BIRTH_DATE, LocalDate.of(1986, 10, 1)))).isEqualTo(Status.ELIGIBLE);
        assertThat(youth(content, with(b(), BIRTH_DATE, LocalDate.of(1986, 9, 30)))).isEqualTo(Status.INELIGIBLE);
        // 2025 도시근로자 2인 5,866,270원 × (100% + 2인 가산 10%) = 6,452,897원 이하만 충족.
        assertThat(youth(content, with(b(), HOUSEHOLD_MONTHLY_INCOME, 6_452_897L))).isEqualTo(Status.ELIGIBLE);
        assertThat(youth(content, with(b(), HOUSEHOLD_MONTHLY_INCOME, 6_452_898L))).isEqualTo(Status.INELIGIBLE);
    }

    @Test
    void 출산자녀_가산_대상은_소득_상한을_넘어도_미충족이_아니라_판단_보류다() {
        EligibilityContent content = draft(evidence());
        EligibilityProfile overLimitWithChild = with(with(b(), HOUSEHOLD_MONTHLY_INCOME, 6_452_898L),
                HAS_CHILDREN_OR_PREGNANCY, true);

        assertThat(youth(content, overLimitWithChild)).isEqualTo(Status.UNDETERMINED);
    }

    @Test
    void 조건으로_바꾸지_못한_요건이_있는_경로는_지원_가능으로_확정하지_않는다() {
        EligibilityContent content = draft(evidence());

        // 부부 형태는 충족하지만 청약통장(기타)과 워커가 비운 자산 상한은 자동 판정하지 못한다.
        assertThat(status(content, b(), "신혼부부")).isEqualTo(Status.UNDETERMINED);
        Guidance direct = content.guidances().stream().filter(guidance -> guidance.id().equals("direct-path-2"))
                .findFirst().orElseThrow();
        assertThat(direct.kind()).isEqualTo(GuidanceKind.REQUIRED_QUALIFICATION);
        assertThat(direct.items()).containsExactly("입주 전까지 주택청약종합저축 가입", "총자산 34,500만원 이하");
    }

    @Test
    void 법정_자격_목록은_자격마다_경로를_나누고_공급대상과_이은_경로만_만든다() {
        EligibilityContent content = draft(evidence());

        assertThat(content.paths()).extracting(EligibilityContent.EligibilityPath::label).containsExactly(
                "청년", "신혼부부", "주거급여수급자 · 주거급여 수급자", "주거급여수급자 · 생계급여 수급자");
        assertThat(content.paths().subList(2, 4)).allSatisfy(path -> assertThat(path.qualificationIds()).hasSize(1));
        assertThat(content.metadata().scopeNote()).doesNotContain("AI").doesNotContain("잇지 못한");
        assertThat(build(evidence()).notices()).containsExactly("공급대상과 잇지 못한 신청자격 경로: 사라진 대상");
        // 모집수 0인 대상(2번 공급행의 청년)은 선택지가 없고, 모집수를 모르는 대상(2번 공급행의 신혼부부)은 UNKNOWN이다.
        assertThat(content.options()).extracting(EligibilityContent.ApplicationOption::id).containsExactly(
                "path-1-unit-1", "path-2-unit-1", "path-2-unit-2", "path-3-unit-1", "path-4-unit-1");
        assertThat(content.options()).extracting(EligibilityContent.ApplicationOption::recruitmentStatus)
                .containsExactly(RecruitmentStatus.RECRUITING, RecruitmentStatus.RECRUITING, RecruitmentStatus.UNKNOWN,
                        RecruitmentStatus.RECRUITING, RecruitmentStatus.RECRUITING);
        // 저장된 대상 이름(주거급여  수급자)이 워커가 맞춘 이름(주거급여 수급자)과 공백만 달라도 잇는다.
        assertThat(status(content, b(), "주거급여수급자 · 주거급여 수급자")).isEqualTo(Status.UNDETERMINED);
    }

    @Test
    void 미혼은_출산자녀_가산_입력이_없어도_소득을_자동_판정한다() {
        EligibilityContent content = draft(evidence());
        EligibilityProfile unmarried = with(b(), FAMILY_TYPE, "UNMARRIED");

        // 자녀 입력을 받지 않는 미혼은 출산자녀 가산 대상이 아니므로 기본 상한으로 판정한다.
        assertThat(youth(content, with(unmarried, HOUSEHOLD_MONTHLY_INCOME, 6_452_897L))).isEqualTo(Status.ELIGIBLE);
        assertThat(youth(content, with(unmarried, HOUSEHOLD_MONTHLY_INCOME, 6_452_898L))).isEqualTo(Status.INELIGIBLE);
    }

    @Test
    void 맞벌이_가산은_맞벌이_부부만_직접_확인하고_한부모와_맞벌이_아닌_부부는_자동_판정한다() {
        EligibilityContent content = draft(evidence(), dualIncome());
        EligibilityProfile couple = with(b(), HOUSEHOLD_MONTHLY_INCOME, 6_000_000L);

        // 맞벌이 경로 기준(2인 5,866,270원)을 넘는 600만원: 맞벌이 아닌 부부는 미충족, 맞벌이 부부는 가산 기준이라 판단 보류.
        assertThat(youth(content, couple)).isEqualTo(Status.INELIGIBLE);
        assertThat(youth(content, with(couple, IS_DUAL_INCOME, true))).isEqualTo(Status.UNDETERMINED);
        // 한부모는 맞벌이 입력을 받지 않으므로 맞벌이 가산 대상이 아니라 기본 상한으로 판정한다.
        EligibilityProfile singleParent = with(b(), FAMILY_TYPE, "SINGLE_PARENT");
        assertThat(youth(content, with(singleParent, HOUSEHOLD_MONTHLY_INCOME, 5_000_000L))).isEqualTo(Status.ELIGIBLE);
        assertThat(youth(content, with(singleParent, HOUSEHOLD_MONTHLY_INCOME, 6_000_000L))).isEqualTo(Status.INELIGIBLE);
    }

    @Test
    void 본인_소득_기준은_가구원_수를_알_수_없어_직접_확인으로_둔다() {
        EligibilityContent content = draft(evidence(), applicantIncome());

        // 본인 소득 300만원은 등본 가족 수 기준으로는 충족이지만, 몇 인 가구 금액인지 몰라 판단 보류다.
        assertThat(youth(content, with(b(), APPLICANT_MONTHLY_INCOME, 3_000_000L))).isEqualTo(Status.UNDETERMINED);
        assertThat(content.paths().getFirst().checks()).extracting(EligibilityContent.ProfileCheck::label)
                .containsExactly("만 19세 이상 39세 이하");
    }

    @Test
    void 신청자격_경로가_없는_공급대상은_검토_안내로_알린다() {
        var units = List.of(new Unit(1, "인천석남", "21A", new BigDecimal("21.00"), 10L, 20L, "행복주택", List.of("28237"),
                List.of(new Target("청년", 10), new Target("고령자", 4), new Target("일반", 0))));

        var built = ExtractedEligibilityDraft.build(source(evidence(), units, List.of()));

        assertThat(built.notices()).contains("신청자격 경로가 없는 공급대상: 고령자. 공고문을 확인해 주세요.");
    }

    @Test
    void 신청자격_근거가_없으면_초안을_만들지_않는다() {
        assertThatThrownBy(() -> draft(Map.of())).isInstanceOf(EligibilityDraftUnavailableException.class)
                .hasMessageContaining("근거가 없어");
    }

    private Status youth(EligibilityContent content, EligibilityProfile profile) {
        return status(content, profile, "청년");
    }

    private Status status(EligibilityContent content, EligibilityProfile profile, String pathLabel) {
        EligibilityDecision decision = new EligibilityEvaluator().evaluate(content, profile, STANDARDS, regions);
        return decision.options().stream().filter(option -> option.pathLabel().equals(pathLabel))
                .findFirst().orElseThrow().status();
    }

    private static EligibilityContent draft(Map<String, Quote> quotes) {
        return draft(quotes, List.of());
    }

    /** 변환한 초안은 관리자 저장과 같은 검증기를 통과해야 한다. */
    private static EligibilityContent draft(Map<String, Quote> quotes, List<Requirement> youthExtra) {
        EligibilityContent content = ExtractedEligibilityDraft.build(source(quotes, null, youthExtra)).content();
        new EligibilityContentValidator(new RegionMembership() {
            public Match match(Set<String> members, String supplied) {
                return Match.NO_MATCH;
            }

            public boolean isKnown(String code) {
                return code != null;
            }
        }, Set.of(1L)).validate(content, false);
        return content;
    }

    private static ExtractedEligibilityDraft.Draft build(Map<String, Quote> quotes) {
        return ExtractedEligibilityDraft.build(source(quotes, null, List.of()));
    }

    private static Source source(Map<String, Quote> quotes, List<Unit> customUnits, List<Requirement> youthExtra) {
        Group common = new Group(COMMON, List.of(
                requirement("NO_HOUSE", "HOUSEHOLD", COMMON + ".requirements[0]", "무주택세대구성원"),
                income(COMMON + ".requirements[1]")), List.of());
        List<Route> routes = List.of(
                new Route("청년", List.of("청년"), new Group(YOUTH, Stream.concat(
                        Stream.of(age(YOUTH + ".requirements[0]")), youthExtra.stream()).toList(), List.of())),
                new Route("신혼부부", List.of("신혼부부·한부모가족"), new Group(COUPLE, List.of(
                        family(COUPLE + ".requirements[0]"),
                        requirement("OTHER", null, COUPLE + ".requirements[1]", "입주 전까지 주택청약종합저축 가입"),
                        requirement("ASSETS", "HOUSEHOLD", COUPLE + ".requirements[2]", "총자산 34,500만원 이하")),
                        List.of())),
                new Route("주거급여수급자", List.of("주거급여 수급자"), new Group(WELFARE, List.of(), List.of(
                        new Qualification("주거급여 수급자", "「주거급여법」", "수급권자 또는 수급자", WELFARE + ".qualifications[0]"),
                        new Qualification("생계급여 수급자", null, "수급자", WELFARE + ".qualifications[1]")))),
                new Route("사라진 대상", List.of(), new Group(MISSING, List.of(), List.of())));
        ExtractedEligibility eligibility = new ExtractedEligibility(common, routes, List.of(), quotes);
        List<Unit> units = customUnits != null ? customUnits : List.of(
                new Unit(1, "인천석남", "21A", new BigDecimal("21.00"), 10L, 20L, "행복주택", List.of("28237"), List.of(
                        new Target("청년", 10), new Target("신혼부부·한부모가족", 5), new Target("주거급여  수급자", 3))),
                new Unit(2, "인천석남", "36A", new BigDecimal("36.00"), 10L, 21L, "행복주택", List.of("28237"),
                        List.of(new Target("청년", 0), new Target("신혼부부·한부모가족", null))));
        return new Source(POSTED,
                new Document("notice.pdf", "https://apply.lh.or.kr/notice.pdf", 23, "a".repeat(64)), units,
                eligibility, Map.of("URBAN_WORKER_MONTHLY_INCOME:2025", 1L), name -> List.of());
    }

    private static Map<String, Quote> evidence() {
        Map<String, Quote> quotes = new LinkedHashMap<>();
        for (String path : List.of(COMMON + ".requirements[0]", COMMON + ".requirements[1]", YOUTH + ".requirements[0]",
                COUPLE + ".requirements[0]", COUPLE + ".requirements[1]", COUPLE + ".requirements[2]",
                WELFARE + ".qualifications[0]", WELFARE + ".qualifications[1]", YOUTH + ".requirements[1]")) {
            quotes.put(path, new Quote(3, "원문 " + path));
        }
        return quotes;
    }

    private static Requirement requirement(String kind, String scope, String path, String asWritten) {
        return new Requirement(kind, scope, null, null, null, null, null, null, Map.of(), List.of(), null, List.of(),
                List.of(), List.of(), asWritten, path);
    }

    private static Requirement age(String path) {
        return new Requirement("AGE", "APPLICANT", 19, 39, null, null, null, null, Map.of(), List.of(), null, List.of(),
                List.of(), List.of(), "만 19세 이상 39세 이하", path);
    }

    private static Requirement family(String path) {
        return new Requirement("FAMILY_TYPE", null, null, null, null, null, null, null, Map.of(), List.of(), null,
                List.of(), List.of("COUPLE_OR_ENGAGED"), List.of(), "혼인 중인 자", path);
    }

    private static List<Requirement> dualIncome() {
        return List.of(new Requirement("INCOME", "HOUSEHOLD", null, null, null, "URBAN_WORKER_MONTHLY_INCOME", 2025, 100,
                Map.of(), List.of("DUAL_INCOME"), null, List.of(), List.of(), List.of(), "월평균소득 100% 이하(맞벌이 120%)",
                YOUTH + ".requirements[1]"));
    }

    private static List<Requirement> applicantIncome() {
        return List.of(new Requirement("INCOME", "APPLICANT", null, null, null, "URBAN_WORKER_MONTHLY_INCOME", 2025, 100,
                Map.of(), List.of(), null, List.of(), List.of(), List.of(), "본인 월평균소득 100% 이하", YOUTH + ".requirements[1]"));
    }

    private static Requirement income(String path) {
        return new Requirement("INCOME", "HOUSEHOLD", null, null, null, "URBAN_WORKER_MONTHLY_INCOME", 2025, 100,
                Map.of(1L, 20, 2L, 10), List.of("CHILDBIRTH"), null, List.of(), List.of(), List.of(),
                "전년도 도시근로자 가구원수별 월평균소득 100% 이하", path);
    }
}

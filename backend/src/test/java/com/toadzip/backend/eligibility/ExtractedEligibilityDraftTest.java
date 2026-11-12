package com.toadzip.backend.eligibility;

import static com.toadzip.backend.eligibility.BasicComparisonFixtures.STANDARDS;
import static com.toadzip.backend.eligibility.BasicComparisonFixtures.b;
import static com.toadzip.backend.eligibility.BasicComparisonFixtures.with;
import static com.toadzip.backend.eligibility.domain.ProfileField.APPLICANT_MONTHLY_INCOME;
import static com.toadzip.backend.eligibility.domain.ProfileField.BIRTH_DATE;
import static com.toadzip.backend.eligibility.domain.ProfileField.CHILDREN_COUNT_INCLUDING_FETUS;
import static com.toadzip.backend.eligibility.domain.ProfileField.FAMILY_CAR_VALUE;
import static com.toadzip.backend.eligibility.domain.ProfileField.FAMILY_OWNS_CAR;
import static com.toadzip.backend.eligibility.domain.ProfileField.YOUNGEST_CHILD;
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
import com.toadzip.backend.eligibility.domain.DateEvent;
import com.toadzip.backend.eligibility.domain.EligibilityDecision;
import com.toadzip.backend.eligibility.domain.EligibilityDecision.Status;
import com.toadzip.backend.eligibility.domain.EligibilityEvaluator;
import com.toadzip.backend.eligibility.domain.EligibilityProfile;
import com.toadzip.backend.eligibility.domain.ExtractedEligibility;
import com.toadzip.backend.eligibility.domain.ExtractedEligibility.Adjustment;
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
import com.toadzip.backend.eligibility.domain.InputValue;
import com.toadzip.backend.eligibility.domain.ProfileField;
import com.toadzip.backend.eligibility.domain.RegionMembership;
import com.toadzip.backend.eligibility.exception.EligibilityDraftUnavailableException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
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
    void 출산자녀_가산을_받을_수_있는_사람은_기본_소득_상한을_넘어도_지원_가능으로_본다() {
        EligibilityContent content = draft(evidence());
        EligibilityProfile overLimit = with(b(), HOUSEHOLD_MONTHLY_INCOME, 6_452_898L);

        // 가산 기준 금액이 추출 결과에 없어, 가산을 받을 수 있는 사람은 지원해 볼 만한 사람으로 본다.
        assertThat(youth(content, with(overLimit, HAS_CHILDREN_OR_PREGNANCY, true))).isEqualTo(Status.ELIGIBLE);
        assertThat(youth(content, with(overLimit, HAS_CHILDREN_OR_PREGNANCY, false))).isEqualTo(Status.INELIGIBLE);
        assertThat(youth(content, with(with(overLimit, FAMILY_TYPE, "SINGLE_PARENT"), HAS_CHILDREN_OR_PREGNANCY, true)))
                .isEqualTo(Status.ELIGIBLE);
        // 미혼은 출산자녀 가산 대상이 아니라 기본 상한으로 판정한다.
        assertThat(youth(content, with(overLimit, FAMILY_TYPE, "UNMARRIED"))).isEqualTo(Status.INELIGIBLE);
        // 가산 대상인지 알 입력이 없으면 미충족으로 돌려보내지 않고 그 입력을 요청한다.
        EligibilityDecision.OptionDecision noFamilyType = option(content, without(overLimit, FAMILY_TYPE), "청년");
        assertThat(noFamilyType.status()).isEqualTo(Status.UNDETERMINED);
        assertThat(noFamilyType.missingInputs()).contains(FAMILY_TYPE);
        EligibilityDecision.OptionDecision noChildren = option(content, without(overLimit, HAS_CHILDREN_OR_PREGNANCY), "청년");
        assertThat(noChildren.status()).isEqualTo(Status.UNDETERMINED);
        assertThat(noChildren.missingInputs()).contains(HAS_CHILDREN_OR_PREGNANCY);
        // 가산 기준이 다르다는 것은 판정을 막지 않는 목록으로 알린다.
        assertThat(guidance(content, "list-common").items()).contains(
                "출산자녀 가구는 소득 기준이 더 높아요. 얼마나 높은지는 공고문에서 확인해 주세요.");
    }

    @Test
    void 워커가_판정에_쓸_요건을_잃었다고_표시한_요건은_지원_가능으로_확정하지_않는다() {
        // 경로를 나누지 못한 공고의 경로별 요건(19~39세, 65세 이상)이나 모델이 빠뜨린 요건 자리다. 목록으로 돌리면
        // 남은 공통 조건만으로 50세도 지원 가능이 된다.
        EligibilityContent content = draft(evidence(), List.of(requirement("UNRESOLVED", null, null,
                "신청 대상마다 다른 요건이 있습니다. 공고문에서 신청자격을 확인해 주세요.")));

        assertThat(youth(content, b())).isEqualTo(Status.UNDETERMINED);
        Guidance direct = guidance(content, "direct-path-1");
        assertThat(direct.kind()).isEqualTo(GuidanceKind.REQUIRED_QUALIFICATION);
        assertThat(direct.items()).containsExactly("신청 대상마다 다른 요건이 있습니다. 공고문에서 신청자격을 확인해 주세요.");
    }

    @Test
    void 이전_워커가_빠진_요건_자리에_둔_OTHER_문구도_지원_가능으로_확정하지_않는다() {
        // UNRESOLVED 전의 워커는 같은 자리에 이 문구를 OTHER로 두었다. 다시 추출하지 않은 결과로 초안을 만들어도 목록으로 새면 안 된다.
        String earlier = "모델 응답에서 빠진 요건이 있습니다. 공고문에서 신청자격을 확인해 주세요.";
        EligibilityContent content = draft(evidence(), List.of(requirement("OTHER", null, null, earlier)));

        assertThat(youth(content, b())).isEqualTo(Status.UNDETERMINED);
        assertThat(guidance(content, "direct-path-1").items()).containsExactly(earlier);
    }

    @Test
    void 조건으로_바꾸지_못한_요건은_판정을_막지_않고_목록에_둔다() {
        EligibilityContent content = draft(evidence());

        // 워커가 비운 자산 상한도, 입력으로 판정할 수 없는 청약통장(기타)도 판정을 막지 않는 목록으로만 둔다.
        assertThat(status(content, b(), "신혼부부")).isEqualTo(Status.ELIGIBLE);
        assertThat(content.guidances()).extracting(Guidance::id).doesNotContain("direct-path-2");
        Guidance listed = guidance(content, "list-path-2");
        assertThat(listed.kind()).isEqualTo(GuidanceKind.REQUIRED_CHECK);
        assertThat(listed.items()).containsExactlyInAnyOrder("자산: 총자산 34,500만원 이하", "입주 전까지 주택청약종합저축 가입");
    }

    @Test
    void 입력으로_판정할_수_없는_세세한_요건은_목록으로만_보이고_판정을_막지_않는다() {
        EligibilityContent content = draft(evidence(), List.of(requirement("OTHER", null, YOUTH + ".requirements[1]",
                "같은 세대별 주민등록표상에 세대원으로 등재되어 있을 것")));

        EligibilityDecision.OptionDecision youth = option(content, b(), "청년");
        assertThat(youth.status()).isEqualTo(Status.ELIGIBLE);
        assertThat(youth.reminderGuidanceIds()).contains("list-path-1");
        assertThat(youth.directCheckGuidanceIds()).isEmpty();
        assertThat(guidance(content, "list-path-1").evidenceIds()).containsExactly(
                content.evidences().stream().filter(item -> item.location().equals(YOUTH + ".requirements[1]"))
                        .findFirst().orElseThrow().id());
    }

    @Test
    void 원문에서_찾지_못한_요건은_입력으로_판정할_조건이어도_목록으로만_둔다() {
        // 근거 쪽에서 문구를 찾지 못해 근거가 없는 자산 요건이다(evidence()에 requirements[2] 인용이 없다).
        Requirement unquoted = new Requirement("ASSETS", "HOUSEHOLD", null, null, null, null, null, null, Map.of(),
                List.of(), 345_000_000L, List.of(), List.of(), List.of(), "총자산 34,500만원 이하", YOUTH + ".requirements[2]");
        EligibilityContent content = draft(evidence(), List.of(unquoted));

        assertThat(youth(content, b())).isEqualTo(Status.ELIGIBLE);
        // 같은 문구를 쓰는 다른 종류(자동차가액)의 경고가 붙지 않게 종류를 앞에 붙인다.
        assertThat(guidance(content, "list-path-1").items()).containsExactly("자산: 총자산 34,500만원 이하");
        assertThat(content.paths().getFirst().checks()).extracting(EligibilityContent.ProfileCheck::label)
                .containsExactly("만 19세 이상 39세 이하");
    }

    @Test
    void 알아볼_입력이_없는_가산은_기본_기준으로_판정하고_가산을_목록에_알린다() {
        EligibilityContent content = draft(evidence(), List.of(new Requirement("INCOME", "HOUSEHOLD", null, null, null,
                "URBAN_WORKER_MONTHLY_INCOME", 2025, 100, Map.of(), List.of(new Adjustment("DISABILITY", null)), null, List.of(), List.of(),
                List.of(), "월평균소득 100% 이하(장애인 120%)", YOUTH + ".requirements[1]")));

        // 2인 기본 상한 5,866,270원 기준으로 판정한다. 장애인 가산 대상은 목록의 안내로 확인한다.
        assertThat(youth(content, with(b(), HOUSEHOLD_MONTHLY_INCOME, 5_800_000L))).isEqualTo(Status.ELIGIBLE);
        assertThat(youth(content, with(b(), HOUSEHOLD_MONTHLY_INCOME, 5_900_000L))).isEqualTo(Status.INELIGIBLE);
        assertThat(guidance(content, "list-path-1").items())
                .containsExactly("장애인 등 가구는 소득 기준이 더 높아요. 얼마나 높은지는 공고문에서 확인해 주세요.");
    }

    @Test
    void 출산자녀_가산_비율을_읽어_출산자녀_수별로_높아진_자동차가액_기준으로_판정한다() {
        // 인천논현2단지 국민임대 공고문: 자동차가액 4,542만원 이하, 자산기준 출산자녀 2인 이상 20%·1인 10% 가산.
        EligibilityContent content = draft(evidence(), List.of(carValue("출산자녀 2인 이상 20% 가산, 출산자녀 1인 10% 가산")));
        EligibilityProfile owner = with(b(), FAMILY_OWNS_CAR, true);
        EligibilityProfile oneBirth = withChildren(owner, 1L, LocalDate.of(2024, 5, 1));
        EligibilityProfile twoBirths = withChildren(owner, 2L, LocalDate.of(2024, 5, 1));

        assertThat(youth(content, with(owner, FAMILY_CAR_VALUE, 45_420_000L))).isEqualTo(Status.ELIGIBLE);
        assertThat(youth(content, with(owner, FAMILY_CAR_VALUE, 45_430_000L))).isEqualTo(Status.INELIGIBLE);
        // 1명: 4,542만원 × 110% = 4,996.2만원 → 만원 단위 올림 4,997만원.
        assertThat(youth(content, with(oneBirth, FAMILY_CAR_VALUE, 49_970_000L))).isEqualTo(Status.ELIGIBLE);
        assertThat(youth(content, with(oneBirth, FAMILY_CAR_VALUE, 49_980_000L))).isEqualTo(Status.INELIGIBLE);
        // 2명 이상: 4,542만원 × 120% = 5,450.4만원 → 5,451만원.
        assertThat(youth(content, with(twoBirths, FAMILY_CAR_VALUE, 54_510_000L))).isEqualTo(Status.ELIGIBLE);
        assertThat(youth(content, with(twoBirths, FAMILY_CAR_VALUE, 54_520_000L))).isEqualTo(Status.INELIGIBLE);
        // 막내가 출산자녀 기준일(2023.3.28.)보다 한참 전에 태어났으면 출산자녀 가산을 받지 않는다.
        EligibilityProfile older = withChildren(owner, 2L, LocalDate.of(2019, 1, 1));
        assertThat(youth(content, with(older, FAMILY_CAR_VALUE, 49_000_000L))).isEqualTo(Status.INELIGIBLE);
        // 계산한 가산은 '공고문에서 확인'으로 따로 알리지 않는다.
        assertThat(content.guidances()).noneMatch(guidance -> guidance.id().equals("list-path-1"));
    }

    @Test
    void 출산자녀_가산_비율은_소득_기준에_퍼센트포인트로_더한다() {
        EligibilityContent content = draft(evidence(), List.of(new Requirement("INCOME", "HOUSEHOLD", null, null, null,
                "URBAN_WORKER_MONTHLY_INCOME", 2025, 100, Map.of(2L, 10), List.of(new Adjustment("CHILDBIRTH",
                        "출산자녀 수 가산항목 및 비율 : 출산자녀 2인 이상 20% 가산, 출산자녀 1인 10% 가산")), null, List.of(),
                List.of(), List.of(), "월평균소득 100% 이하", YOUTH + ".requirements[1]")));
        EligibilityProfile oneBirth = withChildren(b(), 1L, LocalDate.of(2025, 1, 1));

        // 공통 소득 조건(가산 원문 없음)은 자녀가 있는 부부를 넓게 충족으로 보므로, 청년 경로의 소득 조건이 판정을 가른다.
        // 2인 5,866,270원 × (100% + 2인 10%p + 출산자녀 1명 10%p) = 7,039,524원.
        assertThat(youth(content, with(oneBirth, HOUSEHOLD_MONTHLY_INCOME, 7_039_524L))).isEqualTo(Status.ELIGIBLE);
        assertThat(youth(content, with(oneBirth, HOUSEHOLD_MONTHLY_INCOME, 7_039_525L))).isEqualTo(Status.INELIGIBLE);
    }

    @Test
    void 출산자녀_가산_비율은_출산자녀_문구에서만_읽는다() {
        assertThat(ExtractedEligibilityDraft.childbirthPercents("출산자녀 2인 이상 20% 가산, 출산자녀 1인 10% 가산"))
                .containsExactly(Map.entry(1, 10), Map.entry(2, 20));
        // 한 명당 비율이면 최대 2자녀까지 센다.
        assertThat(ExtractedEligibilityDraft.childbirthPercents("출산자녀 1명당 10%p 가산"))
                .containsExactly(Map.entry(1, 10), Map.entry(2, 20));
        // 쉼표 뒤 칸에 '자녀'가 다시 적히지 않아도 출산자녀 비율이면 읽는다(2자녀 가구가 1명 비율만 받지 않게).
        assertThat(ExtractedEligibilityDraft.childbirthPercents("출산자녀 수 가산항목 및 비율 : 1인 10%, 2인 이상 20%"))
                .containsExactly(Map.entry(1, 10), Map.entry(2, 20));
        assertThat(ExtractedEligibilityDraft.childbirthPercents("1인 가구 20% 가산, 출산자녀 1인 10% 가산"))
                .containsExactly(Map.entry(1, 10));
        assertThat(ExtractedEligibilityDraft.childbirthPercents("1인 가구 20% 가산, 2인 가구 10% 가산")).isEmpty();
        assertThat(ExtractedEligibilityDraft.childbirthPercents("월평균 소득('23년 3월 28일 이후 출생한 자녀가 있는 가구)"))
                .isEmpty();
        assertThat(ExtractedEligibilityDraft.childbirthPercents(null)).isEmpty();
    }

    @Test
    void 출산자녀_가산_비율을_읽지_못하면_자녀가_있는_가구를_넓게_보고_목록에_알린다() {
        EligibilityContent content = draft(evidence(), List.of(carValue("출산가구 자산 기준표 참조")));
        EligibilityProfile parent = with(withChildren(b(), 1L, LocalDate.of(2024, 5, 1)), FAMILY_OWNS_CAR, true);

        assertThat(youth(content, with(parent, FAMILY_CAR_VALUE, 60_000_000L))).isEqualTo(Status.ELIGIBLE);
        assertThat(guidance(content, "list-path-1").items())
                .containsExactly("출산자녀 가구는 자동차가액 기준이 더 높아요. 얼마나 높은지는 공고문에서 확인해 주세요.");
    }

    @Test
    void 법정_자격_목록은_자격마다_경로를_나누고_공급대상과_이은_경로만_만든다() {
        EligibilityContent content = draft(evidence());

        assertThat(content.paths()).extracting(EligibilityContent.EligibilityPath::label).containsExactly(
                "청년", "신혼부부", "주거급여 수급자", "생계급여 수급자");
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
        assertThat(status(content, b(), "주거급여 수급자")).isEqualTo(Status.UNDETERMINED);
    }

    @Test
    void 탭_이름은_공고문_목차_기호와_겹친_경로_이름을_떼고_자격_이름만_쓴다() {
        Route defector = new Route("마. 북한이탈주민", List.of("북한이탈주민"), new Group(WELFARE, List.of(), List.of(
                new Qualification("북한이탈주민", null, "북한이탈주민", WELFARE + ".qualifications[0]"))));
        Route youth = new Route("가) 청년", List.of("청년"), new Group(YOUTH, List.of(age(YOUTH + ".requirements[0]")), List.of()));

        EligibilityContent content = draft(evidence(), List.of(defector, youth),
                List.of(new Target("북한이탈주민", 2), new Target("청년", 3)));

        assertThat(content.paths()).extracting(EligibilityContent.EligibilityPath::label)
                .containsExactly("북한이탈주민", "청년");
    }

    @Test
    void 같은_자격이_여러_경로에_있으면_그_경로들만_경로_이름을_앞에_붙여_구분한다() {
        Function<String, Route> route = name -> new Route(name, List.of(name), new Group(WELFARE, List.of(), List.of(
                new Qualification("주거급여 수급자", null, "수급자", WELFARE + ".qualifications[0]"),
                new Qualification(name.equals("1. 우선공급") ? "생계급여 수급자" : "의료급여 수급자", null, "수급자",
                        WELFARE + ".qualifications[1]"))));

        EligibilityContent content = draft(evidence(), List.of(route.apply("1. 우선공급"), route.apply("2. 일반공급")),
                List.of(new Target("1. 우선공급", 2), new Target("2. 일반공급", 3)));

        assertThat(content.paths()).extracting(EligibilityContent.EligibilityPath::label).containsExactly(
                "우선공급 · 주거급여 수급자", "생계급여 수급자", "일반공급 · 주거급여 수급자", "의료급여 수급자");
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
    void 맞벌이_가산은_맞벌이_부부만_기본_상한을_넘어도_지원_가능으로_보고_한부모와_맞벌이_아닌_부부는_기본_상한으로_판정한다() {
        EligibilityContent content = draft(evidence(), dualIncome());
        EligibilityProfile couple = with(b(), HOUSEHOLD_MONTHLY_INCOME, 6_000_000L);

        // 기본 상한(2인 5,866,270원)을 넘는 600만원: 맞벌이 아닌 부부는 미충족, 맞벌이 부부는 가산을 받을 수 있어 지원 가능.
        assertThat(youth(content, couple)).isEqualTo(Status.INELIGIBLE);
        assertThat(youth(content, with(couple, IS_DUAL_INCOME, true))).isEqualTo(Status.ELIGIBLE);
        // 한부모는 맞벌이 입력을 받지 않으므로 맞벌이 가산 대상이 아니라 기본 상한으로 판정한다.
        EligibilityProfile singleParent = with(b(), FAMILY_TYPE, "SINGLE_PARENT");
        assertThat(youth(content, with(singleParent, HOUSEHOLD_MONTHLY_INCOME, 5_000_000L))).isEqualTo(Status.ELIGIBLE);
        assertThat(youth(content, with(singleParent, HOUSEHOLD_MONTHLY_INCOME, 6_000_000L))).isEqualTo(Status.INELIGIBLE);
    }

    @Test
    void 본인_소득_기준은_가구원_수를_알_수_없어_판정하지_않고_목록에_둔다() {
        EligibilityContent content = draft(evidence(), applicantIncome());

        // 몇 인 가구 금액인지 몰라 판정에 넣지 않는다. 판정을 막지 않고 목록으로 알린다.
        assertThat(youth(content, with(b(), APPLICANT_MONTHLY_INCOME, 3_000_000L))).isEqualTo(Status.ELIGIBLE);
        assertThat(guidance(content, "list-path-1").kind()).isEqualTo(GuidanceKind.REQUIRED_CHECK);
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
    void 공급대상_구분이_없는_공급행에는_모든_신청자격_경로를_잇고_검토_안내로_알린다() {
        // 통합공공임대처럼 공급표가 대상을 나누지 않으면 대상이 없거나 "전체" 하나뿐이고, 워커는 경로에 맞춘 대상을 주지 못한다.
        var units = List.of(
                new Unit(1, "남양주왕숙", "26A", new BigDecimal("26.00"), 10L, 20L, "통합공공임대", List.of("28237"),
                        List.of(new Target("전체", 10))),
                new Unit(2, "남양주왕숙", "37A", new BigDecimal("37.00"), 10L, 21L, "통합공공임대", List.of("28237"), List.of()));

        var built = ExtractedEligibilityDraft.build(source(evidence(), units, List.of()));
        validate(built.content());

        assertThat(built.content().paths()).extracting(EligibilityContent.EligibilityPath::label).containsExactly(
                "청년", "신혼부부", "주거급여 수급자", "생계급여 수급자", "사라진 대상");
        assertThat(built.content().options()).filteredOn(option -> option.pathId().equals("path-1"))
                .extracting(EligibilityContent.ApplicationOption::supplyUnitId,
                        EligibilityContent.ApplicationOption::recruitmentStatus)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("unit-1", RecruitmentStatus.RECRUITING),
                        org.assertj.core.groups.Tuple.tuple("unit-2", RecruitmentStatus.UNKNOWN));
        assertThat(built.notices()).containsExactly(
                "공급대상 구분이 없는 공급행 2개에는 모든 신청자격 경로를 이었습니다. 경로마다 신청할 수 있는 공급행이 맞는지 확인해 주세요.");
        assertThat(youth(built.content(), b())).isEqualTo(Status.ELIGIBLE);
    }

    @Test
    void 대상을_나눈_공급행에는_그_대상의_경로만_잇는다() {
        var units = List.of(
                new Unit(1, "인천석남", "21A", new BigDecimal("21.00"), 10L, 20L, "행복주택", List.of("28237"),
                        List.of(new Target("청년", 10))),
                new Unit(2, "인천석남", "36A", new BigDecimal("36.00"), 10L, 21L, "행복주택", List.of("28237"),
                        List.of(new Target("전체", 0))));

        var built = ExtractedEligibilityDraft.build(source(evidence(), units, List.of()));

        // 대상을 나눈 1번 공급행에는 청년만 잇고, 모집수 0인 2번 공급행에는 아무 경로도 잇지 않는다.
        assertThat(built.content().options()).extracting(EligibilityContent.ApplicationOption::id)
                .containsExactly("path-1-unit-1");
    }

    @Test
    void 대상_구분이_없는_행과_나눈_행이_섞이면_맞춘_대상이_없는_경로는_구분_없는_행에만_잇고_알린다() {
        var units = List.of(
                new Unit(1, "인천석남", "21A", new BigDecimal("21.00"), 10L, 20L, "행복주택", List.of("28237"),
                        List.of(new Target("전체", 10))),
                new Unit(2, "인천석남", "36A", new BigDecimal("36.00"), 10L, 21L, "행복주택", List.of("28237"),
                        List.of(new Target("청년", 10))));

        var built = ExtractedEligibilityDraft.build(source(evidence(), units, List.of()));
        validate(built.content());

        var paths = built.content().paths();
        String youth = paths.stream().filter(path -> path.label().equals("청년")).findFirst().orElseThrow().id();
        String missing = paths.stream().filter(path -> path.label().equals("사라진 대상")).findFirst().orElseThrow().id();
        assertThat(built.content().options()).filteredOn(option -> option.pathId().equals(youth))
                .extracting(EligibilityContent.ApplicationOption::supplyUnitId).containsExactly("unit-1", "unit-2");
        assertThat(built.content().options()).filteredOn(option -> option.pathId().equals(missing))
                .extracting(EligibilityContent.ApplicationOption::supplyUnitId).containsExactly("unit-1");
        assertThat(built.notices()).containsExactly(
                "공급대상 구분이 없는 공급행 1개에는 모든 신청자격 경로를 이었습니다. 경로마다 신청할 수 있는 공급행이 맞는지 확인해 주세요.",
                "공급대상을 나눈 공급행에는 잇지 못한 신청자격 경로: 사라진 대상");
    }

    @Test
    void 자기_조건이_없는_경로는_지원_가능으로_확정하지_않는다() {
        var units = List.of(new Unit(1, "공주옥룡", "26", new BigDecimal("26.00"), 10L, 20L, "영구임대", List.of("28237"),
                List.of(new Target("전체", 10))));

        EligibilityContent content = ExtractedEligibilityDraft.build(source(evidence(), units, List.of())).content();
        validate(content);

        // 경로 이름이 곧 자격(65세 이상 직계존속 부양자 등)인데 요건·자격을 하나도 못 뽑은 경로다. 공통 조건만으로 충족이 되면 안 된다.
        assertThat(status(content, b(), "사라진 대상")).isEqualTo(Status.UNDETERMINED);
        Guidance direct = guidance(content, "direct-path-5");
        assertThat(direct.kind()).isEqualTo(GuidanceKind.REQUIRED_QUALIFICATION);
        assertThat(direct.items()).containsExactly("신청 대상 '사라진 대상'에 해당하는지");
    }

    @Test
    void 경로의_마지막_조건까지_직접_확인으로_돌려도_저장되고_지원_가능으로_확정하지_않는다() {
        EligibilityContent content = draft(evidence());
        // 관리자 화면의 '직접 확인으로 바꾸기'는 조건을 빼고 공고 전체 대상의 직접 확인 안내에 옮긴다. 청년 경로는 조건이 하나도 안 남는다.
        EligibilityContent noChecks = new EligibilityContent(content.metadata(), content.documents(), content.evidences(),
                content.supplyUnits(), content.paths().stream().map(path -> path.label().equals("청년")
                        ? new EligibilityContent.EligibilityPath(path.id(), path.label(), path.kind(), List.of(),
                                path.qualificationIds())
                        : path).toList(),
                content.options(), content.guidances(), content.quantities(), content.rentOffers(), content.schedules(),
                List.of(), content.qualifications());
        Function<EligibilityContent.GuidanceTarget, EligibilityContent> withDirect = target -> new EligibilityContent(
                noChecks.metadata(), noChecks.documents(), noChecks.evidences(), noChecks.supplyUnits(), noChecks.paths(),
                noChecks.options(),
                Stream.concat(noChecks.guidances().stream(), Stream.of(new Guidance("direct-common",
                        GuidanceKind.REQUIRED_QUALIFICATION, "공고문에서 직접 확인할 신청자격", "직접 확인",
                        List.of("무주택세대구성원", "전년도 도시근로자 가구원수별 월평균소득 100% 이하"), List.of(target),
                        content.commonChecks().getFirst().evidenceIds()))).toList(),
                noChecks.quantities(), noChecks.rentOffers(), noChecks.schedules(), noChecks.commonChecks(),
                noChecks.qualifications());
        EligibilityContent moved = withDirect.apply(
                new EligibilityContent.GuidanceTarget(EligibilityContent.TargetScope.ANNOUNCEMENT, null, null));
        String otherPath = content.paths().stream().filter(path -> !path.label().equals("청년")).findFirst().orElseThrow().id();
        String unit = content.supplyUnits().getFirst().id();

        assertThatThrownBy(() -> validate(noChecks)).hasMessageContaining("아무 조건도 구조화하지 않은 경로");
        // 그 경로의 모든 선택지에 걸리지 않는 직접 확인(다른 경로, 공급단위 하나)으로는 조건 없는 경로를 허용하지 않는다.
        assertThatThrownBy(() -> validate(withDirect.apply(
                new EligibilityContent.GuidanceTarget(EligibilityContent.TargetScope.PATH, otherPath, null))))
                .hasMessageContaining("아무 조건도 구조화하지 않은 경로");
        assertThatThrownBy(() -> validate(withDirect.apply(
                new EligibilityContent.GuidanceTarget(EligibilityContent.TargetScope.UNIT, null, unit))))
                .hasMessageContaining("아무 조건도 구조화하지 않은 경로");
        validate(moved);
        EligibilityDecision.OptionDecision youth = option(moved, b(), "청년");
        assertThat(youth.status()).isEqualTo(Status.UNDETERMINED);
        assertThat(youth.directCheckGuidanceIds()).contains("direct-common");
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
        return option(content, profile, pathLabel).status();
    }

    private EligibilityDecision.OptionDecision option(EligibilityContent content, EligibilityProfile profile,
            String pathLabel) {
        EligibilityDecision decision = new EligibilityEvaluator().evaluate(content, profile, STANDARDS, regions);
        return decision.options().stream().filter(option -> option.pathLabel().equals(pathLabel))
                .findFirst().orElseThrow();
    }

    private static Guidance guidance(EligibilityContent content, String id) {
        return content.guidances().stream().filter(guidance -> guidance.id().equals(id)).findFirst().orElseThrow();
    }

    private static EligibilityContent draft(Map<String, Quote> quotes) {
        return draft(quotes, List.of());
    }

    /** 변환한 초안은 관리자 저장과 같은 검증기를 통과해야 한다. */
    private static EligibilityContent draft(Map<String, Quote> quotes, List<Requirement> youthExtra) {
        EligibilityContent content = ExtractedEligibilityDraft.build(source(quotes, null, youthExtra)).content();
        validate(content);
        return content;
    }

    private static void validate(EligibilityContent content) {
        new EligibilityContentValidator(new RegionMembership() {
            public Match match(Set<String> members, String supplied) {
                return Match.NO_MATCH;
            }

            public boolean isKnown(String code) {
                return code != null;
            }
        }, Set.of(1L)).validate(content, false);
    }

    /** 주어진 경로와, 그 경로 대상을 모두 담은 공급행 하나로 만든 초안. 공통 요건은 기본 픽스처와 같다. */
    private static EligibilityContent draft(Map<String, Quote> quotes, List<Route> routes, List<Target> targets) {
        Source base = source(quotes, null, List.of());
        ExtractedEligibility eligibility = new ExtractedEligibility(base.eligibility().common(), routes, List.of(), quotes);
        List<Unit> units = List.of(new Unit(1, "인천석남", "21A", new BigDecimal("21.00"), 10L, 20L, "행복주택",
                List.of("28237"), targets));
        EligibilityContent content = ExtractedEligibilityDraft.build(new Source(base.referenceDate(), base.document(), units,
                eligibility, base.incomeTables(), base.regionCodes())).content();
        validate(content);
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

    private static EligibilityProfile without(EligibilityProfile profile, ProfileField field) {
        Map<ProfileField, InputValue> values = new EnumMap<>(profile.facts());
        values.remove(field);
        return new EligibilityProfile(profile.familyDetailsState(), values);
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

    private static Requirement carValue(String adjustment) {
        return new Requirement("CAR_VALUE", "HOUSEHOLD", null, null, null, null, null, null, Map.of(),
                List.of(new Adjustment("CHILDBIRTH", adjustment)), 45_420_000L, List.of(), List.of(), List.of(),
                "세대구성원 전원이 보유하고 있는 개별 자동차가액 ( 4,542 )만원 이하", YOUTH + ".requirements[1]");
    }

    private static EligibilityProfile withChildren(EligibilityProfile profile, long count, LocalDate youngest) {
        return with(with(with(profile, HAS_CHILDREN_OR_PREGNANCY, true), CHILDREN_COUNT_INCLUDING_FETUS, count),
                YOUNGEST_CHILD, new DateEvent("BORN", youngest));
    }

    private static List<Requirement> dualIncome() {
        return List.of(new Requirement("INCOME", "HOUSEHOLD", null, null, null, "URBAN_WORKER_MONTHLY_INCOME", 2025, 100,
                Map.of(), List.of(new Adjustment("DUAL_INCOME", null)), null, List.of(), List.of(), List.of(), "월평균소득 100% 이하(맞벌이 120%)",
                YOUTH + ".requirements[1]"));
    }

    private static List<Requirement> applicantIncome() {
        return List.of(new Requirement("INCOME", "APPLICANT", null, null, null, "URBAN_WORKER_MONTHLY_INCOME", 2025, 100,
                Map.of(), List.of(), null, List.of(), List.of(), List.of(), "본인 월평균소득 100% 이하", YOUTH + ".requirements[1]"));
    }

    private static Requirement income(String path) {
        return new Requirement("INCOME", "HOUSEHOLD", null, null, null, "URBAN_WORKER_MONTHLY_INCOME", 2025, 100,
                Map.of(1L, 20, 2L, 10), List.of(new Adjustment("CHILDBIRTH", null)), null, List.of(), List.of(), List.of(),
                "전년도 도시근로자 가구원수별 월평균소득 100% 이하", path);
    }
}

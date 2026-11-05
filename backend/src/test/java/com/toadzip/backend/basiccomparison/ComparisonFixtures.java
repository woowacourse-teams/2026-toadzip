package com.toadzip.backend.basiccomparison;

import static com.toadzip.backend.basiccomparison.domain.ComparisonContent.*;
import static com.toadzip.backend.basiccomparison.domain.ProfileField.*;

import com.toadzip.backend.basiccomparison.domain.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Partial source-backed acceptance examples, not production notice imports. URLs are test fixtures.
 */
public final class ComparisonFixtures {
    private ComparisonFixtures() {}

    public static final Map<Long, IncomeStandard> STANDARDS =
            Map.of(
                    1L,
                            new IncomeStandard(
                                    1,
                                    "URBAN_WORKER_MONTHLY_INCOME",
                                    2025,
                                    "D05 PDF p5",
                                    579278L,
                                    8,
                                    Map.of(
                                            1L, 3813363L, 2L, 5866270L, 3L, 8168429L, 4L, 8802202L,
                                            5L, 9326985L, 6L, 9906263L, 7L, 10485541L, 8L,
                                            11064819L)),
                    2L,
                            new IncomeStandard(
                                    2,
                                    "STANDARD_MEDIAN_INCOME",
                                    2026,
                                    "D14 PDF p8",
                                    959198L,
                                    8,
                                    Map.of(
                                            1L, 2564238L, 2L, 4199292L, 3L, 5359036L, 4L, 6494738L,
                                            5L, 7556719L, 6L, 8555952L, 7L, 9515150L, 8L,
                                            10474348L)));

    public static BasicRule equal(ProfileField field, String expected) {
        return new BasicRule(
                BasicRule.Operator.EQUALS, field, expected, null, null, List.of(), null, List.of());
    }

    public static BasicRule amount(ProfileField field, long maximum) {
        return new BasicRule(
                BasicRule.Operator.MONEY_CEILING,
                field,
                null,
                null,
                maximum,
                List.of(),
                null,
                List.of());
    }

    public static BasicRule age(ProfileField field, long minimum, long maximum) {
        return new BasicRule(
                BasicRule.Operator.AGE_RANGE,
                field,
                null,
                minimum,
                maximum,
                List.of(),
                null,
                List.of());
    }

    public static BasicRule region(String region) {
        return new BasicRule(
                BasicRule.Operator.REGION_IN,
                RESIDENCE_REGION,
                null,
                null,
                null,
                List.of(region),
                null,
                List.of());
    }

    public static BasicRule income(long table, int percentage) {
        return new BasicRule(
                BasicRule.Operator.INCOME_CEILING,
                HOUSEHOLD_MONTHLY_INCOME,
                null,
                null,
                null,
                List.of(),
                new BasicRule.IncomeLimit(
                        table, percentage, Map.of(1L, 20, 2L, 10), REGISTERED_FAMILY_COUNT, null),
                List.of());
    }

    public static BasicCheck check(String id, BasicRule rule) {
        return new BasicCheck(id, id, rule, null, "scope", List.of("source"));
    }

    public static Guidance guidance(String id, GuidanceKind kind, String title) {
        return new Guidance(
                id,
                kind,
                title,
                title + " 원문에 적힌 세부 요건을 직접 확인해 주세요.",
                List.of(),
                List.of(new GuidanceTarget(TargetScope.ANNOUNCEMENT, null, null)),
                List.of("source"));
    }

    public static ComparisonContent sacheon() {
        return ordinary("D05", "사천벌리1", "48240", LocalDate.of(2026, 10, 16), 1, 50, 245000000L);
    }

    /** D05 PDF pp1-2,10; only the 26 square metre application unit is represented. */
    public static ComparisonContent sacheonWithInformation() {
        ComparisonContent content = sacheon();
        return new ComparisonContent(
                content.metadata(),
                content.documents(),
                content.evidences(),
                List.of(
                        new SupplyUnit(
                                "unit",
                                "사천벌리1 26형",
                                List.of(
                                        new PhysicalHousing(
                                                "type26",
                                                "사천벌리1",
                                                "26",
                                                "영구임대",
                                                new java.math.BigDecimal("26.37"),
                                                null,
                                                null)))),
                content.paths(),
                content.options(),
                content.guidances(),
                List.of(
                        new Quantity(
                                "built",
                                "건설 세대수",
                                QuantityKind.CONSTRUCTED,
                                new java.math.BigDecimal("298"),
                                List.of("unit"),
                                List.of("source")),
                        new Quantity(
                                "vacant",
                                "현재 공가",
                                QuantityKind.VACANT,
                                new java.math.BigDecimal("77"),
                                List.of("unit"),
                                List.of("source")),
                        new Quantity(
                                "recruit",
                                "금회 모집 예비자",
                                QuantityKind.WAITLIST_RECRUITED,
                                new java.math.BigDecimal("110"),
                                List.of("unit"),
                                List.of("source"))),
                List.of(
                        new RentOffer(
                                "rent-a",
                                "26형 가군",
                                "생계·의료급여수급자 등: 원문 가군 해당 여부 확인",
                                2520000L,
                                2520000L,
                                50160L,
                                50160L,
                                true,
                                null,
                                List.of("unit"),
                                List.of("source")),
                        new RentOffer(
                                "rent-b",
                                "26형 나군",
                                "가군 해당자 이외의 영구임대 입주 가능한 자",
                                7648000L,
                                7648000L,
                                115940L,
                                115940L,
                                true,
                                null,
                                List.of("unit"),
                                List.of("source"))),
                List.of(
                        new Schedule(
                                "apply",
                                "현장 신청",
                                "신청 구분별 지정일 확인, 선순위 초과 시 후순위 미접수",
                                java.time.OffsetDateTime.parse("2026-10-28T09:00:00+09:00"),
                                java.time.OffsetDateTime.parse("2026-10-30T18:00:00+09:00"),
                                "거주지 관할 읍·면·동 주민센터; 점심 12~13시 제외",
                                null,
                                List.of("unit"),
                                List.of("source"))));
    }

    public static ComparisonContent asan() {
        return ordinary("D18", "아산읍내", "44200", LocalDate.of(2026, 10, 6), 1, 50, 245000000L);
    }

    public static ComparisonContent dasan() {
        ComparisonContent content =
                ordinary("D14", "다산지금A3", null, LocalDate.of(2026, 5, 28), 2, 150, 345000000L);
        List<Guidance> guides = new ArrayList<>(content.guidances());
        guides.add(
                guidance("priority", GuidanceKind.PRIORITY_OPPORTUNITY, "철거민·보훈·장애 등 우선공급 대상 확인"));
        return withGuidances(content, guides);
    }

    private static ComparisonContent ordinary(
            String document,
            String label,
            String region,
            LocalDate reference,
            long table,
            int percent,
            long assets) {
        List<BasicCheck> checks = new ArrayList<>();
        checks.add(check("adult", age(BIRTH_DATE, 19, 150)));
        if (region != null) {
            checks.add(check("residence", region(region)));
        }
        checks.add(check("home", equal(FAMILY_OWNS_HOUSE, "false")));
        BasicRule noChildren = equal(HAS_CHILDREN_OR_PREGNANCY, "false");
        checks.add(
                new BasicCheck(
                        "income",
                        "세대 합산 소득",
                        income(table, percent),
                        noChildren,
                        "scope",
                        List.of("source")));
        checks.add(
                new BasicCheck(
                        "assets",
                        "가족 총자산",
                        amount(FAMILY_TOTAL_ASSETS, assets),
                        noChildren,
                        "scope",
                        List.of("source")));
        checks.add(
                new BasicCheck(
                        "car",
                        "가족 자동차",
                        amount(FAMILY_CAR_VALUE, 45420000L),
                        noChildren,
                        "scope",
                        List.of("source")));
        List<Guidance> guides =
                List.of(
                        guidance(
                                "scope",
                                GuidanceKind.REQUIRED_CHECK,
                                "법정 세대 범위와 자녀 가산 등 실제 적용 기준 확인"),
                        guidance("history", GuidanceKind.REQUIRED_CHECK, "불법양도·전대에 따른 제한 확인"),
                        guidance("other", GuidanceKind.OTHER_ROUTE, "다른 신분별 신청 경로 확인"));
        return content(
                document,
                label,
                reference,
                AccessMode.GENERAL_ROUTE_AVAILABLE,
                List.of(new ComparisonPath("ordinary", "일반 경로 기본 조건", PathKind.GENERAL, checks)),
                guides);
    }

    public static ComparisonContent shPermanent() {
        return content(
                "D11",
                "SH 영구임대",
                LocalDate.of(2025, 11, 13),
                AccessMode.REQUIRED_QUALIFICATION,
                List.of(
                        new ComparisonPath(
                                "common",
                                "독립적으로 확인하는 공통 조건",
                                PathKind.COMMON_ONLY,
                                List.of(
                                        check("adult", age(BIRTH_DATE, 19, 150)),
                                        check("seoul", region("11")),
                                        check("home", equal(FAMILY_OWNS_HOUSE, "false"))))),
                List.of(
                        guidance("scope", GuidanceKind.REQUIRED_CHECK, "법정 세대 범위와 주택소유 기준 확인"),
                        new Guidance(
                                "qualification",
                                GuidanceKind.REQUIRED_QUALIFICATION,
                                "공고에 열거된 가~자목 중 하나가 필요해요.",
                                "소득·자산이 낮다는 사실만으로 신청하는 차목 일반 경로가 없습니다. 각 자격과 결합된 세부 요건을 확인해 주세요.",
                                List.of(
                                        "생계·의료급여",
                                        "법령별 보훈 대상",
                                        "위안부 피해자",
                                        "지원대상 한부모가족",
                                        "북한이탈주민",
                                        "등록 장애인",
                                        "65세 이상 직계존속 부양",
                                        "시설퇴소·시설장 추천",
                                        "65세 이상 수급권자·차상위"),
                                List.of(new GuidanceTarget(TargetScope.ANNOUNCEMENT, null, null)),
                                List.of("source"))));
    }

    public static ComparisonContent anyang() {
        return content(
                "D03",
                "안양 냉천",
                LocalDate.of(2026, 6, 26),
                AccessMode.REQUIRED_QUALIFICATION,
                List.of(
                        new ComparisonPath(
                                "common",
                                "공통 주택 보유 조건",
                                PathKind.COMMON_ONLY,
                                List.of(check("home", equal(FAMILY_OWNS_HOUSE, "false"))))),
                List.of(
                        guidance("scope", GuidanceKind.REQUIRED_CHECK, "법정 세대 범위 확인"),
                        guidance(
                                "preselected",
                                GuidanceKind.REQUIRED_QUALIFICATION,
                                "이미 선정된 안양 냉천지구 대상자만 신청할 수 있으며 일반인은 청약할 수 없습니다.")));
    }

    public static ComparisonContent company() {
        ComparisonContent base =
                content(
                        "D19",
                        "연천BIX 기업체 기숙사",
                        LocalDate.of(2026, 8, 24),
                        AccessMode.GENERAL_ROUTE_AVAILABLE,
                        List.of(),
                        List.of());
        return new ComparisonContent(
                new Metadata(
                        base.metadata().referenceDate(),
                        ApplicantKind.ORGANIZATION,
                        AccessMode.GENERAL_ROUTE_AVAILABLE,
                        ReviewStatus.REVIEWED,
                        "source",
                        "기업 신청·계약, 직원 입주 확인 사례"),
                base.documents(),
                base.evidences(),
                base.supplyUnits(),
                List.of(),
                List.of(),
                List.of(),
                base.quantities(),
                base.rentOffers(),
                base.schedules());
    }

    public static ComparisonContent classification(String document, String label) {
        return content(
                document,
                label,
                LocalDate.of(2026, 1, 1),
                AccessMode.GENERAL_ROUTE_AVAILABLE,
                List.of(
                        new ComparisonPath(
                                "general", "이번 모집의 일반 개인 경로", PathKind.GENERAL, List.of())),
                List.of(guidance("other", GuidanceKind.OTHER_ROUTE, "특수 경로의 요건은 해당 원문에서 확인")));
    }

    public static ComparisonContent content(
            String document,
            String label,
            LocalDate reference,
            AccessMode access,
            List<ComparisonPath> paths,
            List<Guidance> guidances) {
        List<ComparisonOption> options =
                paths.stream()
                        .map(
                                path ->
                                        new ComparisonOption(
                                                "unit-" + path.id(),
                                                "unit",
                                                path.id(),
                                                RecruitmentStatus.RECRUITING))
                        .toList();
        int first = 1;
        int last = 10;
        if (document.equals("D11")) {
            first = 7;
        }
        return new ComparisonContent(
                new Metadata(
                        reference,
                        ApplicantKind.INDIVIDUAL,
                        access,
                        ReviewStatus.REVIEWED,
                        "source",
                        "원문 일부를 사용한 테스트 fixture이며 전체 공고 적재 데이터가 아닙니다."),
                List.of(
                        new SourceDocument(
                                document,
                                label + ".pdf",
                                "https://example.test/" + document + ".pdf",
                                60,
                                null)),
                List.of(
                        new Evidence(
                                "source",
                                document,
                                first,
                                last,
                                "신청자격·소득표·공급표",
                                "원문을 기준으로 작성한 비교 및 안내의 테스트 근거")),
                List.of(new SupplyUnit("unit", label + " 신청 단위", List.of())),
                paths,
                options,
                guidances,
                List.of(),
                List.of(),
                List.of());
    }

    public static ComparisonContent withGuidances(
            ComparisonContent content, List<Guidance> guidances) {
        return new ComparisonContent(
                content.metadata(),
                content.documents(),
                content.evidences(),
                content.supplyUnits(),
                content.paths(),
                content.options(),
                guidances,
                content.quantities(),
                content.rentOffers(),
                content.schedules());
    }

    public static ComparisonContent withPaths(
            ComparisonContent content, List<ComparisonPath> paths) {
        return new ComparisonContent(
                content.metadata(),
                content.documents(),
                content.evidences(),
                content.supplyUnits(),
                paths,
                content.options(),
                content.guidances(),
                content.quantities(),
                content.rentOffers(),
                content.schedules());
    }

    public static ComparisonProfile b() {
        Map<ProfileField, InputValue> values = new EnumMap<>(ProfileField.class);
        Map<ProfileField, Object> supplied =
                Map.ofEntries(
                        Map.entry(BIRTH_DATE, "1989-01-10"),
                        Map.entry(RESIDENCE_REGION, "48240"),
                        Map.entry(IS_UNIVERSITY_STUDENT, false),
                        Map.entry(IS_JOB_SEEKER, false),
                        Map.entry(APPLICANT_OWNS_HOUSE, false),
                        Map.entry(APPLICANT_OWNS_CAR, false),
                        Map.entry(APPLICANT_ASSETS, 60000000L),
                        Map.entry(APPLICANT_MONTHLY_INCOME, 2000000L),
                        Map.entry(FAMILY_TYPE, "COUPLE_OR_ENGAGED"),
                        Map.entry(REGISTERED_FAMILY_COUNT, 2L),
                        Map.entry(HOUSEHOLD_MONTHLY_INCOME, 3000000L),
                        Map.entry(HAS_CHILDREN_OR_PREGNANCY, false),
                        Map.entry(CHILDREN_COUNT_INCLUDING_FETUS, 0L),
                        Map.entry(
                                MARRIAGE_EVENT,
                                new DateEvent("REGISTERED", LocalDate.of(2018, 4, 1))),
                        Map.entry(IS_DUAL_INCOME, false),
                        Map.entry(FAMILY_OWNS_HOUSE, false),
                        Map.entry(FAMILY_OWNS_CAR, false),
                        Map.entry(FAMILY_TOTAL_ASSETS, 100000000L));
        supplied.forEach((field, value) -> values.put(field, InputValue.provided(value)));
        values.put(APPLICANT_CAR_VALUE, InputValue.notApplicable());
        values.put(FAMILY_CAR_VALUE, InputValue.notApplicable());
        values.put(YOUNGEST_CHILD, InputValue.notApplicable());
        return new ComparisonProfile(FamilyDetailsState.PROVIDED, values);
    }

    public static ComparisonProfile with(
            ComparisonProfile profile, ProfileField field, Object value) {
        Map<ProfileField, InputValue> values = new EnumMap<>(profile.facts());
        values.put(field, InputValue.provided(value));
        return new ComparisonProfile(profile.familyDetailsState(), values);
    }

    public static ComparisonProfile singleParent() {
        Map<ProfileField, InputValue> values = new EnumMap<>(b().facts());
        values.put(FAMILY_TYPE, InputValue.provided("SINGLE_PARENT"));
        values.put(RESIDENCE_REGION, InputValue.provided("11110"));
        values.put(MARRIAGE_EVENT, InputValue.notApplicable());
        values.put(IS_DUAL_INCOME, InputValue.notApplicable());
        values.put(HAS_CHILDREN_OR_PREGNANCY, InputValue.provided(true));
        values.put(CHILDREN_COUNT_INCLUDING_FETUS, InputValue.provided(1L));
        values.put(
                YOUNGEST_CHILD,
                InputValue.provided(new DateEvent("BORN", LocalDate.of(2017, 3, 1))));
        return new ComparisonProfile(FamilyDetailsState.PROVIDED, values);
    }

    public static ComparisonProfile skipped() {
        Map<ProfileField, InputValue> values = new EnumMap<>(b().facts());
        for (ProfileField field : ProfileField.values()) {
            if (field.optional()) {
                values.put(field, InputValue.unknown());
            }
        }
        return new ComparisonProfile(FamilyDetailsState.SKIPPED, values);
    }
}

package com.toadzip.backend.eligibility.domain;

import com.toadzip.backend.eligibility.domain.EligibilityContent.AccessMode;
import com.toadzip.backend.eligibility.domain.EligibilityContent.ApplicantKind;
import com.toadzip.backend.eligibility.domain.EligibilityContent.ApplicationOption;
import com.toadzip.backend.eligibility.domain.EligibilityContent.EligibilityPath;
import com.toadzip.backend.eligibility.domain.EligibilityContent.Evidence;
import com.toadzip.backend.eligibility.domain.EligibilityContent.Guidance;
import com.toadzip.backend.eligibility.domain.EligibilityContent.GuidanceKind;
import com.toadzip.backend.eligibility.domain.EligibilityContent.GuidanceTarget;
import com.toadzip.backend.eligibility.domain.EligibilityContent.Metadata;
import com.toadzip.backend.eligibility.domain.EligibilityContent.PathKind;
import com.toadzip.backend.eligibility.domain.EligibilityContent.PhysicalHousing;
import com.toadzip.backend.eligibility.domain.EligibilityContent.ProfileCheck;
import com.toadzip.backend.eligibility.domain.EligibilityContent.RecruitmentStatus;
import com.toadzip.backend.eligibility.domain.EligibilityContent.ReviewStatus;
import com.toadzip.backend.eligibility.domain.EligibilityContent.SourceDocument;
import com.toadzip.backend.eligibility.domain.EligibilityContent.SupplyUnit;
import com.toadzip.backend.eligibility.domain.EligibilityContent.TargetScope;
import com.toadzip.backend.eligibility.domain.ExtractedEligibility.Group;
import com.toadzip.backend.eligibility.domain.ExtractedEligibility.Requirement;
import com.toadzip.backend.eligibility.domain.ExtractedEligibility.Route;
import com.toadzip.backend.eligibility.domain.ProfileRule.IncomeLimit;
import com.toadzip.backend.eligibility.domain.ProfileRule.Operator;
import com.toadzip.backend.eligibility.exception.EligibilityDraftUnavailableException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * AI 추출 신청자격과 PDF 보정을 적용한 공급행으로 검토 전(REVIEWING) 신청자격 초안을 만든다.
 * 자동 판정 조건으로 바꿀 수 없는 요건(기타, 비운 숫자, 없는 소득 기준표, 읽지 못한 지역, 본인·부모 합산 소득, 장애인 등 가산)은
 * 조건으로 만들지 않고 그 범위의 직접 확인 안내 항목으로 남긴다. 조건을 빼서 판정을 넓히지 않는다.
 * 출산자녀·맞벌이 가산이 있는 조건은 해당하지 않는 사람에게만 자동 판정하고, 해당하면 직접 확인으로 넘긴다.
 */
public final class ExtractedEligibilityDraft {
    private static final String DOCUMENT_ID = "pdf";
    private static final String SCOPE_NOTE = "공고문의 신청자격 장을 바탕으로 구조화했어요. 우선순위·배점·제출서류는 담지 않았어요.";
    private static final long MAXIMUM_AGE = 150;
    private static final long MAXIMUM_MONTHS = 1800;

    /** 추출 PDF와 그 공고의 현재 공급행. incomeTables는 "기준표 종류:연도" → 기준표 ID다. */
    public record Source(
            LocalDate referenceDate,
            Document document,
            List<Unit> units,
            ExtractedEligibility eligibility,
            Map<String, Long> incomeTables,
            Function<String, List<String>> regionCodes) {}

    public record Document(String fileName, String downloadUrl, int pageCount, String sha256) {}

    /** 관리자가 빼지 않은 공급행 하나. targets는 빼지 않은 공급대상이다. */
    public record Unit(
            long rowId,
            String complexLabel,
            String typeLabel,
            BigDecimal exclusiveArea,
            Long housingComplexId,
            Long housingTypeId,
            String rentalType,
            List<String> regionCodes,
            List<Target> targets) {}

    public record Target(String name, Integer supplyHouseholdCount) {}

    private final Source source;
    private final Map<String, String> evidenceIds = new LinkedHashMap<>();
    private final List<Evidence> evidences = new ArrayList<>();
    private final List<Guidance> guidances = new ArrayList<>();
    private final List<EligibilityContent.Qualification> qualifications = new ArrayList<>();

    private ExtractedEligibilityDraft(Source source) {
        this.source = source;
        source.eligibility().quotes().forEach((path, quote) -> {
            String id = "ev-" + (evidences.size() + 1);
            int page = Math.min(Math.max(quote.page(), 1), source.document().pageCount());
            evidences.add(new Evidence(id, DOCUMENT_ID, page, page, path, quote.quote()));
            evidenceIds.put(path, id);
        });
    }

    /** 초안 내용과, 관리자가 검토할 때 알아야 할 안내(잇지 못한 경로·경로가 없는 공급대상). 안내는 공개 내용에 넣지 않는다. */
    public record Draft(EligibilityContent content, List<String> notices) {}

    public static Draft build(Source source) {
        return new ExtractedEligibilityDraft(source).content();
    }

    private Draft content() {
        if (evidences.isEmpty()) {
            throw new EligibilityDraftUnavailableException("추출 결과에 신청자격 근거가 없어 초안을 만들 수 없습니다.");
        }
        String classification = evidences.getFirst().id();
        List<String> unmatched = new ArrayList<>();
        List<Route> routes = routes(unmatched);
        Scope common = new Scope("common", new GuidanceTarget(TargetScope.ANNOUNCEMENT, null, null),
                source.eligibility().common().evidencePath());
        List<ProfileCheck> commonChecks = checks(source.eligibility().common().requirements(), common);
        List<String> commonQualifications = names(source.eligibility().common().qualifications());
        if (!routes.isEmpty() && !commonQualifications.isEmpty()) {
            common.direct.add("다음 자격 중 하나: " + String.join(", ", commonQualifications));
        }
        List<SupplyUnit> units = new ArrayList<>();
        List<EligibilityPath> paths = new ArrayList<>();
        List<ApplicationOption> options = new ArrayList<>();
        List<Scope> scopes = new ArrayList<>();
        for (Route route : routes) {
            for (Alternative alternative : alternatives(route, routes.size() == 1 && route.name() == null)) {
                String pathId = "path-" + (paths.size() + 1);
                List<ApplicationOption> connected = options(pathId, route.supplyTargets());
                if (connected.isEmpty()) {
                    unmatched.add(alternative.label());
                    continue;
                }
                Scope scope = new Scope(pathId, new GuidanceTarget(TargetScope.PATH, pathId, null),
                        route.group().evidencePath());
                List<ProfileCheck> checks = checks(route.group().requirements(), scope);
                paths.add(new EligibilityPath(pathId, alternative.label(), PathKind.APPLICATION, checks,
                        alternative.qualificationIds()));
                options.addAll(connected);
                scopes.add(scope);
            }
        }
        if (paths.isEmpty()) {
            throw new EligibilityDraftUnavailableException("공급대상과 이어지는 신청자격 경로가 없어 초안을 만들 수 없습니다. 공급정보 보정을 먼저 적용해 공급대상 이름을 공고문과 맞춘 뒤 다시 만들어 주세요.");
        }
        Set<String> usedUnits = new LinkedHashSet<>(options.stream().map(ApplicationOption::supplyUnitId).toList());
        for (Unit unit : source.units()) {
            if (usedUnits.contains(unitId(unit))) {
                units.add(supplyUnit(unit));
            }
        }
        guidances.addAll(common.guidances());
        scopes.forEach(scope -> guidances.addAll(scope.guidances()));
        exclusions();
        List<String> notices = new ArrayList<>();
        if (!unmatched.isEmpty()) {
            notices.add("공급대상과 잇지 못한 신청자격 경로: " + String.join(", ", unmatched));
        }
        List<String> uncovered = uncoveredTargets(routes, paths);
        if (!uncovered.isEmpty()) {
            // 신청자격 경로가 없는 대상의 사용자는 다른 경로에서만 판정되어 공고 전체가 미충족으로 보일 수 있다.
            notices.add("신청자격 경로가 없는 공급대상: " + String.join(", ", uncovered) + ". 공고문을 확인해 주세요.");
        }
        // scopeNote는 사용자 공고 상세에 그대로 보이고 비울 수 없으므로, AI 초안 안내 대신 사용자에게도 맞는 범위 설명을 둔다.
        EligibilityContent content = new EligibilityContent(
                new Metadata(source.referenceDate(), ApplicantKind.INDIVIDUAL, AccessMode.APPLICATION_ROUTES,
                        ReviewStatus.REVIEWING, classification, SCOPE_NOTE),
                List.of(new SourceDocument(DOCUMENT_ID, source.document().fileName(), source.document().downloadUrl(),
                        source.document().pageCount(), source.document().sha256())),
                evidences, units, paths, options, guidances, List.of(), List.of(), List.of(), commonChecks,
                usedQualifications(paths));
        return new Draft(content, List.copyOf(notices));
    }

    /** 모집수가 있거나 모르는 공급대상 가운데 만든 경로 어디에도 이어지지 않은 대상. */
    private List<String> uncoveredTargets(List<Route> routes, List<EligibilityPath> paths) {
        Set<String> covered = new LinkedHashSet<>();
        if (!paths.isEmpty()) {
            routes.forEach(route -> route.supplyTargets().forEach(name -> covered.add(compact(name))));
        }
        return source.units().stream().flatMap(unit -> unit.targets().stream())
                .filter(target -> target.supplyHouseholdCount() == null || target.supplyHouseholdCount() > 0)
                .map(Target::name).filter(name -> !covered.contains(compact(name))).distinct().toList();
    }

    /** 경로가 없으면(공급대상 구분이 없는 공고) 공급표의 모든 대상을 잇는 경로 하나로 본다. */
    private List<Route> routes(List<String> unmatched) {
        List<Route> routes = new ArrayList<>();
        for (Route route : source.eligibility().routes()) {
            if (route.supplyTargets().isEmpty()) {
                unmatched.add(route.name());
            } else {
                routes.add(route);
            }
        }
        if (source.eligibility().routes().isEmpty()) {
            List<String> all = source.units().stream().flatMap(unit -> unit.targets().stream()).map(Target::name)
                    .distinct().toList();
            Group common = source.eligibility().common();
            routes.add(new Route(null, all, new Group(common.evidencePath(), List.of(), common.qualifications())));
        }
        return routes;
    }

    private record Alternative(String label, List<String> qualificationIds) {}

    /** 한 묶음의 직접 확인 자격은 그중 하나만 갖추면 되므로 자격마다 경로를 나눈다. */
    private List<Alternative> alternatives(Route route, boolean announcementWide) {
        String base = route.name() == null ? "신청자격" : route.name();
        if (route.group().qualifications().isEmpty()) {
            return List.of(new Alternative(limit(base, 300), List.of()));
        }
        List<Alternative> result = new ArrayList<>();
        for (ExtractedEligibility.Qualification qualification : route.group().qualifications()) {
            String id = "qual-" + (qualifications.size() + 1);
            List<String> evidence = evidence(qualification.evidencePath(), route.group().evidencePath());
            qualifications.add(new EligibilityContent.Qualification(id, limit(qualification.exactName(), 300),
                    blankToNull(qualification.legalBasis()),
                    limit(firstText(qualification.conditionsAsWritten(), qualification.exactName()), 10000),
                    List.of(), evidence));
            String label = announcementWide ? qualification.exactName() : base + " · " + qualification.exactName();
            result.add(new Alternative(limit(label, 300), List.of(id)));
        }
        return result;
    }

    private List<ApplicationOption> options(String pathId, List<String> supplyTargets) {
        List<ApplicationOption> options = new ArrayList<>();
        // 워커가 맞춘 이름과 보정으로 저장한 이름은 공백만 다를 수 있어(가군 (일반) ↔ 가군(일반)) 공백은 무시하고 비교한다.
        Set<String> wanted = new LinkedHashSet<>(supplyTargets.stream().map(ExtractedEligibilityDraft::compact).toList());
        for (Unit unit : source.units()) {
            List<Target> matched = unit.targets().stream().filter(target -> wanted.contains(compact(target.name()))).toList();
            if (matched.isEmpty()) {
                continue;
            }
            boolean recruiting = matched.stream().anyMatch(target -> target.supplyHouseholdCount() != null
                    && target.supplyHouseholdCount() > 0);
            boolean unknown = matched.stream().anyMatch(target -> target.supplyHouseholdCount() == null);
            if (!recruiting && !unknown) {
                continue;
            }
            options.add(new ApplicationOption(pathId + "-" + unitId(unit), unitId(unit), pathId,
                    recruiting ? RecruitmentStatus.RECRUITING : RecruitmentStatus.UNKNOWN));
        }
        return options;
    }

    private SupplyUnit supplyUnit(Unit unit) {
        String label = limit(String.join(" ", nonBlank(unit.complexLabel(), unit.typeLabel())), 300);
        // 전용면적이나 임대유형을 모르면 물리 주택형 연결은 두지 않는다(검증기가 두 값을 요구한다).
        List<PhysicalHousing> housing = unit.exclusiveArea() == null || unit.exclusiveArea().signum() <= 0
                || unit.rentalType() == null
                ? List.of()
                : List.of(new PhysicalHousing("housing-" + unit.rowId(), limit(firstText(unit.complexLabel(), label), 300),
                        limit(firstText(unit.typeLabel(), label), 100), unit.rentalType(), unit.exclusiveArea(),
                        unit.housingComplexId(), unit.housingTypeId()));
        return new SupplyUnit(unitId(unit), label, housing, unit.regionCodes().stream().distinct().toList());
    }

    private static String unitId(Unit unit) {
        return "unit-" + unit.rowId();
    }

    /** 범위(공고 전체 또는 경로)의 조건과, 조건으로 바꾸지 못한 요건을 모은 직접 확인 안내. */
    private final class Scope {
        private final String id;
        private final GuidanceTarget target;
        private final String evidencePath;
        private final List<String> direct = new ArrayList<>();
        private boolean referenced;

        private Scope(String id, GuidanceTarget target, String evidencePath) {
            this.id = id;
            this.target = target;
            this.evidencePath = evidencePath;
        }

        /** 가산·가족 정보 등으로 조건을 자동 판정하지 못할 때 넘기는 안내(검증기는 REQUIRED_CHECK를 요구한다). */
        private String fallbackId() {
            referenced = true;
            return "check-" + id;
        }

        /**
         * 조건으로 바꾸지 못한 요건은 필수 확인 자격(REQUIRED_QUALIFICATION)으로 둔다. 판정기는 이 안내가 걸린 선택지를
         * 지원 가능으로 확정하지 않는다. REQUIRED_CHECK 안내는 조건에 연결될 때만 결과에 반영되므로 여기에 쓰지 않는다.
         */
        private List<Guidance> guidances() {
            List<Guidance> result = new ArrayList<>();
            if (referenced) {
                result.add(new Guidance("check-" + id, GuidanceKind.REQUIRED_CHECK, "자동 판정 한계 확인",
                        "출산자녀·맞벌이 등 가산 대상이거나 가족 정보를 입력하지 않았으면 공고문 기준으로 확인해 주세요.",
                        List.of(), List.of(target),
                        evidence(null, evidencePath)));
            }
            if (!direct.isEmpty()) {
                List<String> items = direct.stream().map(item -> limit(item, 2000)).distinct().limit(100).toList();
                result.add(new Guidance("direct-" + id, GuidanceKind.REQUIRED_QUALIFICATION, "공고문에서 직접 확인할 신청자격",
                        "자동으로 판정하지 못한 요건이에요. 공고문 원문에서 해당하는지 확인해 주세요.", items, List.of(target),
                        evidence(null, evidencePath)));
            }
            return result;
        }
    }

    private List<ProfileCheck> checks(List<Requirement> requirements, Scope scope) {
        List<ProfileCheck> checks = new ArrayList<>();
        for (Requirement requirement : requirements) {
            Optional<ProfileRule> rule = rule(requirement);
            String evidence = evidenceIds.get(requirement.evidencePath());
            Optional<ProfileRule> appliesWhen = appliesWhen(requirement);
            if (rule.isEmpty() || evidence == null || appliesWhen == null) {
                scope.direct.add(firstText(requirement.asWritten(), requirement.kind()));
                continue;
            }
            boolean fallback = appliesWhen.isPresent() || EligibilityContentValidator.needsFallback(rule.get());
            checks.add(new ProfileCheck(scope.id + "-" + (checks.size() + 1), limit(firstText(requirement.asWritten(),
                    requirement.kind()), 300), rule.get(), appliesWhen.orElse(null), fallback ? scope.fallbackId() : null,
                    List.of(evidence)));
        }
        return checks;
    }

    /**
     * 가산이 있으면 해당하지 않는 사람에게만 자동 판정한다. 알아볼 입력이 없는 가산이면 null(직접 확인)이다.
     * 자녀·맞벌이 입력은 가족 형태에 따라 받지 않으므로(미혼은 자녀, 미혼·한부모는 맞벌이) 그 가족 형태도 해당하지 않는 사람으로 본다.
     */
    private static Optional<ProfileRule> appliesWhen(Requirement requirement) {
        List<ProfileRule> conditions = new ArrayList<>();
        for (String adjustment : requirement.adjustments()) {
            switch (adjustment) {
                case "CHILDBIRTH" -> conditions.add(group(Operator.ANY, List.of(
                        equal(ProfileField.FAMILY_TYPE, "UNMARRIED"), equal(ProfileField.HAS_CHILDREN_OR_PREGNANCY, "false"))));
                case "DUAL_INCOME" -> conditions.add(group(Operator.ANY, List.of(
                        equal(ProfileField.FAMILY_TYPE, "UNMARRIED"), equal(ProfileField.FAMILY_TYPE, "SINGLE_PARENT"),
                        equal(ProfileField.IS_DUAL_INCOME, "false"))));
                default -> {
                    return null;
                }
            }
        }
        if (conditions.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(conditions.size() == 1 ? conditions.getFirst() : group(Operator.ALL, conditions));
    }

    private Optional<ProfileRule> rule(Requirement requirement) {
        String scope = requirement.scope();
        boolean household = "HOUSEHOLD".equals(scope);
        boolean applicant = "APPLICANT".equals(scope);
        return switch (requirement.kind()) {
            case "AGE" -> age(requirement);
            case "NO_HOUSE" -> household ? Optional.of(equal(ProfileField.FAMILY_OWNS_HOUSE, "false"))
                    : applicant ? Optional.of(equal(ProfileField.APPLICANT_OWNS_HOUSE, "false")) : Optional.empty();
            case "NO_CAR" -> household ? Optional.of(equal(ProfileField.FAMILY_OWNS_CAR, "false"))
                    : applicant ? Optional.of(equal(ProfileField.APPLICANT_OWNS_CAR, "false")) : Optional.empty();
            case "ASSETS" -> money(requirement, household ? ProfileField.FAMILY_TOTAL_ASSETS
                    : applicant ? ProfileField.APPLICANT_ASSETS : null);
            case "CAR_VALUE" -> money(requirement, household ? ProfileField.FAMILY_CAR_VALUE
                    : applicant ? ProfileField.APPLICANT_CAR_VALUE : null);
            // 본인 소득 기준이 몇 인 가구 금액인지 추출 결과에 없어, 등본 가족 수로 넓게 판정하지 않고 직접 확인으로 둔다.
            case "INCOME" -> income(requirement, household ? ProfileField.HOUSEHOLD_MONTHLY_INCOME : null);
            case "RESIDENCE" -> residence(requirement);
            case "MARRIAGE_PERIOD" -> requirement.maximum() == null ? Optional.empty()
                    : Optional.of(range(Operator.MONTHS_SINCE, ProfileField.MARRIAGE_EVENT, 0L,
                            Math.min(requirement.maximum() * 12L, MAXIMUM_MONTHS)));
            case "FAMILY_TYPE" -> familyType(requirement.familyTypes());
            case "UNIVERSITY_STUDENT" -> Optional.of(equal(ProfileField.IS_UNIVERSITY_STUDENT, "true"));
            case "JOB_SEEKER" -> Optional.of(equal(ProfileField.IS_JOB_SEEKER, "true"));
            case "ANY_OF" -> anyOf(requirement.options());
            default -> Optional.empty();
        };
    }

    private static Optional<ProfileRule> age(Requirement requirement) {
        if (requirement.minimum() == null && requirement.maximum() == null) {
            return Optional.empty();
        }
        ProfileField field = "YOUNGEST_CHILD".equals(requirement.scope()) ? ProfileField.YOUNGEST_CHILD
                : "APPLICANT".equals(requirement.scope()) ? ProfileField.BIRTH_DATE : null;
        if (field == null) {
            return Optional.empty();
        }
        long minimum = requirement.minimum() == null ? 0 : requirement.minimum();
        long maximum = requirement.maximum() == null ? MAXIMUM_AGE : requirement.maximum();
        return minimum <= maximum ? Optional.of(range(Operator.AGE_RANGE, field, minimum, maximum)) : Optional.empty();
    }

    private static Optional<ProfileRule> money(Requirement requirement, ProfileField field) {
        if (field == null || requirement.maximumKrw() == null) {
            return Optional.empty();
        }
        return Optional.of(new ProfileRule(Operator.MONEY_CEILING, field, null, null, requirement.maximumKrw(),
                List.of(), null, List.of()));
    }

    private Optional<ProfileRule> income(Requirement requirement, ProfileField field) {
        Long table = source.incomeTables().get(requirement.incomeStandard() + ":" + requirement.incomeBaseYear());
        if (field == null || table == null || requirement.percent() == null || requirement.percent() <= 0) {
            return Optional.empty();
        }
        IncomeLimit limit = new IncomeLimit(table, requirement.percent(), requirement.householdSizeExtraPercent(),
                ProfileField.REGISTERED_FAMILY_COUNT, null);
        return Optional.of(new ProfileRule(Operator.INCOME_CEILING, field, null, null, null, List.of(), limit, List.of()));
    }

    private Optional<ProfileRule> residence(Requirement requirement) {
        Set<String> codes = new LinkedHashSet<>();
        for (String region : requirement.regions()) {
            List<String> resolved = source.regionCodes().apply(region);
            if (resolved.isEmpty()) {
                return Optional.empty();
            }
            codes.addAll(resolved);
        }
        if (codes.isEmpty()) {
            return Optional.empty();
        }
        ProfileRule region = new ProfileRule(Operator.REGION_IN, ProfileField.RESIDENCE_REGION, null, null, null,
                List.copyOf(codes), null, List.of());
        if (requirement.minimumMonths() == null) {
            return Optional.of(region);
        }
        return Optional.of(group(Operator.ALL, List.of(region, range(Operator.NUMBER_RANGE,
                ProfileField.CURRENT_REGION_RESIDENCE_MONTHS, (long) requirement.minimumMonths(), MAXIMUM_MONTHS))));
    }

    private static Optional<ProfileRule> familyType(List<String> values) {
        if (values.isEmpty()) {
            return Optional.empty();
        }
        List<ProfileRule> rules = values.stream().map(value -> equal(ProfileField.FAMILY_TYPE, value)).toList();
        return Optional.of(rules.size() == 1 ? rules.getFirst() : group(Operator.ANY, rules));
    }

    /** 선택 요건 하나라도 조건으로 바꾸지 못하면, 그 선택으로 지원할 수 있는 사람을 놓치지 않게 전체를 직접 확인으로 둔다. */
    private Optional<ProfileRule> anyOf(List<Requirement> options) {
        List<ProfileRule> rules = new ArrayList<>();
        for (Requirement option : options) {
            Optional<ProfileRule> rule = option.adjustments().isEmpty() ? rule(option) : Optional.empty();
            if (rule.isEmpty()) {
                return Optional.empty();
            }
            rules.add(rule.get());
        }
        return rules.isEmpty() ? Optional.empty() : Optional.of(group(Operator.ANY, rules));
    }

    private void exclusions() {
        for (ExtractedEligibility.Exclusion exclusion : source.eligibility().exclusions()) {
            guidances.add(new Guidance("exclusion-" + (guidances.size() + 1), GuidanceKind.APPLICATION_EXCLUSION,
                    limit(exclusion.title(), 300), limit(firstText(exclusion.body(), exclusion.title()), 10000), List.of(),
                    List.of(new GuidanceTarget(TargetScope.ANNOUNCEMENT, null, null)),
                    evidence(exclusion.evidencePath(), source.eligibility().common().evidencePath())));
        }
    }

    private List<EligibilityContent.Qualification> usedQualifications(List<EligibilityPath> paths) {
        Set<String> used = new LinkedHashSet<>();
        paths.forEach(path -> used.addAll(path.qualificationIds()));
        return qualifications.stream().filter(qualification -> used.contains(qualification.id())).toList();
    }

    /** 항목 근거가 없으면 같은 묶음의 첫 근거, 그것도 없으면 첫 신청자격 근거를 쓴다. 근거를 지어내지 않는다. */
    private List<String> evidence(String path, String groupPath) {
        if (path != null && evidenceIds.containsKey(path)) {
            return List.of(evidenceIds.get(path));
        }
        if (groupPath != null) {
            Optional<String> grouped = evidenceIds.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(groupPath + ".")).map(Map.Entry::getValue).findFirst();
            if (grouped.isPresent()) {
                return List.of(grouped.get());
            }
        }
        return List.of(evidences.getFirst().id());
    }

    private static List<String> names(List<ExtractedEligibility.Qualification> qualifications) {
        return qualifications.stream().map(ExtractedEligibility.Qualification::exactName).toList();
    }

    private static ProfileRule equal(ProfileField field, String value) {
        return new ProfileRule(Operator.EQUALS, field, value, null, null, List.of(), null, List.of());
    }

    private static ProfileRule range(Operator operator, ProfileField field, long minimum, long maximum) {
        return new ProfileRule(operator, field, null, minimum, maximum, List.of(), null, List.of());
    }

    private static ProfileRule group(Operator operator, List<ProfileRule> children) {
        return new ProfileRule(operator, null, null, null, null, List.of(), null, children);
    }

    private static String compact(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "");
    }

    private static List<String> nonBlank(String... values) {
        return Arrays.stream(values).filter(value -> value != null && !value.isBlank()).toList();
    }

    private static String firstText(String value, String fallback) {
        return value != null && !value.isBlank() ? value.strip() : fallback;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : limit(value.strip(), 300);
    }

    private static String limit(String value, int maximum) {
        String text = value == null ? "" : value.strip();
        return text.length() <= maximum ? text : text.substring(0, maximum);
    }
}

package com.toadzip.backend.eligibility.domain;

import static com.toadzip.backend.eligibility.domain.EligibilityContent.*;
import static com.toadzip.backend.eligibility.domain.BasicComparisonResult.*;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class BasicComparisonEvaluator {
    public static final String VERSION = "basic-comparison-2";

    public BasicComparisonResult evaluate(
            EligibilityContent content,
            EligibilityProfile profile,
            Map<Long, IncomeStandard> standards,
            RegionMembership regions) {
        if (content.metadata().applicantKind() == ApplicantKind.ORGANIZATION) {
            return result(
                    content,
                    Scope.OUT_OF_SCOPE,
                    "기업이 신청·계약하는 공고로 개인 비교 대상이 아닙니다.",
                    List.of(),
                    List.of());
        }
        ProfileRuleEvaluator evaluator =
                new ProfileRuleEvaluator(
                        content.metadata().referenceDate(), profile, standards, regions);
        Map<String, EligibilityPath> paths =
                content.paths().stream()
                        .collect(Collectors.toMap(EligibilityPath::id, Function.identity()));
        Map<String, SupplyUnit> units =
                content.supplyUnits().stream()
                        .collect(Collectors.toMap(SupplyUnit::id, Function.identity()));
        List<OptionResult> options =
                content.options().stream()
                        .map(
                                option ->
                                        option(
                                                option,
                                                paths.get(option.pathId()),
                                                units.get(option.supplyUnitId()),
                                                content,
                                                profile,
                                                evaluator))
                        .toList();
        List<Guidance> guidances =
                content.guidances().stream()
                        .sorted(Comparator.comparing(Guidance::kind).thenComparing(Guidance::id))
                        .toList();
        String headline = "비교한 기본 조건과 신청 전에 직접 확인할 요건을 살펴보세요.";
        if (content.metadata().accessMode() == AccessMode.REQUIRED_QUALIFICATION) {
            headline = "이 공고는 별도의 신청자격도 필요해요. 해당 항목을 직접 확인해 주세요.";
        }
        return result(content, Scope.INDIVIDUAL, headline, options, guidances);
    }

    private BasicComparisonResult result(
            EligibilityContent content,
            Scope scope,
            String headline,
            List<OptionResult> options,
            List<Guidance> guidances) {
        return new BasicComparisonResult(
                scope,
                content.metadata().referenceDate(),
                headline,
                options,
                guidances,
                content.documents(),
                content.evidences(),
                content.supplyUnits(),
                content.quantities(),
                content.rentOffers(),
                content.schedules(),
                content.metadata().scopeNote(),
                VERSION);
    }

    private OptionResult option(
            ApplicationOption option,
            EligibilityPath path,
            SupplyUnit unit,
            EligibilityContent content,
            EligibilityProfile profile,
            ProfileRuleEvaluator evaluator) {
        List<String> directCheckIds =
                content.guidances().stream()
                        .filter(guidance -> guidance.appliesTo(option))
                        .sorted(Comparator.comparing(Guidance::kind).thenComparing(Guidance::id))
                        .map(Guidance::id)
                        .toList();
        if (option.recruitmentStatus() != RecruitmentStatus.RECRUITING) {
            return new OptionResult(
                    option.id(),
                    unit.label(),
                    path.label(),
                    path.kind(),
                    option.recruitmentStatus(),
                    BasicStatus.NOT_EVALUATED,
                    "이번 모집 여부와 접수 일정을 원문에서 확인해 주세요.",
                    List.of(),
                    List.of(),
                    directCheckIds);
        }
        List<CheckResult> checks =
                path.checks().stream().map(check -> check(check, evaluator)).toList();
        Set<ProfileField> missing = new LinkedHashSet<>();
        for (CheckResult check : checks) {
            missing.addAll(check.reading().missingInputs());
        }
        List<MissingInput> inputs =
                missing.stream()
                        .sorted()
                        .map(
                                field ->
                                        new MissingInput(
                                                field,
                                                field.optional(),
                                                field.optional()
                                                        && profile.familyDetailsState()
                                                                == FamilyDetailsState.SKIPPED))
                        .toList();
        BasicStatus status = status(checks);
        return new OptionResult(
                option.id(),
                unit.label(),
                path.label(),
                path.kind(),
                option.recruitmentStatus(),
                status,
                message(status, path.kind()),
                checks,
                inputs,
                directCheckIds);
    }

    static CheckResult check(ProfileCheck check, ProfileRuleEvaluator evaluator) {
        if (check.appliesWhen() != null) {
            RuleReading applicability = evaluator.evaluate(check.appliesWhen());
            if (applicability.status() == RuleReading.Status.INPUT_NEEDED) {
                return new CheckResult(
                        check.id(),
                        check.label(),
                        applicability,
                        check.evidenceIds(),
                        check.fallbackGuidanceId());
            }
            if (applicability.status() != RuleReading.Status.SATISFIED) {
                return new CheckResult(
                        check.id(),
                        check.label(),
                        RuleReading.manual(),
                        check.evidenceIds(),
                        check.fallbackGuidanceId());
            }
        }
        RuleReading reading = evaluator.evaluate(check.rule());
        String directCheckId = null;
        if (reading.status() == RuleReading.Status.DIRECT_CHECK_REQUIRED) {
            directCheckId = check.fallbackGuidanceId();
        }
        return new CheckResult(
                check.id(), check.label(), reading, check.evidenceIds(), directCheckId);
    }

    private BasicStatus status(List<CheckResult> checks) {
        if (checks.isEmpty()) {
            return BasicStatus.NOT_EVALUATED;
        }
        if (has(checks, RuleReading.Status.NOT_SATISFIED)) {
            return BasicStatus.NOT_MATCHED;
        }
        if (has(checks, RuleReading.Status.INPUT_NEEDED)) {
            return BasicStatus.INPUT_NEEDED;
        }
        if (has(checks, RuleReading.Status.DIRECT_CHECK_REQUIRED)) {
            return BasicStatus.PARTIALLY_COMPARED;
        }
        return BasicStatus.MATCHED;
    }

    private boolean has(List<CheckResult> checks, RuleReading.Status status) {
        return checks.stream().anyMatch(check -> check.reading().status() == status);
    }

    private String message(BasicStatus status, PathKind coverage) {
        if (status == BasicStatus.MATCHED && coverage == PathKind.COMMON_ONLY) {
            return "입력하신 공통 조건은 맞아요. 별도의 필수 신청자격도 확인해 주세요.";
        }
        return switch (status) {
            case MATCHED -> "입력하신 정보로 비교한 이 일반 경로의 기본 조건을 충족해요. 직접 확인할 요건도 살펴봐 주세요.";
            case NOT_MATCHED -> "비교한 이 경로의 기본 조건 중 맞지 않는 항목이 있어요. 다른 신청 경로는 직접 확인해 주세요.";
            case INPUT_NEEDED -> "일부 기본 조건은 입력이 없어 비교하지 않았어요. 선택 가족정보는 원하시면 추가할 수 있어요.";
            case PARTIALLY_COMPARED -> "일부 조건의 적용 기준을 직접 확인해야 해요. 확인한 항목과 안내를 함께 살펴봐 주세요.";
            case NOT_EVALUATED -> "자동으로 비교한 기본 조건이 없어요. 신청 요건을 원문에서 확인해 주세요.";
        };
    }
}

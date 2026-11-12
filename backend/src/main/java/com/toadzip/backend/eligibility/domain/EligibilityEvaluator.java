package com.toadzip.backend.eligibility.domain;

import static com.toadzip.backend.eligibility.domain.EligibilityContent.*;
import static com.toadzip.backend.eligibility.domain.EligibilityDecision.*;

import com.toadzip.backend.eligibility.domain.BasicComparisonResult.CheckResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class EligibilityEvaluator {
    public static final String VERSION = "eligibility-2";

    public EligibilityDecision evaluate(EligibilityContent content, EligibilityProfile profile,
            Map<Long, IncomeStandard> standards, RegionMembership regions) {
        if (content.metadata().applicantKind() != ApplicantKind.INDIVIDUAL
                || content.metadata().accessMode() != AccessMode.APPLICATION_ROUTES) {
            return new EligibilityDecision(Status.NOT_EVALUATED, "실제 신청 경로의 구조화 확인이 필요해요.",
                    content.metadata().referenceDate(), VERSION, List.of(), List.of(), List.of());
        }
        ProfileRuleEvaluator evaluator = new ProfileRuleEvaluator(
                content.metadata().referenceDate(), profile, standards, regions);
        List<CheckResult> common = checks(content.commonChecks(), evaluator);
        Map<String, PathDecision> paths = content.paths().stream().collect(Collectors.toMap(
                EligibilityPath::id, path -> new PathDecision(path.id(), checks(path.checks(), evaluator))));
        Map<String, EligibilityPath> definitions = content.paths().stream()
                .collect(Collectors.toMap(EligibilityPath::id, Function.identity()));
        Map<String, SupplyUnit> units = content.supplyUnits().stream()
                .collect(Collectors.toMap(SupplyUnit::id, Function.identity()));
        List<OptionDecision> options = content.options().stream().map(option -> option(option,
                units.get(option.supplyUnitId()), definitions.get(option.pathId()), common,
                paths.get(option.pathId()).checks(), content)).toList();
        Status status = aggregate(options, common);
        return new EligibilityDecision(status, headline(status), content.metadata().referenceDate(), VERSION,
                common, content.paths().stream().map(path -> paths.get(path.id())).toList(), options);
    }

    private List<CheckResult> checks(List<ProfileCheck> checks, ProfileRuleEvaluator evaluator) {
        return checks.stream().map(check -> BasicComparisonEvaluator.check(check, evaluator))
                .sorted(Comparator.comparing(check ->
                        check.reading().status() != RuleReading.Status.NOT_SATISFIED)).toList();
    }

    private OptionDecision option(ApplicationOption option, SupplyUnit unit, EligibilityPath path,
            List<CheckResult> common, List<CheckResult> pathChecks, EligibilityContent content) {
        List<CheckResult> checks = new ArrayList<>(common);
        checks.addAll(pathChecks);
        Set<ProfileField> missing = new LinkedHashSet<>();
        Set<String> directChecks = new LinkedHashSet<>();
        Set<String> notApplied = new LinkedHashSet<>();
        for (CheckResult check : checks) {
            missing.addAll(check.reading().missingInputs());
            if (check.reading().status() == RuleReading.Status.DIRECT_CHECK_REQUIRED
                    && check.directCheckId() != null) {
                (check.reading().blocksEligibility() ? directChecks : notApplied).add(check.directCheckId());
            }
        }
        List<Guidance> guides = content.guidances().stream().filter(guide -> guide.appliesTo(option)).toList();
        guides.stream().filter(guide -> guide.kind() == GuidanceKind.REQUIRED_QUALIFICATION)
                .map(Guidance::id).forEach(directChecks::add);
        Status status = status(option, checks, path.qualificationIds(), directChecks);
        return new OptionDecision(option.id(), unit.id(), unit.label(), unit.regionCodes(), path.id(), path.label(),
                option.recruitmentStatus(), status, optionMessage(status, option, path, directChecks),
                missingMessage(status, missing), missing.stream().sorted().toList(), path.qualificationIds(),
                List.copyOf(directChecks), guides.stream()
                        .filter(guide -> guide.kind() == GuidanceKind.APPLICATION_EXCLUSION)
                        .map(Guidance::id).toList(), reminders(guides, content, notApplied));
    }

    /**
     * 판정을 막지 않고 목록으로만 보이는 신청 전 확인 안내. 조건에 연결되지 않은 안내와, 적용 조건 밖이라
     * 따지지 않은 조건(가산 대상 등)에 연결한 안내다. 그 밖의 조건에 연결된 안내는 그 조건 결과로 보인다.
     */
    private List<String> reminders(List<Guidance> guides, EligibilityContent content, Set<String> notApplied) {
        Set<String> linked = new LinkedHashSet<>();
        content.commonChecks().forEach(check -> linked.add(check.fallbackGuidanceId()));
        content.paths().forEach(path -> path.checks().forEach(check -> linked.add(check.fallbackGuidanceId())));
        linked.removeAll(notApplied);
        return guides.stream().filter(guide -> guide.kind() == GuidanceKind.REQUIRED_CHECK)
                .map(Guidance::id).filter(id -> !linked.contains(id)).toList();
    }

    private String optionMessage(Status status, ApplicationOption option, EligibilityPath path,
            Set<String> directChecks) {
        if (option.recruitmentStatus() == RecruitmentStatus.CANCELLED) {
            return "모집 취소";
        }
        if (status == Status.INELIGIBLE) {
            return path.label() + " 미충족";
        }
        if (status == Status.UNDETERMINED
                && (!path.qualificationIds().isEmpty() || !directChecks.isEmpty())) {
            return "신청자격을 직접 확인해 주세요.";
        }
        if (status == Status.UNDETERMINED) {
            return "필요한 정보를 입력하면 확인해드릴게요.";
        }
        return headline(status);
    }

    private String missingMessage(Status status, Set<ProfileField> fields) {
        if (fields.isEmpty()) {
            return null;
        }
        String labels = fields.stream().sorted().map(ProfileField::label).collect(Collectors.joining(", "));
        if (status == Status.INELIGIBLE) {
            return labels + " 입력 시 해당 조건도 확인해드릴게요.";
        }
        return labels + " 입력 시 확인해드릴게요.";
    }

    private Status status(ApplicationOption option, List<CheckResult> checks,
            List<String> requiredQualifications, Set<String> directChecks) {
        if (!active(option.recruitmentStatus())) {
            return Status.NOT_EVALUATED;
        }
        if (checks.stream().anyMatch(check -> check.reading().status() == RuleReading.Status.NOT_SATISFIED)) {
            return Status.INELIGIBLE;
        }
        if (!requiredQualifications.isEmpty() || !directChecks.isEmpty()
                || checks.stream().anyMatch(check -> check.reading().blocksEligibility())) {
            return Status.UNDETERMINED;
        }
        // 비교해 충족한 조건이 하나도 없으면(모두 적용 조건 밖 등) 지원 가능으로 확정하지 않는다.
        if (checks.stream().noneMatch(check -> check.reading().status() == RuleReading.Status.SATISFIED)) {
            return Status.NOT_EVALUATED;
        }
        return Status.ELIGIBLE;
    }

    private Status aggregate(List<OptionDecision> options, List<CheckResult> common) {
        if (options.stream().anyMatch(option -> option.status() == Status.ELIGIBLE)) {
            return Status.ELIGIBLE;
        }
        List<OptionDecision> active = options.stream().filter(option -> active(option.recruitmentStatus())).toList();
        boolean recruitmentUnknown = options.stream()
                .anyMatch(option -> option.recruitmentStatus() == RecruitmentStatus.UNKNOWN);
        if (active.isEmpty() && !recruitmentUnknown) {
            return Status.NOT_EVALUATED;
        }
        if (common.stream().anyMatch(check -> check.reading().status() == RuleReading.Status.NOT_SATISFIED)) {
            return Status.INELIGIBLE;
        }
        if (!recruitmentUnknown && active.stream().allMatch(option -> option.status() == Status.INELIGIBLE)) {
            return Status.INELIGIBLE;
        }
        return Status.UNDETERMINED;
    }

    private boolean active(RecruitmentStatus status) {
        return status == RecruitmentStatus.RECRUITING || status == RecruitmentStatus.UPCOMING;
    }

    private String headline(Status status) {
        return switch (status) {
            case ELIGIBLE -> "지원할 수 있어요.";
            case INELIGIBLE -> "이 공고의 신청자격을 충족하지 않아요.";
            case UNDETERMINED -> "입력할 정보와 직접 확인할 신청자격을 살펴보세요.";
            case NOT_EVALUATED -> "판정할 실제 모집 선택지를 확인해 주세요.";
        };
    }
}

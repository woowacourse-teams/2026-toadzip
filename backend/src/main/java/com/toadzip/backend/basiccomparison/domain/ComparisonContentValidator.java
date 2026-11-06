package com.toadzip.backend.basiccomparison.domain;

import static com.toadzip.backend.basiccomparison.domain.BasicRuleValidator.require;
import static com.toadzip.backend.basiccomparison.domain.ComparisonContent.*;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public final class ComparisonContentValidator {
    private final RegionMembership regions;
    private final BasicRuleValidator rules;

    public ComparisonContentValidator(RegionMembership regions, Set<Long> standardIds) {
        this.regions = regions;
        this.rules = new BasicRuleValidator(standardIds);
    }

    public void validate(ComparisonContent content, boolean publication) {
        require(content != null && content.metadata() != null, "공고 비교 메타데이터가 필요합니다.");
        Metadata metadata = content.metadata();
        require(
                metadata.referenceDate() != null
                        && metadata.applicantKind() != null
                        && metadata.accessMode() != null
                        && metadata.reviewStatus() != null,
                "공고 분류와 기준일이 필요합니다.");
        text(metadata.scopeNote(), 2000, "구조화한 비교 범위");
        if (publication) {
            require(metadata.reviewStatus() == ReviewStatus.REVIEWED, "검토 완료한 내용만 공개할 수 있습니다.");
        }
        Map<String, SourceDocument> documents = index(content.documents(), SourceDocument::id);
        Map<String, Evidence> evidences = index(content.evidences(), Evidence::id);
        Map<String, SupplyUnit> units = index(content.supplyUnits(), SupplyUnit::id);
        Map<String, ComparisonPath> paths = index(content.paths(), ComparisonPath::id);
        Map<String, Guidance> guidances = index(content.guidances(), Guidance::id);
        Map<String, ComparisonOption> choices = index(content.options(), ComparisonOption::id);
        Map<String, Qualification> qualifications = index(content.qualifications(), Qualification::id);
        index(content.quantities(), Quantity::id);
        index(content.rentOffers(), RentOffer::id);
        index(content.schedules(), Schedule::id);
        sources(content, documents, evidences);
        require(
                evidences.containsKey(metadata.classificationEvidenceId()),
                "신청 주체와 모집 경로의 분류 근거가 필요합니다.");
        for (SupplyUnit unit : content.supplyUnits()) {
            unit(unit);
        }
        Set<String> combinations = options(content.options(), units, paths);
        for (Guidance guidance : content.guidances()) {
            guidance(guidance, evidences, units, paths, combinations);
        }
        for (Qualification qualification : content.qualifications()) {
            qualification(qualification, evidences);
        }
        for (ComparisonPath path : content.paths()) {
            referencesIfPresent(path.qualificationIds(), qualifications, "경로 필수 자격");
            path(path, content, guidances, evidences);
        }
        index(content.commonChecks(), BasicCheck::id);
        for (BasicCheck check : content.commonChecks()) {
            check(check, null, content, guidances, evidences);
        }
        access(content);
        information(content, units, choices, evidences);
    }

    private void sources(
            ComparisonContent content,
            Map<String, SourceDocument> documents,
            Map<String, Evidence> evidences) {
        require(!documents.isEmpty() && !evidences.isEmpty(), "원문 파일과 근거가 필요합니다.");
        for (SourceDocument document : content.documents()) {
            text(document.fileName(), 500, "원문 파일명");
            url(document.downloadUrl(), false);
            require(document.pageCount() > 0 && document.pageCount() <= 10000, "원문 쪽수가 올바르지 않습니다.");
            if (document.sha256() != null) {
                require(document.sha256().matches("[a-fA-F0-9]{64}"), "파일 해시가 올바르지 않습니다.");
            }
        }
        for (Evidence evidence : content.evidences()) {
            require(documents.containsKey(evidence.documentId()), "근거 파일이 같은 공개 내용에 없습니다.");
            require(
                    evidence.firstPage() > 0
                            && evidence.lastPage() >= evidence.firstPage()
                            && evidence.lastPage()
                                    <= documents.get(evidence.documentId()).pageCount(),
                    "근거가 원문 쪽수를 벗어났습니다.");
            text(evidence.location(), 500, "표 또는 문단 위치");
            text(evidence.description(), 4000, "근거 설명");
        }
    }

    private void unit(SupplyUnit unit) {
        text(unit.label(), 300, "신청 단위명");
        index(unit.housingTypes(), PhysicalHousing::id);
        require(new HashSet<>(unit.regionCodes()).size() == unit.regionCodes().size(), "신청 지역이 중복됩니다.");
        for (String region : unit.regionCodes()) {
            require(regions.isKnown(region), "알 수 없는 신청 단위 지역입니다.");
        }
        for (PhysicalHousing housing : unit.housingTypes()) {
            text(housing.complexLabel(), 300, "단지명");
            text(housing.typeLabel(), 100, "주택형명");
            text(housing.rentalType(), 100, "임대유형");
            require(
                    housing.exclusiveArea() != null && housing.exclusiveArea().signum() > 0,
                    "전용면적이 올바르지 않습니다.");
            require(
                    housing.housingComplexId() == null || housing.housingComplexId() > 0,
                    "단지 ID가 올바르지 않습니다.");
            require(
                    housing.housingTypeId() == null || housing.housingTypeId() > 0,
                    "주택형 ID가 올바르지 않습니다.");
        }
    }

    private Set<String> options(
            List<ComparisonOption> options,
            Map<String, SupplyUnit> units,
            Map<String, ComparisonPath> paths) {
        Set<String> combinations = new HashSet<>();
        for (ComparisonOption option : options) {
            require(
                    units.containsKey(option.supplyUnitId()) && paths.containsKey(option.pathId()),
                    "선택지가 없는 단위나 경로를 참조합니다.");
            require(option.recruitmentStatus() != null, "모집 상태가 필요합니다.");
            require(
                    combinations.add(combination(option.pathId(), option.supplyUnitId())),
                    "중복된 신청 단위와 경로입니다.");
        }
        return combinations;
    }

    private void guidance(
            Guidance guidance,
            Map<String, Evidence> evidences,
            Map<String, SupplyUnit> units,
            Map<String, ComparisonPath> paths,
            Set<String> combinations) {
        require(guidance.kind() != null && !guidance.targets().isEmpty(), "안내 종류와 적용 범위가 필요합니다.");
        text(guidance.title(), 300, "안내 제목");
        text(guidance.body(), 10000, "안내 내용");
        require(guidance.items().size() <= 100, "안내 목록이 너무 큽니다.");
        for (String item : guidance.items()) {
            text(item, 2000, "안내 항목");
        }
        references(guidance.evidenceIds(), evidences, "안내 근거");
        require(
                new HashSet<>(guidance.targets()).size() == guidance.targets().size(),
                "안내 적용 범위가 중복됩니다.");
        for (GuidanceTarget target : guidance.targets()) {
            require(target.scope() != null, "안내 적용 범위가 필요합니다.");
            switch (target.scope()) {
                case ANNOUNCEMENT ->
                        require(
                                target.pathId() == null && target.supplyUnitId() == null,
                                "공고 전체 안내에 단위나 경로를 지정할 수 없습니다.");
                case UNIT ->
                        require(
                                target.pathId() == null && units.containsKey(target.supplyUnitId()),
                                "안내 단위가 올바르지 않습니다.");
                case PATH ->
                        require(
                                target.supplyUnitId() == null && paths.containsKey(target.pathId()),
                                "안내 경로가 올바르지 않습니다.");
                case OPTION ->
                        require(
                                combinations.contains(
                                        combination(target.pathId(), target.supplyUnitId())),
                                "안내 대상 선택지가 없습니다.");
            }
        }
    }

    private void path(
            ComparisonPath path,
            ComparisonContent content,
            Map<String, Guidance> guidances,
            Map<String, Evidence> evidences) {
        text(path.label(), 300, "비교 경로명");
        require(path.kind() != null, "비교 범위가 필요합니다.");
        require(
                content.options().stream().anyMatch(option -> option.pathId().equals(path.id())),
                "신청 단위에 연결되지 않은 비교 경로입니다.");
        index(path.checks(), BasicCheck::id);
        for (BasicCheck check : path.checks()) {
            check(check, path, content, guidances, evidences);
        }
    }

    private void check(BasicCheck check, ComparisonPath path, ComparisonContent content,
            Map<String, Guidance> guidances, Map<String, Evidence> evidences) {
        text(check.label(), 300, "조건 이름");
        references(check.evidenceIds(), evidences, "조건 근거");
        rule(check.rule());
        if (check.appliesWhen() != null) {
            rule(check.appliesWhen());
        }
        require(check.appliesWhen() == null && !needsFallback(check.rule())
                        || check.fallbackGuidanceId() != null,
                "자동 판정할 수 없는 경우의 직접 확인 안내가 필요합니다.");
        if (check.fallbackGuidanceId() != null) {
            fallback(check, path, content, guidances);
        }
    }

    private void qualification(Qualification qualification, Map<String, Evidence> evidences) {
        text(qualification.exactName(), 300, "정확한 자격명");
        text(qualification.conditionsAsWritten(), 10000, "자격이 요구하는 원문 조건");
        if (qualification.legalBasis() != null) {
            text(qualification.legalBasis(), 4000, "법적 근거");
        }
        require(new HashSet<>(qualification.categories()).size() == qualification.categories().size(),
                "탐색 분류가 중복됩니다.");
        for (String category : qualification.categories()) {
            require(category != null && category.matches("[A-Z][A-Z0-9_]{0,99}"), "탐색 분류 코드가 올바르지 않습니다.");
        }
        references(qualification.evidenceIds(), evidences, "필수 자격 근거");
    }

    private void fallback(
            BasicCheck check,
            ComparisonPath path,
            ComparisonContent content,
            Map<String, Guidance> guidances) {
        Guidance guidance = guidances.get(check.fallbackGuidanceId());
        require(
                guidance != null && guidance.kind() == GuidanceKind.REQUIRED_CHECK,
                "비교 한계는 필수 확인 안내에 연결해야 합니다.");
        require(
                content.options().stream()
                        .filter(option -> path == null || option.pathId().equals(path.id()))
                        .allMatch(guidance::appliesTo),
                "비교 한계 안내가 해당 선택지에 적용되지 않습니다.");
    }

    private boolean needsFallback(BasicRule rule) {
        if (rule.operator() == BasicRule.Operator.INCOME_CEILING
                || rule.field() == ProfileField.PARENTS_MONTHLY_INCOME
                || rule.field() == ProfileField.MARRIAGE_EVENT
                || rule.field() == ProfileField.YOUNGEST_CHILD) {
            return true;
        }
        if (rule.field() != null
                && rule.field().flow() != ProfileField.Flow.ALWAYS
                && rule.field().flow() != ProfileField.Flow.OPTIONAL_FAMILY) {
            return true;
        }
        return rule.children().stream().anyMatch(this::needsFallback);
    }

    private void rule(BasicRule rule) {
        rules.validate(rule);
        for (String region : rule.regions()) {
            require(regions.isKnown(region), "알 수 없는 기준 지역입니다.");
        }
        for (BasicRule child : rule.children()) {
            rule(child);
        }
    }

    private void access(ComparisonContent content) {
        if (content.metadata().applicantKind() == ApplicantKind.ORGANIZATION) {
            require(
                    content.paths().isEmpty() && content.options().isEmpty(),
                    "기업 신청 유형에 개인 비교 경로를 만들 수 없습니다.");
            return;
        }
        if (content.metadata().accessMode() == AccessMode.APPLICATION_ROUTES) {
            require(!content.paths().isEmpty()
                            && content.paths().stream().allMatch(path -> path.kind() == PathKind.APPLICATION),
                    "신청 경로 모드에는 실제 신청 경로를 구조화해야 합니다.");
            for (ComparisonPath path : content.paths()) {
                require(!path.checks().isEmpty() || !content.commonChecks().isEmpty()
                                || !path.qualificationIds().isEmpty(),
                        "아무 조건도 구조화하지 않은 경로를 신청 가능으로 판정할 수 없습니다.");
            }
            return;
        }
        if (content.metadata().accessMode() == AccessMode.REQUIRED_QUALIFICATION) {
            require(
                    content.paths().stream().allMatch(path -> path.kind() == PathKind.COMMON_ONLY),
                    "필수 별도 자격 공고에 일반 경로를 만들 수 없습니다.");
            require(
                    content.guidances().stream()
                            .anyMatch(
                                    guidance ->
                                            guidance.kind() == GuidanceKind.REQUIRED_QUALIFICATION
                                                    && guidance.targets().stream()
                                                            .anyMatch(
                                                                    target ->
                                                                            target.scope()
                                                                                    == TargetScope
                                                                                            .ANNOUNCEMENT)),
                    "공고 전체의 필수 별도 자격 안내가 필요합니다.");
            return;
        }
        require(
                content.paths().stream().anyMatch(path -> path.kind() == PathKind.GENERAL),
                "일반 경로가 있다는 분류에는 실제 일반 비교 경로가 필요합니다.");
    }

    private void information(
            ComparisonContent content,
            Map<String, SupplyUnit> units,
            Map<String, ComparisonOption> options,
            Map<String, Evidence> evidences) {
        for (Quantity quantity : content.quantities()) {
            text(quantity.label(), 300, "물량 이름");
            require(
                    quantity.kind() != null
                            && quantity.value() != null
                            && quantity.value().signum() >= 0,
                    "물량이 올바르지 않습니다.");
            if (quantity.kind() == QuantityKind.PERCENT) {
                require(
                        quantity.value().compareTo(java.math.BigDecimal.valueOf(100)) <= 0,
                        "비율은 100 이하여야 합니다.");
            }
            if (quantity.kind() != QuantityKind.PERCENT) {
                require(quantity.value().stripTrailingZeros().scale() <= 0, "물량은 정수여야 합니다.");
            }
            scope(quantity.supplyUnitIds(), quantity.optionIds(), units, options, "물량");
            references(quantity.evidenceIds(), evidences, "물량 근거");
        }
        for (RentOffer rent : content.rentOffers()) {
            text(rent.label(), 300, "임대조건 이름");
            text(rent.applicabilityAsWritten(), 2000, "임대조건 적용 설명");
            amounts(rent.depositMinimum(), rent.depositMaximum());
            amounts(rent.monthlyRentMinimum(), rent.monthlyRentMaximum());
            if (rent.pairedAmounts()) {
                require(
                        rent.depositMinimum().equals(rent.depositMaximum())
                                && rent.monthlyRentMinimum().equals(rent.monthlyRentMaximum()),
                        "확정 조합에는 범위를 사용할 수 없습니다.");
            }
            scope(rent.supplyUnitIds(), rent.optionIds(), units, options, "임대조건");
            references(rent.evidenceIds(), evidences, "임대조건 근거");
        }
        for (Schedule schedule : content.schedules()) {
            text(schedule.label(), 300, "일정 이름");
            text(schedule.targetAsWritten(), 1000, "일정 대상 원문");
            require(schedule.startsAt() != null, "일정 시작 시각이 필요합니다.");
            require(
                    schedule.endsAt() == null || !schedule.endsAt().isBefore(schedule.startsAt()),
                    "일정 종료 시각이 시작보다 빠릅니다.");
            url(schedule.applicationUrl(), true);
            referencesIfPresent(schedule.supplyUnitIds(), units, "일정 단위");
            referencesIfPresent(schedule.optionIds(), options, "일정 선택지");
            references(schedule.evidenceIds(), evidences, "일정 근거");
        }
    }

    private void scope(List<String> unitIds, List<String> optionIds,
            Map<String, SupplyUnit> units, Map<String, ComparisonOption> options, String name) {
        require(!unitIds.isEmpty() || !optionIds.isEmpty(), name + " 적용 범위가 필요합니다.");
        referencesIfPresent(unitIds, units, name + " 단위");
        referencesIfPresent(optionIds, options, name + " 선택지");
    }

    private <T> void referencesIfPresent(List<String> ids, Map<String, T> targets, String name) {
        if (!ids.isEmpty()) {
            references(ids, targets, name);
        }
    }

    private void amounts(Long minimum, Long maximum) {
        require(
                minimum != null && maximum != null && minimum >= 0 && maximum >= minimum,
                "임대 금액 범위가 올바르지 않습니다.");
    }

    private String combination(String path, String unit) {
        return path + ":" + unit;
    }

    private <T> Map<String, T> index(List<T> values, Function<T, String> key) {
        require(values.size() <= 2000, "공개 내용의 항목 수를 초과했습니다.");
        Map<String, T> indexed = new HashMap<>();
        for (T value : values) {
            String id = key.apply(value);
            require(id != null && id.matches("[a-zA-Z0-9._-]{1,100}"), "항목 코드가 올바르지 않습니다.");
            require(indexed.put(id, value) == null, "중복된 항목 코드입니다.");
        }
        return indexed;
    }

    private void text(String value, int maximum, String name) {
        require(
                value != null && !value.isBlank() && value.length() <= maximum,
                name + "이 필요하거나 너무 깁니다.");
    }

    private <T> void references(List<String> ids, Map<String, T> targets, String name) {
        require(
                !ids.isEmpty()
                        && ids.stream().allMatch(targets::containsKey)
                        && new HashSet<>(ids).size() == ids.size(),
                name + " 참조가 올바르지 않습니다.");
    }

    private void url(String value, boolean optional) {
        if (optional && value == null) {
            return;
        }
        text(value, 2000, "URL");
        try {
            URI uri = new URI(value);
            require(
                    ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                            && uri.getHost() != null
                            && uri.getUserInfo() == null,
                    "HTTP 원문 URL이 필요합니다.");
        } catch (URISyntaxException exception) {
            throw new com.toadzip.backend.basiccomparison.exception.InvalidComparisonException(
                    "URL 형식이 올바르지 않습니다.");
        }
    }
}

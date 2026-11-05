package com.toadzip.backend.eligibility.domain;

import static com.toadzip.backend.eligibility.domain.RuleSetData.*;

import com.toadzip.backend.eligibility.exception.InvalidEligibilityException;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Validates a whole replacement, including references that database column constraints cannot
 * express.
 */
public final class RuleSetValidator {
    private final RegionMembership regions;
    private final Set<Long> incomeStandards;

    public RuleSetValidator(RegionMembership regions, Set<Long> incomeStandards) {
        this.regions = regions;
        this.incomeStandards = Set.copyOf(incomeStandards);
    }

    public void validateStructure(RuleSetContent content) {
        List<String> errors = new ArrayList<>();
        if (content == null || content.metadata() == null) {
            throw new InvalidEligibilityException("content.metadata는 필수입니다.");
        }
        metadata(content.metadata(), errors);
        int count = 0;
        for (RuleSetSection section : RuleSetSection.values()) {
            count += section.rows(content).size();
            fields(section, content, errors);
        }
        require(count <= 20000, "구조화 행은 20,000개 이하여야 합니다.", errors);
        fail(errors);
        references(content, errors);
        fail(errors);
    }

    public void validateForPublication(RuleSetContent content) {
        validateStructure(content);
        List<String> errors = new ArrayList<>();
        require(!content.documents().isEmpty(), "공개할 원문 파일이 필요합니다.", errors);
        require(!content.evidences().isEmpty(), "공개할 원문 근거가 필요합니다.", errors);
        require(
                !"NOT_STARTED".equals(content.metadata().structuringStatus()),
                "구조화 시작 전에는 공개할 수 없습니다.",
                errors);
        if ("INDIVIDUAL".equals(content.metadata().applicantKind())) {
            require(!content.options().isEmpty(), "개인 공고에는 신청 선택지가 필요합니다.", errors);
        }
        evidence(content, errors);
        supply(content, errors);
        conditions(content, errors);
        selection(content, errors);
        rentsAndSchedules(content, errors);
        graph(content, errors);
        fail(errors);
    }

    private void metadata(RuleSetMetadata metadata, List<String> errors) {
        member(
                metadata.recruitmentKind(),
                Set.of("INITIAL", "ADDITIONAL", "WAITLIST", "REGULAR_BATCH", "CONTINUOUS"),
                "metadata.recruitmentKind",
                errors);
        member(
                metadata.applicantKind(),
                Set.of("INDIVIDUAL", "ORGANIZATION"),
                "metadata.applicantKind",
                errors);
        member(
                metadata.structuringStatus(),
                Set.of("NOT_STARTED", "PARTIAL", "COMPLETE"),
                "metadata.structuringStatus",
                errors);
        require(
                metadata.hasEligibilityRelaxation() != null
                        && metadata.collectedAt() != null
                        && metadata.eligibilityReferenceDate() != null,
                "판본의 완화 여부, 수집 시각, 판단 기준일은 필수입니다.",
                errors);
        url(metadata.sourceUrl(), "metadata.sourceUrl", errors);
    }

    private void fields(RuleSetSection section, RuleSetContent content, List<String> errors) {
        Set<Object> keys = new HashSet<>();
        for (Object row : section.rows(content)) {
            Object key = row;
            if (section.hasIdentity()) {
                key = section.value(row, "id");
            }
            require(keys.add(key), section.property() + ": 중복 식별자가 있습니다.", errors);
            for (RuleSetSection.Field field : section.fields()) {
                scalar(section, row, field, errors);
            }
        }
    }

    private void scalar(
            RuleSetSection section, Object row, RuleSetSection.Field field, List<String> errors) {
        Object value = section.value(row, field.name());
        String path = section.property() + "." + field.name();
        if (value == null) {
            require(field.nullable(), path + ": 필수 값입니다.", errors);
            return;
        }
        if (value instanceof String text) {
            require(!text.isBlank(), path + ": 빈 문자열은 허용되지 않습니다.", errors);
            require(
                    field.maxLength() == 0 || text.length() <= field.maxLength(),
                    path + ": 최대 길이를 초과했습니다.",
                    errors);
            require(
                    field.allowedValues().isEmpty() || field.allowedValues().contains(text),
                    path + ": 허용되지 않는 코드입니다.",
                    errors);
            if (field.name().endsWith("Url")) {
                url(text, path, errors);
            }
        }
        if (value instanceof Long number
                && (field.name().equals("id") || field.name().endsWith("Id"))) {
            require(number > 0, path + ": 식별자는 양수여야 합니다.", errors);
        }
        if (value instanceof BigDecimal number) {
            require(
                    number.precision() - number.scale() <= 14
                            && Math.max(0, number.stripTrailingZeros().scale()) <= 4,
                    path + ": 소수의 자릿수를 초과했습니다.",
                    errors);
        }
    }

    private void references(RuleSetContent content, List<String> errors) {
        Map<String, Set<Object>> identities = new HashMap<>();
        for (RuleSetSection section : RuleSetSection.values()) {
            if (section.hasIdentity()) {
                Set<Object> ids =
                        section.rows(content).stream()
                                .map(row -> section.value(row, "id"))
                                .collect(Collectors.toSet());
                identities.put(section.table(), ids);
                if (section == RuleSetSection.ANNOUNCEMENT_COMPLEXES) {
                    identities.put("announcement_complexes", ids);
                }
            }
        }
        identities.put("eligibility_facts", new HashSet<>(FactResolver.DERIVED));
        for (InputFact fact : InputFact.values()) {
            identities.get("eligibility_facts").add(fact.name());
        }
        identities.put("income_standard_tables", new HashSet<>(incomeStandards));
        Set<Object> gaps = new HashSet<>();
        for (int number = 1; number <= 22; number++) {
            gaps.add("G%02d".formatted(number));
        }
        identities.put("input_gaps", gaps);
        for (RuleSetSection section : RuleSetSection.values()) {
            referenceRows(section, content, identities, errors);
        }
    }

    private void referenceRows(
            RuleSetSection section,
            RuleSetContent content,
            Map<String, Set<Object>> identities,
            List<String> errors) {
        for (Object row : section.rows(content)) {
            for (RuleSetSection.Field field : section.fields()) {
                Object value = section.value(row, field.name());
                Set<Object> targets = identities.get(field.reference());
                if (value != null && targets != null) {
                    require(
                            targets.contains(value),
                            section.property()
                                    + "."
                                    + field.name()
                                    + ": 같은 판본 또는 참조 카탈로그의 행을 가리켜야 합니다.",
                            errors);
                }
            }
        }
    }

    private void evidence(RuleSetContent content, List<String> errors) {
        Map<Long, Document> documents = index(content.documents(), Document::id);
        for (Document document : content.documents()) {
            require(
                    document.sha256().matches("[0-9a-f]{64}"),
                    "documents.sha256 형식이 올바르지 않습니다.",
                    errors);
            require(
                    document.byteSize() >= 0
                            && (document.pageCount() == null || document.pageCount() > 0),
                    "documents: 크기와 쪽수가 올바르지 않습니다.",
                    errors);
        }
        for (Evidence evidence : content.evidences()) {
            Long pages = documents.get(evidence.documentId()).pageCount();
            require(
                    evidence.pdfPageFrom() > 0
                            && evidence.pdfPageTo() >= evidence.pdfPageFrom()
                            && (pages == null || evidence.pdfPageTo() <= pages),
                    "evidences: 원문 쪽 범위가 올바르지 않습니다.",
                    errors);
        }
    }

    private void supply(RuleSetContent content, List<String> errors) {
        Map<Long, HousingType> types = index(content.housingTypes(), HousingType::id);
        for (SupplyUnit unit : content.supplyUnits()) {
            List<SupplyHousingType> links =
                    content.supplyHousingTypes().stream()
                            .filter(link -> link.supplyUnitId().equals(unit.id()))
                            .toList();
            require(!links.isEmpty(), "supplyUnits: 연결된 물리 주택형이 필요합니다.", errors);
            if ("SINGLE_COMPLEX".equals(unit.allocationScope())) {
                require(
                        links.stream()
                                        .map(
                                                link ->
                                                        types.get(link.announcementHousingTypeId())
                                                                .announcementComplexId())
                                        .distinct()
                                        .count()
                                == 1,
                        "supplyUnits: 단일 단지 단위에 여러 단지가 연결되었습니다.",
                        errors);
            }
        }
        Map<Long, Set<Long>> options = new HashMap<>();
        content.quotaOptions()
                .forEach(
                        link ->
                                options.computeIfAbsent(link.quotaPoolId(), key -> new HashSet<>())
                                        .add(link.applicationOptionId()));
        for (QuotaPool pool : content.quotaPools()) {
            if (pool.parentPoolId() != null) {
                require(
                        options.getOrDefault(pool.parentPoolId(), Set.of())
                                .containsAll(options.getOrDefault(pool.id(), Set.of())),
                        "quotaPools: 하위 물량의 선택지는 상위 물량에 포함되어야 합니다.",
                        errors);
            }
            purpose(content, pool.entryConditionGroupId(), "QUOTA_ENTRY", errors);
        }
        Map<String, Set<String>> poolGraph = new HashMap<>();
        content.quotaPools()
                .forEach(pool -> edge(poolGraph, "p" + pool.id(), "p", pool.parentPoolId()));
        acyclic(poolGraph, errors);
    }

    private void conditions(RuleSetContent content, List<String> errors) {
        Map<Long, Condition> conditions = index(content.conditions(), Condition::id);
        Map<Long, ConditionGroup> groups = index(content.groups(), ConditionGroup::id);
        for (ConditionGroup group : content.groups()) {
            if (group.parentGroupId() != null) {
                require(
                        group.purpose().equals(groups.get(group.parentGroupId()).purpose()),
                        "groups: 부모와 자식의 목적이 달라서는 안 됩니다.",
                        errors);
            }
            require(
                    content.conditions().stream()
                                    .anyMatch(condition -> condition.groupId().equals(group.id()))
                            || content.groups().stream()
                                    .anyMatch(child -> group.id().equals(child.parentGroupId())),
                    "groups: 비어 있는 조건 묶음은 공개할 수 없습니다.",
                    errors);
        }
        content.paths()
                .forEach(
                        path ->
                                purpose(
                                        content,
                                        path.eligibilityRootGroupId(),
                                        "ELIGIBILITY",
                                        errors));
        content.options()
                .forEach(
                        option ->
                                purpose(
                                        content,
                                        option.optionConditionGroupId(),
                                        "OPTION_RESTRICTION",
                                        errors));
        for (Condition condition : content.conditions()) {
            operator(condition, errors);
            purpose(content, condition.exemptionGroupId(), "EXEMPTION_TRIGGER", errors);
            if (condition.replacesConditionId() != null) {
                require(
                        "REFERENCE_ONLY"
                                .equals(
                                        conditions
                                                .get(condition.replacesConditionId())
                                                .conditionRole()),
                        "conditions: 대체된 조건은 REFERENCE_ONLY여야 합니다.",
                        errors);
            }
        }
        for (Adjustment adjustment : content.adjustments()) {
            purpose(content, adjustment.triggerGroupId(), "ADJUSTMENT_TRIGGER", errors);
            require(
                    adjustment.valueNumber().signum() >= 0,
                    "adjustments: 음수 가산·대체 값은 허용되지 않습니다.",
                    errors);
            Condition condition = conditions.get(adjustment.conditionId());
            require(
                    condition.valueNumber() != null,
                    "adjustments: 수치 기준이 있는 조건만 조정할 수 있습니다.",
                    errors);
            if ("REPLACE_PERCENT".equals(adjustment.adjustmentKind())
                    || "ADD_PERCENT_POINTS".equals(adjustment.adjustmentKind())) {
                require(
                        "PERCENT_OF_STANDARD".equals(condition.valueUnit()),
                        "adjustments: 퍼센트 조정에는 기준표 비율 조건이 필요합니다.",
                        errors);
            }
            if ("REPLACE_NUMBER".equals(adjustment.adjustmentKind())) {
                require(
                        !"PERCENT_OF_STANDARD".equals(condition.valueUnit()),
                        "adjustments: 기준표 비율의 대체에는 REPLACE_PERCENT를 사용해야 합니다.",
                        errors);
            }
        }
        for (RegionMember member : content.regionMembers()) {
            require(
                    regions.isKnown(member.regionCode()),
                    "regionMembers: 지역 카탈로그에 없는 코드입니다.",
                    errors);
        }
    }

    private void operator(Condition condition, List<String> errors) {
        if (condition.factCode() == null || condition.operator() == null) {
            return;
        }
        InputFact.ValueType type = FactResolver.type(condition.factCode());
        String operator = condition.operator();
        validateOperatorValue(condition, type, operator, errors);
        if (Set.of("INPUT_BROADER", "INPUT_NARROWER").contains(condition.inputMatch())) {
            require(
                    type == InputFact.ValueType.BOOLEAN,
                    "conditions: 입력 범위의 단방향 판정은 불리언에만 적용됩니다.",
                    errors);
        }
        if (condition.householdSizeFactCode() != null) {
            require(
                    FactResolver.type(condition.householdSizeFactCode())
                            == InputFact.ValueType.INTEGER,
                    "conditions: 가구원수 사실은 정수여야 합니다.",
                    errors);
        }
    }

    private void validateOperatorValue(
            Condition condition, InputFact.ValueType type, String operator, List<String> errors) {
        if (Set.of("IS_TRUE", "IS_FALSE").contains(operator)) {
            require(
                    type == InputFact.ValueType.BOOLEAN,
                    "conditions: 불리언 연산자와 사실의 종류가 다릅니다.",
                    errors);
            return;
        }
        if (Set.of("IN_REGION_SET", "NOT_IN_REGION_SET").contains(operator)) {
            require(
                    type == InputFact.ValueType.REGION,
                    "conditions: 지역 연산자와 사실의 종류가 다릅니다.",
                    errors);
            return;
        }
        if (operator.startsWith("DATE_")) {
            require(
                    type == InputFact.ValueType.DATE && condition.valueDate() != null,
                    "conditions: 날짜 사실과 기준일이 필요합니다.",
                    errors);
            if ("DATE_BETWEEN".equals(operator)) {
                require(
                        condition.valueDateTo() != null
                                && condition.valueDate() != null
                                && !condition.valueDateTo().isBefore(condition.valueDate()),
                        "conditions: 날짜 범위가 올바르지 않습니다.",
                        errors);
            }
            return;
        }
        if ("EQ_CODE".equals(operator)) {
            require(
                    type == InputFact.ValueType.CODE && condition.valueCode() != null,
                    "conditions: 코드 사실과 비교값이 필요합니다.",
                    errors);
            return;
        }
        require(
                Set.of(
                                        InputFact.ValueType.INTEGER,
                                        InputFact.ValueType.KRW,
                                        InputFact.ValueType.MONTHS)
                                .contains(type)
                        && condition.valueNumber() != null,
                "conditions: 수치 사실과 비교값이 필요합니다.",
                errors);
        if ("BETWEEN".equals(operator)) {
            require(
                    condition.valueNumberTo() != null
                            && condition.valueNumber() != null
                            && condition.valueNumberTo().compareTo(condition.valueNumber()) >= 0,
                    "conditions: 수치 범위가 올바르지 않습니다.",
                    errors);
        }
    }

    private void selection(RuleSetContent content, List<String> errors) {
        Map<Long, SelectionStep> steps = index(content.selectionSteps(), SelectionStep::id);
        Map<Long, ScoreItem> items = index(content.scoreItems(), ScoreItem::id);
        for (SelectionProcedure procedure : content.selectionProcedures()) {
            require(
                    content.selectionSteps().stream()
                            .anyMatch(step -> step.procedureId().equals(procedure.id())),
                    "selectionProcedures: 선정 단계가 필요합니다.",
                    errors);
        }
        for (RankTier tier : content.rankTiers()) {
            purpose(content, tier.conditionGroupId(), "RANK_TIER", errors);
            require(
                    "RANK".equals(steps.get(tier.stepId()).stepKind()),
                    "rankTiers: 순위 단계가 아닙니다.",
                    errors);
        }
        for (ScoreItem item : content.scoreItems()) {
            require(
                    "SCORE".equals(steps.get(item.stepId()).stepKind()),
                    "scoreItems: 배점 단계가 아닙니다.",
                    errors);
        }
        for (ScoreBand band : content.scoreBands()) {
            purpose(content, band.conditionGroupId(), "SCORE_BAND", errors);
            BigDecimal maximum = items.get(band.scoreItemId()).maxPoints();
            require(
                    maximum == null || band.points().compareTo(maximum) <= 0,
                    "scoreBands: 항목의 최대 배점을 넘었습니다.",
                    errors);
        }
    }

    private void rentsAndSchedules(RuleSetContent content, List<String> errors) {
        content.rentOffers()
                .forEach(offer -> purpose(content, offer.rentClassGroupId(), "RENT_CLASS", errors));
        content.rules()
                .forEach(rule -> purpose(content, rule.conditionGroupId(), "RULE_SCOPE", errors));
        for (ScheduleEvent event : content.schedules()) {
            require(
                    event.startsAt() == null
                            || event.endsAt() == null
                            || !event.startsAt().isAfter(event.endsAt()),
                    "schedules: 시작이 종료보다 늦습니다.",
                    errors);
        }
    }

    private void purpose(RuleSetContent content, Long id, String expected, List<String> errors) {
        if (id == null) {
            return;
        }
        boolean correct =
                content.groups().stream()
                        .anyMatch(
                                group -> group.id().equals(id) && group.purpose().equals(expected));
        require(correct, "groups: 참조한 조건 묶음의 목적은 " + expected + "여야 합니다.", errors);
    }

    private void graph(RuleSetContent content, List<String> errors) {
        Map<String, Set<String>> graph = new HashMap<>();
        for (ConditionGroup group : content.groups()) {
            if (group.parentGroupId() != null) {
                edge(graph, "g" + group.parentGroupId(), "g", group.id());
            }
        }
        for (Condition condition : content.conditions()) {
            String node = "c" + condition.id();
            edge(graph, "g" + condition.groupId(), "c", condition.id());
            edge(graph, node, "g", condition.referencedGroupId());
            edge(graph, node, "g", condition.exemptionGroupId());
        }
        content.adjustments()
                .forEach(
                        adjustment ->
                                edge(
                                        graph,
                                        "c" + adjustment.conditionId(),
                                        "g",
                                        adjustment.triggerGroupId()));
        acyclic(graph, errors);
    }

    private void acyclic(Map<String, Set<String>> graph, List<String> errors) {
        Map<String, Integer> depths = new HashMap<>();
        for (String node : graph.keySet()) {
            if (depth(node, graph, new LinkedHashSet<>(), depths) > 64) {
                errors.add("조건 또는 물량 참조에 순환이 있거나 허용 깊이 64를 초과했습니다.");
                return;
            }
        }
    }

    private int depth(
            String node,
            Map<String, Set<String>> graph,
            Set<String> active,
            Map<String, Integer> depths) {
        if (active.size() >= 64 || active.contains(node)) {
            return 65;
        }
        if (depths.containsKey(node)) {
            return depths.get(node);
        }
        active.add(node);
        int maximum = 0;
        for (String target : graph.getOrDefault(node, Set.of())) {
            maximum = Math.max(maximum, depth(target, graph, active, depths));
            if (maximum >= 64) {
                return 65;
            }
        }
        active.remove(node);
        depths.put(node, maximum + 1);
        return maximum + 1;
    }

    private void edge(Map<String, Set<String>> graph, String from, String prefix, Long to) {
        if (to != null) {
            graph.computeIfAbsent(from, key -> new LinkedHashSet<>()).add(prefix + to);
        }
    }

    private void url(String value, String path, List<String> errors) {
        if (value == null) {
            return;
        }
        try {
            URI uri = URI.create(value);
            require(
                    value.length() <= 2000
                            && uri.getHost() != null
                            && Set.of("http", "https").contains(uri.getScheme())
                            && uri.getUserInfo() == null,
                    path + ": HTTP(S) 출처 URL이 필요합니다.",
                    errors);
        } catch (IllegalArgumentException exception) {
            errors.add(path + ": URL 형식이 올바르지 않습니다.");
        }
    }

    private void member(String value, Set<String> allowed, String path, List<String> errors) {
        require(value != null && allowed.contains(value), path + ": 허용되지 않는 코드입니다.", errors);
    }

    private void require(boolean valid, String message, List<String> errors) {
        if (!valid && errors.size() < 100) {
            errors.add(message);
        }
    }

    private void fail(List<String> errors) {
        if (!errors.isEmpty()) {
            throw new InvalidEligibilityException(errors);
        }
    }

    private static <T> Map<Long, T> index(List<T> rows, Function<T, Long> id) {
        return rows.stream().collect(Collectors.toMap(id, Function.identity()));
    }
}

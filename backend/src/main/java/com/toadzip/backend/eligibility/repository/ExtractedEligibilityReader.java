package com.toadzip.backend.eligibility.repository;

import com.toadzip.backend.eligibility.domain.ExtractedEligibility;
import com.toadzip.backend.eligibility.domain.ExtractedEligibility.Exclusion;
import com.toadzip.backend.eligibility.domain.ExtractedEligibility.Group;
import com.toadzip.backend.eligibility.domain.ExtractedEligibility.Qualification;
import com.toadzip.backend.eligibility.domain.ExtractedEligibility.Quote;
import com.toadzip.backend.eligibility.domain.ExtractedEligibility.Requirement;
import com.toadzip.backend.eligibility.domain.ExtractedEligibility.Route;
import com.toadzip.backend.eligibility.exception.EligibilityDraftUnavailableException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 저장된 PDF 추출 결과 JSON(ai-worker/contract의 result json)에서 신청자격(data.eligibility)과 그 근거만 읽는다.
 * 형식이 틀린 칸은 비운다. 비운 칸은 변환에서 직접 확인으로 남는다.
 */
@Component
public class ExtractedEligibilityReader {
    private final ObjectMapper json;

    public ExtractedEligibilityReader(ObjectMapper json) {
        this.json = json;
    }

    public ExtractedEligibility read(String extraction) {
        JsonNode tree;
        try {
            tree = json.readTree(extraction);
        } catch (JacksonException exception) {
            throw new EligibilityDraftUnavailableException("PDF 추출 결과를 읽지 못했습니다. 추출을 다시 해 주세요.");
        }
        JsonNode eligibility = tree.path("data").path("eligibility");
        if (!eligibility.isObject()) {
            throw new EligibilityDraftUnavailableException(
                    "이 추출 결과에는 신청자격이 없습니다. 신청자격 추출을 지원하는 워커로 PDF를 다시 추출해 주세요.");
        }
        List<Route> routes = new ArrayList<>();
        JsonNode targets = eligibility.path("targets");
        for (int index = 0; index < targets.size(); index++) {
            JsonNode target = targets.get(index);
            routes.add(new Route(text(target, "target"), texts(target.path("supplyTargets")),
                    group(target, "eligibility.targets[" + index + "]")));
        }
        List<Exclusion> exclusions = new ArrayList<>();
        JsonNode items = eligibility.path("exclusions");
        for (int index = 0; index < items.size(); index++) {
            exclusions.add(new Exclusion(text(items.get(index), "title"), text(items.get(index), "body"),
                    "eligibility.exclusions[" + index + "]"));
        }
        return new ExtractedEligibility(group(eligibility.path("common"), "eligibility.common"), routes, exclusions,
                quotes(tree.path("evidence")));
    }

    private static Group group(JsonNode node, String path) {
        List<Requirement> requirements = new ArrayList<>();
        JsonNode items = node.path("requirements");
        for (int index = 0; index < items.size(); index++) {
            requirements.add(requirement(items.get(index), path + ".requirements[" + index + "]"));
        }
        List<Qualification> qualifications = new ArrayList<>();
        JsonNode values = node.path("qualifications");
        for (int index = 0; index < values.size(); index++) {
            JsonNode value = values.get(index);
            Optional.ofNullable(text(value, "exactName")).ifPresent(name -> qualifications.add(new Qualification(
                    name, text(value, "legalBasis"), text(value, "conditionsAsWritten"), null)));
        }
        return new Group(path, requirements, numbered(qualifications, path));
    }

    /** 워커가 이름 없는 자격을 이미 빼고 근거 순번도 옮겼으므로, 남은 순서대로 근거 경로를 붙인다. */
    private static List<Qualification> numbered(List<Qualification> qualifications, String path) {
        List<Qualification> result = new ArrayList<>();
        for (int index = 0; index < qualifications.size(); index++) {
            Qualification item = qualifications.get(index);
            result.add(new Qualification(item.exactName(), item.legalBasis(), item.conditionsAsWritten(),
                    path + ".qualifications[" + index + "]"));
        }
        return result;
    }

    private static Requirement requirement(JsonNode node, String path) {
        Map<Long, Integer> extra = new LinkedHashMap<>();
        for (JsonNode item : node.path("householdSizeExtraPercent")) {
            if (item.path("householdSize").isIntegralNumber() && item.path("percent").isIntegralNumber()
                    && item.path("householdSize").asLong() >= 1) {
                extra.put(item.path("householdSize").asLong(), item.path("percent").asInt());
            }
        }
        List<String> adjustments = new ArrayList<>();
        for (JsonNode item : node.path("adjustments")) {
            Optional.ofNullable(text(item, "kind")).ifPresent(adjustments::add);
        }
        List<Requirement> options = new ArrayList<>();
        JsonNode values = node.path("options");
        for (int index = 0; index < values.size(); index++) {
            options.add(requirement(values.get(index), path + ".options[" + index + "]"));
        }
        return new Requirement(Optional.ofNullable(text(node, "kind")).orElse("OTHER"), text(node, "scope"),
                integer(node, "minimum"), integer(node, "maximum"), integer(node, "minimumMonths"),
                text(node, "incomeStandard"), integer(node, "incomeBaseYear"), integer(node, "percent"), extra, adjustments,
                node.path("maximumKrw").isIntegralNumber() ? node.path("maximumKrw").asLong() : null,
                texts(node.path("regions")), texts(node.path("familyTypes")), options, text(node, "asWritten"), path);
    }

    private static Map<String, Quote> quotes(JsonNode evidence) {
        Map<String, Quote> quotes = new LinkedHashMap<>();
        for (JsonNode item : evidence) {
            String path = text(item, "path");
            // 인용이 빈 근거는 근거로 쓰지 않는다(검증기가 근거 설명을 요구한다).
            if (path != null && path.startsWith("eligibility.") && item.path("page").isIntegralNumber()
                    && item.path("page").asInt() > 0 && text(item, "quote") != null) {
                quotes.putIfAbsent(path, new Quote(item.path("page").asInt(),
                        Optional.ofNullable(text(item, "quote")).orElse("")));
            }
        }
        return quotes;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() && !value.asString().isBlank() ? value.asString().strip() : null;
    }

    private static Integer integer(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isIntegralNumber() && value.canConvertToInt() ? value.asInt() : null;
    }

    private static List<String> texts(JsonNode node) {
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.isString() && !item.asString().isBlank()) {
                values.add(item.asString().strip());
            }
        }
        return values;
    }
}

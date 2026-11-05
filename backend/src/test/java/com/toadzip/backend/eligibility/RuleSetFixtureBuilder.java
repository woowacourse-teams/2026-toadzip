package com.toadzip.backend.eligibility;

import com.toadzip.backend.eligibility.domain.RuleSetContent;
import com.toadzip.backend.eligibility.domain.RuleSetSection;

import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compact test-data notation. All expected evaluation outcomes are asserted independently by tests.
 */
public final class RuleSetFixtureBuilder {
    private final Map<String, Object> content = new LinkedHashMap<>();
    private final Map<String, List<Map<String, Object>>> sections = new LinkedHashMap<>();
    private long nextId = 1;
    private long evidenceId;

    public RuleSetFixtureBuilder(String name, int pageCount, String hash, long bytes) {
        content.put(
                "metadata",
                values(
                        "recruitmentKind",
                        "WAITLIST",
                        "applicantKind",
                        "INDIVIDUAL",
                        "hasEligibilityRelaxation",
                        false,
                        "collectedAt",
                        "2026-10-29T00:00:00Z",
                        "eligibilityReferenceDate",
                        "2026-10-16",
                        "structuringStatus",
                        "PARTIAL"));
        for (RuleSetSection section : RuleSetSection.values()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            sections.put(section.property(), rows);
            content.put(section.property(), rows);
        }
        long document =
                add(
                        "documents",
                        "fileName",
                        name,
                        "mediaType",
                        "application/pdf",
                        "byteSize",
                        bytes,
                        "sha256",
                        hash,
                        "pageCount",
                        pageCount,
                        "evidenceLevel",
                        "OFFICIAL_ATTACHMENT_UNVERIFIED",
                        "fetchedAt",
                        "2026-10-29T00:00:00Z");
        evidenceId =
                add(
                        "evidences",
                        "documentId",
                        document,
                        "pdfPageFrom",
                        1,
                        "pdfPageTo",
                        1,
                        "excerpt",
                        "설계의 E/F 사례에서 옮긴 검증용 부분 구조화. 원문 전체를 대표하지 않음.",
                        "verificationMethod",
                        "TEXT");
    }

    public long evidence(int page, String excerpt) {
        long document = (Long) sections.get("documents").getFirst().get("id");
        return add(
                "evidences",
                "documentId",
                document,
                "pdfPageFrom",
                page,
                "pdfPageTo",
                page,
                "excerpt",
                excerpt,
                "verificationMethod",
                "TEXT");
    }

    public long add(String section, Object... fields) {
        Map<String, Object> row = values(fields);
        row.putIfAbsent("id", nextId++);
        sections.get(section).add(row);
        return ((Number) row.get("id")).longValue();
    }

    public long group(String purpose, String logic, String label) {
        return add(
                "groups",
                "purpose",
                purpose,
                "logic",
                logic,
                "label",
                label,
                "orderNo",
                1,
                "evidenceId",
                evidenceId);
    }

    public long condition(long group, String fact, String operator, Object value, Object... extra) {
        Map<String, Object> row =
                values(
                        "groupId",
                        group,
                        "orderNo",
                        nextId,
                        "conditionRole",
                        "REQUIREMENT",
                        "subjectCode",
                        "APPLICANT",
                        "factCode",
                        fact,
                        "operator",
                        operator,
                        "valueNumber",
                        value,
                        "referenceDateRule",
                        "BASE_DATE",
                        "holdingPeriod",
                        "AT_REFERENCE_DATE",
                        "interpretationStatus",
                        "INTERPRETED",
                        "automationMode",
                        "AUTOMATIC",
                        "inputMatch",
                        "EXACT",
                        "statementAsWritten",
                        "검증 조건",
                        "evidenceId",
                        evidenceId);
        row.putAll(values(extra));
        row.put("id", nextId++);
        sections.get("conditions").add(row);
        return (Long) row.get("id");
    }

    public void reference(long group, long target) {
        condition(group, null, null, null, "subjectCode", "NONE", "referencedGroupId", target);
    }

    public long numericGroup(String purpose, String fact, String operator, long value) {
        long group = group(purpose, "ALL", fact + " " + operator + " " + value);
        condition(group, fact, operator, value);
        return group;
    }

    public long region(String label, String... codes) {
        long id = add("regionSets", "label", label, "evidenceId", evidenceId);
        for (String code : codes) {
            sections.get("regionMembers").add(values("regionSetId", id, "regionCode", code));
        }
        return id;
    }

    public long unit(long complex, String label, String rentalType, String area) {
        long housingType =
                add(
                        "housingTypes",
                        "announcementComplexId",
                        complex,
                        "typeLabelAsWritten",
                        label,
                        "exclusiveAreaM2",
                        area,
                        "isAccessibleUnit",
                        false,
                        "evidenceId",
                        evidenceId);
        long unit =
                add(
                        "supplyUnits",
                        "unitLabelAsWritten",
                        label,
                        "rentalType",
                        rentalType,
                        "unitKind",
                        "GENERAL",
                        "allocationScope",
                        "SINGLE_COMPLEX",
                        "evidenceId",
                        evidenceId);
        sections.get("supplyHousingTypes")
                .add(values("supplyUnitId", unit, "announcementHousingTypeId", housingType));
        return unit;
    }

    public long path(String label, long root, Long procedure) {
        return add(
                "paths",
                "pathLabelAsWritten",
                label,
                "supplyCategory",
                "GENERAL",
                "recruitmentStatus",
                "RECRUITING",
                "eligibilityRootGroupId",
                root,
                "selectionProcedureId",
                procedure,
                "evidenceId",
                evidenceId);
    }

    public long option(long unit, long path) {
        return add(
                "options",
                "supplyUnitId",
                unit,
                "eligibilityPathId",
                path,
                "evidenceId",
                evidenceId);
    }

    public void adjustment(
            long condition, long trigger, String kind, long amount, String stacking) {
        add(
                "adjustments",
                "conditionId",
                condition,
                "triggerGroupId",
                trigger,
                "adjustmentKind",
                kind,
                "valueNumber",
                amount,
                "stacking",
                stacking,
                "orderNo",
                nextId,
                "evidenceId",
                evidenceId);
    }

    public long rent(long unit, String label, Long group, long deposit, long monthly) {
        long rent =
                add(
                        "rentOffers",
                        "rentKind",
                        "MONTHLY",
                        "rentClassLabelAsWritten",
                        label,
                        "rentClassGroupId",
                        group,
                        "amountPrecision",
                        "EXACT",
                        "depositKrw",
                        deposit,
                        "monthlyRentKrw",
                        monthly,
                        "asOfNote",
                        "계약 시점에 변경된 임대조건 적용",
                        "evidenceId",
                        evidenceId);
        sections.get("rentSupplyUnits").add(values("rentOfferId", rent, "supplyUnitId", unit));
        return rent;
    }

    public long step(long procedure, long number, String kind, Object... extra) {
        Map<String, Object> row =
                values(
                        "procedureId",
                        procedure,
                        "stepNo",
                        number,
                        "stepKind",
                        kind,
                        "usage",
                        "SELECTION",
                        "statementAsWritten",
                        kind,
                        "evidenceId",
                        evidenceId);
        row.putAll(values(extra));
        return add("selectionSteps", flatten(row));
    }

    public void tier(long step, int number, long group) {
        add(
                "rankTiers",
                "stepId",
                step,
                "tierNo",
                number,
                "labelAsWritten",
                number + "순위",
                "conditionGroupId",
                group,
                "evidenceId",
                evidenceId);
    }

    public void score(long step, String label, int maximum, long[] groups, int[] points) {
        long item =
                add(
                        "scoreItems",
                        "stepId",
                        step,
                        "itemNo",
                        nextId,
                        "labelAsWritten",
                        label,
                        "aggregation",
                        "BEST_BAND",
                        "maxPoints",
                        maximum,
                        "evidenceId",
                        evidenceId);
        for (int index = 0; index < groups.length; index++) {
            add(
                    "scoreBands",
                    "scoreItemId",
                    item,
                    "points",
                    points[index],
                    "conditionGroupId",
                    groups[index],
                    "orderNo",
                    index + 1);
        }
    }

    public long evidenceId() {
        return evidenceId;
    }

    public RuleSetContent build() {
        return JsonMapper.builder().build().convertValue(content, RuleSetContent.class);
    }

    public static Map<String, Object> values(Object... fields) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < fields.length; index += 2) {
            result.put((String) fields[index], fields[index + 1]);
        }
        return result;
    }

    private static Object[] flatten(Map<String, Object> fields) {
        List<Object> result = new ArrayList<>();
        fields.forEach(
                (key, value) -> {
                    result.add(key);
                    result.add(value);
                });
        return result.toArray();
    }
}

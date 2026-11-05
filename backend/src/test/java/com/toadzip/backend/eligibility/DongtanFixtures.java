package com.toadzip.backend.eligibility;

import com.toadzip.backend.eligibility.domain.RuleSetContent;

/**
 * D01/E1/F: Dongtan subset, reference date 2026-10-16. File identity is deliberately synthetic for
 * tests.
 */
public final class DongtanFixtures {
    private DongtanFixtures() {}

    public static RuleSetContent content() {
        var builder =
                new RuleSetFixtureBuilder("D01-case-fixture.pdf", 108, "ab".repeat(32), 1759994);
        long evidence = builder.evidenceId();
        long common = builder.group("SHARED_DEFINITION", "ALL", "공통");
        builder.condition(
                common,
                "HOUSEHOLD_HAS_HOUSE_OWNER",
                "IS_FALSE",
                null,
                "subjectCode",
                "HOUSEHOLD_MEMBERS",
                "holdingPeriod",
                "UNTIL_MOVE_IN");
        long exclusion = builder.evidence(15, "불법전대 적발 후 4년이 지나지 않은 세대 신청 제한");
        long commonExclusion = builder.group("SHARED_DEFINITION", "ALL", "불법전대 배제");
        builder.condition(
                commonExclusion,
                null,
                null,
                null,
                "inputGapCode",
                "G08",
                "conditionRole",
                "EXCLUSION",
                "subjectCode",
                "HOUSEHOLD_MEMBERS",
                "evidenceId",
                exclusion);
        builder.reference(common, commonExclusion);

        long newborn =
                builder.numericGroup(
                        "ADJUSTMENT_TRIGGER", "CHILDREN_BORN_SINCE_2023_03_28", "EQ", 1);
        // A child born since the cutoff plus an earlier child receives the two-child increase as
        // well.
        builder.condition(newborn, "CHILDREN_COUNT", "EQ", 1);
        long children =
                builder.numericGroup(
                        "ADJUSTMENT_TRIGGER", "CHILDREN_BORN_SINCE_2023_03_28", "GTE", 1);
        builder.condition(children, "CHILDREN_COUNT", "GTE", 2);
        long onePerson = builder.numericGroup("ADJUSTMENT_TRIGGER", "FAMILY_MEMBER_COUNT", "EQ", 1);
        long twoPeople = builder.numericGroup("ADJUSTMENT_TRIGGER", "FAMILY_MEMBER_COUNT", "EQ", 2);
        long dual = builder.group("ADJUSTMENT_TRIGGER", "ALL", "맞벌이 3인 이상");
        builder.condition(dual, "IS_DUAL_INCOME", "IS_TRUE", null);
        builder.condition(dual, "FAMILY_MEMBER_COUNT", "GTE", 3);
        long twoDual = builder.group("ADJUSTMENT_TRIGGER", "ALL", "2인 맞벌이");
        builder.condition(twoDual, "FAMILY_MEMBER_COUNT", "EQ", 2);
        builder.condition(twoDual, "IS_DUAL_INCOME", "IS_TRUE", null);
        long twoSingleIncome = builder.group("ADJUSTMENT_TRIGGER", "ALL", "2인 외벌이");
        builder.condition(twoSingleIncome, "FAMILY_MEMBER_COUNT", "EQ", 2);
        builder.condition(twoSingleIncome, "IS_DUAL_INCOME", "IS_FALSE", null);

        long shared = builder.group("SHARED_DEFINITION", "ALL", "신혼부부 소득 자산 통장");
        long income =
                builder.condition(
                        shared,
                        "HOUSEHOLD_MONTHLY_INCOME",
                        "LTE",
                        100,
                        "subjectCode",
                        "HOUSEHOLD",
                        "valueUnit",
                        "PERCENT_OF_STANDARD",
                        "incomeStandardTableId",
                        1,
                        "householdSizeFactCode",
                        "FAMILY_MEMBER_COUNT",
                        "evidenceId",
                        builder.evidence(19, "세대 소득 기준"),
                        "semanticCaveat",
                        "해당 세대는 분리배우자 포함(p20)");
        builder.adjustment(income, dual, "REPLACE_PERCENT", 120, "MAX");
        builder.adjustment(income, twoSingleIncome, "REPLACE_PERCENT", 110, "MAX");
        builder.adjustment(income, twoDual, "REPLACE_PERCENT", 130, "MAX");
        builder.adjustment(income, newborn, "ADD_PERCENT_POINTS", 10, "MAX");
        builder.adjustment(income, children, "ADD_PERCENT_POINTS", 20, "MAX");
        long assets =
                builder.condition(
                        shared,
                        "HOUSEHOLD_ASSETS",
                        "LTE",
                        345000000,
                        "subjectCode",
                        "HOUSEHOLD",
                        "valueUnit",
                        "KRW",
                        "evidenceId",
                        builder.evidence(20, "총자산 34,500만원, 자녀 가산 37,900만원 또는 41,300만원"));
        long car =
                builder.condition(
                        shared,
                        "HOUSEHOLD_CAR_VALUE",
                        "LTE",
                        45420000,
                        "subjectCode",
                        "HOUSEHOLD",
                        "valueUnit",
                        "KRW");
        builder.adjustment(assets, newborn, "REPLACE_NUMBER", 379000000, "MAX");
        builder.adjustment(assets, children, "REPLACE_NUMBER", 413000000, "MAX");
        builder.adjustment(car, newborn, "REPLACE_NUMBER", 49960000, "MAX");
        builder.adjustment(car, children, "REPLACE_NUMBER", 54510000, "MAX");
        builder.condition(
                shared,
                "HAS_SAVINGS_ACCOUNT",
                "IS_TRUE",
                null,
                "conditionRole",
                "OBLIGATION",
                "subjectCode",
                "APPLICANT_OR_SPOUSE",
                "holdingPeriod",
                "BY_MOVE_IN",
                "statementAsWritten",
                "본인 또는 배우자 1인의 입주 전 청약저축 가입");

        long term = builder.group("ELIGIBILITY", "ANY", "혼인 7년 이내 또는 6세 이하 자녀");
        builder.condition(term, "MARRIAGE_MONTHS_AT_REFERENCE", "LTE", 84, "valueUnit", "MONTHS");
        builder.condition(term, "YOUNGEST_CHILD_AGE_AT_REFERENCE", "LTE", 6, "valueUnit", "YEARS");
        long newlywed = builder.group("ELIGIBILITY", "ALL", "신혼부부");
        builder.condition(newlywed, "IS_MARRIED_AT_REFERENCE", "IS_TRUE", null);
        builder.reference(newlywed, term);
        builder.reference(newlywed, shared);
        builder.reference(newlywed, common);
        long engaged = builder.group("ELIGIBILITY", "ALL", "예비신혼부부");
        builder.condition(engaged, "IS_ENGAGED_AT_REFERENCE", "IS_TRUE", null);
        builder.reference(engaged, shared);
        builder.reference(engaged, common);
        builder.condition(
                engaged,
                null,
                null,
                null,
                "inputGapCode",
                "G05",
                "statementAsWritten",
                "혼인으로 구성될 세대의 세대원");
        long singleParent = builder.group("ELIGIBILITY", "ALL", "한부모가족");
        builder.condition(
                singleParent, "FAMILY_TYPE", "EQ_CODE", null, "valueCode", "SINGLE_PARENT");
        builder.condition(singleParent, "YOUNGEST_CHILD_AGE_AT_REFERENCE", "LTE", 6);
        builder.reference(singleParent, shared);
        builder.reference(singleParent, common);

        long youth = builder.group("ELIGIBILITY", "ALL", "청년");
        long youthAge = builder.group("ELIGIBILITY", "ANY", "청년 나이 또는 사회초년생");
        builder.condition(youthAge, "AGE_AT_REFERENCE", "BETWEEN", 19, "valueNumberTo", 39);
        builder.condition(youthAge, null, null, null, "inputGapCode", "G13");
        builder.reference(youth, youthAge);
        builder.condition(youth, "IS_MARRIED_AT_REFERENCE", "IS_FALSE", null);
        builder.condition(
                youth,
                "APPLICANT_OWNS_HOUSE",
                "IS_FALSE",
                null,
                "holdingPeriod",
                "UNTIL_CONTRACT_END");
        builder.reference(youth, commonExclusion);
        long youthIncome =
                builder.condition(
                        youth,
                        "HOUSEHOLD_MONTHLY_INCOME",
                        "LTE",
                        100,
                        "subjectCode",
                        "HOUSEHOLD_IF_HEAD_ELSE_APPLICANT",
                        "valueUnit",
                        "PERCENT_OF_STANDARD",
                        "incomeStandardTableId",
                        1,
                        "householdSizeFactCode",
                        "FAMILY_MEMBER_COUNT");
        builder.adjustment(youthIncome, onePerson, "ADD_PERCENT_POINTS", 20, "SUM");
        builder.adjustment(youthIncome, twoPeople, "ADD_PERCENT_POINTS", 10, "SUM");
        builder.condition(
                youth,
                "HOUSEHOLD_ASSETS",
                "LTE",
                251000000,
                "subjectCode",
                "HOUSEHOLD_IF_HEAD_ELSE_APPLICANT",
                "valueUnit",
                "KRW");
        builder.condition(
                youth,
                "HOUSEHOLD_CAR_VALUE",
                "LTE",
                45420000,
                "subjectCode",
                "HOUSEHOLD_IF_HEAD_ELSE_APPLICANT",
                "valueUnit",
                "KRW");
        builder.condition(
                youth,
                "HAS_SAVINGS_ACCOUNT",
                "IS_TRUE",
                null,
                "conditionRole",
                "OBLIGATION",
                "holdingPeriod",
                "BY_MOVE_IN");

        long nearby =
                builder.region("화성시 및 연접", "41590", "41110", "41370", "41460", "41270", "41220");
        long capital = builder.region("수도권", "11", "28", "41");
        long youthProcedure = selection(builder, nearby, capital, false);
        long marriedProcedure = selection(builder, nearby, capital, true);
        long complex =
                builder.add(
                        "complexes",
                        "complexNameAsWritten",
                        "동탄호수공원",
                        "supplyMethod",
                        "CONSTRUCTED",
                        "totalUnitsAsWritten",
                        995,
                        "evidenceId",
                        evidence);
        long unit36 = builder.unit(complex, "36AB", "HAPPY_HOUSING", "36.1396");
        long unitYouth = builder.unit(complex, "36AB1", "HAPPY_HOUSING", "36.2643");
        long unit26 = builder.unit(complex, "26B", "HAPPY_HOUSING", "26.4422");
        builder.option(unit36, builder.path("신혼부부", newlywed, marriedProcedure));
        builder.option(unit36, builder.path("예비신혼부부", engaged, marriedProcedure));
        builder.option(unit36, builder.path("한부모가족", singleParent, marriedProcedure));
        long youthPath = builder.path("청년", youth, youthProcedure);
        builder.option(unitYouth, youthPath);
        builder.option(unit26, youthPath);
        long rent = builder.rent(unit36, "신혼부부·한부모", null, 52211000, 216960);
        builder.add(
                "rentConversions",
                "rentOfferId",
                rent,
                "direction",
                "DEPOSIT_UP",
                "depositKrw",
                70211000,
                "monthlyRentKrw",
                111960,
                "ratePercent",
                7,
                "isMaximum",
                true,
                "evidenceId",
                evidence);
        for (String label : java.util.List.of("청년 소득 없음", "청년 소득 있음")) {
            long group = builder.group("RENT_CLASS", "ALL", label);
            builder.condition(group, null, null, null, "inputGapCode", "G15");
            long deposit = 44379000;
            long monthly = 184420;
            if (label.equals("청년 소득 있음")) {
                deposit = 46990000;
                monthly = 195270;
            }
            builder.rent(unitYouth, label, group, deposit, monthly);
        }
        builder.add(
                "schedules",
                "eventKind",
                "APPLICATION",
                "channel",
                "ONLINE",
                "timePrecision",
                "DATE",
                "startsAt",
                "2026-10-26T00:00:00+09:00",
                "endsAt",
                "2026-10-28T00:00:00+09:00",
                "labelAsWritten",
                "인터넷 청약",
                "evidenceId",
                builder.evidence(3, "인터넷 청약 10.26~10.28"));
        builder.add(
                "rules",
                "ruleKind",
                "WAITLIST_DUPLICATE_SELECTION",
                "definitionStatus",
                "DEFINED",
                "statementAsWritten",
                "예비입주자 중복선정 불가, 기존 대기자의 후순번",
                "evidenceId",
                exclusion);
        return builder.build();
    }

    private static long selection(
            RuleSetFixtureBuilder builder, long nearby, long capital, boolean spouse) {
        long first = builder.group("RANK_TIER", "ANY", "거주지 또는 소득근거지");
        builder.condition(first, "RESIDENCE_REGION", "IN_REGION_SET", null, "regionSetId", nearby);
        builder.condition(
                first, "INCOME_BASE_REGION", "IN_REGION_SET", null, "regionSetId", nearby);
        if (spouse) {
            builder.condition(
                    first, null, null, null, "inputGapCode", "G01", "subjectCode", "SPOUSE");
        }
        long second = builder.group("RANK_TIER", "ALL", "수도권");
        builder.condition(
                second, "RESIDENCE_REGION", "IN_REGION_SET", null, "regionSetId", capital);
        long third = builder.numericGroup("RANK_TIER", "AGE_AT_REFERENCE", "GTE", 19);
        long procedure =
                builder.add(
                        "selectionProcedures",
                        "labelAsWritten",
                        "순위 후 추첨",
                        "evidenceId",
                        builder.evidenceId());
        long rank = builder.step(procedure, 1, "RANK");
        builder.tier(rank, 1, first);
        builder.tier(rank, 2, second);
        builder.tier(rank, 3, third);
        builder.step(procedure, 2, "LOTTERY");
        return procedure;
    }
}

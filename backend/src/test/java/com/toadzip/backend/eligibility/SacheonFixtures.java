package com.toadzip.backend.eligibility;

import com.toadzip.backend.eligibility.domain.RuleSetContent;

import java.util.ArrayList;
import java.util.List;

/**
 * D05/E3/F: 12 paths x 2 units. Synthetic file identity; source facts are from the supplied
 * design's page notes.
 */
public final class SacheonFixtures {
    private SacheonFixtures() {}

    public static RuleSetContent content() {
        var builder = new RuleSetFixtureBuilder("D05-case-fixture.pdf", 35, "cd".repeat(32), 100);
        long evidence = builder.evidenceId();
        long region = builder.region("사천시", "48240");
        long common = builder.group("SHARED_DEFINITION", "ALL", "공통 자격");
        builder.condition(common, "AGE_AT_REFERENCE", "GTE", 19);
        builder.condition(common, "RESIDENCE_REGION", "IN_REGION_SET", null, "regionSetId", region);
        builder.condition(
                common,
                "HOUSEHOLD_HAS_HOUSE_OWNER",
                "IS_FALSE",
                null,
                "subjectCode",
                "HOUSEHOLD_MEMBERS",
                "holdingPeriod",
                "UNTIL_MOVE_IN");
        builder.condition(
                common,
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
                builder.evidence(7, "불법양도·전대 후 4년 미경과 배제"));
        long single = builder.numericGroup("ADJUSTMENT_TRIGGER", "FAMILY_MEMBER_COUNT", "EQ", 1);
        long couple = builder.numericGroup("ADJUSTMENT_TRIGGER", "FAMILY_MEMBER_COUNT", "EQ", 2);
        long child =
                builder.numericGroup(
                        "ADJUSTMENT_TRIGGER", "CHILDREN_BORN_SINCE_2023_03_28", "GTE", 1);
        long children =
                builder.numericGroup(
                        "ADJUSTMENT_TRIGGER", "CHILDREN_BORN_SINCE_2023_03_28", "GTE", 1);
        builder.condition(children, "CHILDREN_COUNT", "GTE", 2);
        long income50 = income(builder, 50, single, couple, child, children);
        long income70 = income(builder, 70, single, couple, child, children);
        long income100 = income(builder, 100, single, couple, child, children);
        String[] names = {"가", "나", "다", "라", "마", "바", "사", "아", "자", "차", "카", "타"};
        List<Long> identities = new ArrayList<>();
        for (String name : names) {
            identities.add(builder.group("SHARED_DEFINITION", "ALL", name + "목 신분"));
        }
        builder.condition(
                identities.get(0),
                "BASIC_LIVELIHOOD_RECIPIENT",
                "IS_TRUE",
                null,
                "inputMatch",
                "INPUT_BROADER");
        builder.condition(
                identities.get(1), "VETERAN_TARGET", "IS_TRUE", null, "inputMatch", "UNSETTLED");
        builder.condition(identities.get(2), null, null, null, "inputGapCode", "G17");
        builder.condition(identities.get(3), "SUPPORTED_SINGLE_PARENT_FAMILY", "IS_TRUE", null);
        builder.condition(identities.get(4), null, null, null, "inputGapCode", "G17");
        builder.condition(identities.get(5), null, null, null, "inputGapCode", "G10");
        builder.condition(identities.get(6), null, null, null, "inputGapCode", "G16");
        builder.condition(identities.get(6), null, null, null, "inputGapCode", "G22");
        builder.condition(identities.get(7), null, null, null, "inputGapCode", "G17");
        builder.condition(identities.get(7), null, null, null, "automationMode", "AGENCY_REVIEW");
        long benefits = builder.group("SHARED_DEFINITION", "ANY", "수급권자 또는 차상위");
        builder.condition(
                benefits,
                "BASIC_LIVELIHOOD_RECIPIENT",
                "IS_TRUE",
                null,
                "inputMatch",
                "INPUT_NARROWER");
        builder.condition(benefits, "NEAR_POVERTY", "IS_TRUE", null);
        builder.condition(identities.get(8), "AGE_AT_REFERENCE", "GTE", 65);
        builder.reference(identities.get(8), benefits);
        builder.condition(identities.get(9), "AGE_AT_REFERENCE", "GTE", 19);
        builder.condition(identities.get(10), null, null, null, "automationMode", "AGENCY_REVIEW");
        builder.condition(identities.get(11), null, null, null, "inputGapCode", "G10");
        long procedure =
                builder.add(
                        "selectionProcedures",
                        "labelAsWritten",
                        "순위, 배점, 전입일, 세대원수, 연령, 추첨",
                        "evidenceId",
                        builder.evidence(9, "100점 배점 및 동점 처리"));
        long rank = builder.step(procedure, 1, "RANK");
        long first = builder.group("RANK_TIER", "ANY", "가~카목");
        identities.subList(0, 11).forEach(identity -> builder.reference(first, identity));
        long second = builder.group("RANK_TIER", "ALL", "타목");
        builder.reference(second, identities.getLast());
        builder.tier(rank, 1, first);
        builder.tier(rank, 2, second);
        long score = builder.step(procedure, 2, "SCORE");
        scores(builder, score);
        builder.step(
                procedure, 3, "SORT_BY_FACT", "sortInputGapCode", "G02", "sortDirection", "ASC");
        builder.step(
                procedure,
                4,
                "SORT_BY_FACT",
                "sortFactCode",
                "FAMILY_MEMBER_COUNT",
                "sortDirection",
                "DESC");
        builder.step(
                procedure, 5, "SORT_BY_FACT", "sortFactCode", "BIRTH_DATE", "sortDirection", "ASC");
        builder.step(procedure, 6, "LOTTERY");
        long complex =
                builder.add(
                        "complexes",
                        "complexNameAsWritten",
                        "사천벌리1",
                        "supplyMethod",
                        "CONSTRUCTED",
                        "firstMoveInMonth",
                        "1995-07-01",
                        "evidenceId",
                        evidence);
        long unit26 = builder.unit(complex, "26형", "PERMANENT_RENTAL", "26.37");
        long unit31 = builder.unit(complex, "31형", "PERMANENT_RENTAL", "31.32");
        for (int index = 0; index < names.length; index++) {
            long root = builder.group("ELIGIBILITY", "ALL", names[index] + "목 자격");
            builder.reference(root, common);
            builder.reference(root, identities.get(index));
            appendIncome(builder, index, root, income50, income70, income100);
            long path = builder.path(names[index], root, procedure);
            builder.option(unit26, path);
            builder.option(unit31, path);
        }
        long rentA = builder.group("RENT_CLASS", "ANY", "가군");
        builder.reference(rentA, identities.get(0));
        builder.reference(rentA, identities.get(3));
        builder.condition(rentA, null, null, null, "inputGapCode", "G22");
        builder.condition(rentA, null, null, null, "inputGapCode", "G17");
        long rentB = builder.group("RENT_CLASS", "ALL", "가군 이외");
        builder.condition(
                rentB,
                null,
                null,
                null,
                "referencedGroupId",
                rentA,
                "conditionRole",
                "EXCLUSION",
                "subjectCode",
                "NONE");
        builder.rent(unit26, "가군", rentA, 2520000, 50160);
        builder.rent(unit26, "나군", rentB, 7648000, 115940);
        builder.rent(unit31, "가군", rentA, 2993000, 59570);
        builder.rent(unit31, "나군", rentB, 8692000, 127970);
        builder.add(
                "schedules",
                "eventKind",
                "APPLICATION",
                "channel",
                "ON_SITE",
                "timePrecision",
                "DATETIME",
                "startsAt",
                "2026-10-28T09:00:00+09:00",
                "endsAt",
                "2026-10-30T18:00:00+09:00",
                "labelAsWritten",
                "현장 접수",
                "evidenceId",
                builder.evidence(10, "10.28~10.30 현장 접수"));
        builder.add(
                "rules",
                "ruleKind",
                "DUPLICATE_APPLICATION_LIMIT",
                "definitionStatus",
                "DEFINED",
                "statementAsWritten",
                "1세대 1주택, 형별 중복신청 불가",
                "evidenceId",
                evidence);
        return builder.build();
    }

    private static long income(
            RuleSetFixtureBuilder builder,
            int percent,
            long single,
            long couple,
            long child,
            long children) {
        long group = builder.group("SHARED_DEFINITION", "ALL", percent + "% 소득과 자산");
        long income =
                builder.condition(
                        group,
                        "HOUSEHOLD_MONTHLY_INCOME",
                        "LTE",
                        percent,
                        "subjectCode",
                        "HOUSEHOLD",
                        "valueUnit",
                        "PERCENT_OF_STANDARD",
                        "incomeStandardTableId",
                        1,
                        "householdSizeFactCode",
                        "FAMILY_MEMBER_COUNT");
        builder.adjustment(income, single, "ADD_PERCENT_POINTS", 20, "SUM");
        builder.adjustment(income, couple, "ADD_PERCENT_POINTS", 10, "SUM");
        builder.adjustment(income, child, "ADD_PERCENT_POINTS", 10, "MAX");
        builder.adjustment(income, children, "ADD_PERCENT_POINTS", 20, "MAX");
        builder.condition(
                group,
                "HOUSEHOLD_ASSETS",
                "LTE",
                245000000,
                "subjectCode",
                "HOUSEHOLD",
                "valueUnit",
                "KRW");
        builder.condition(
                group,
                "HOUSEHOLD_CAR_VALUE",
                "LTE",
                45420000,
                "subjectCode",
                "HOUSEHOLD",
                "valueUnit",
                "KRW");
        return group;
    }

    private static void appendIncome(
            RuleSetFixtureBuilder builder,
            int index,
            long root,
            long income50,
            long income70,
            long income100) {
        if (java.util.Set.of(0, 2, 3, 6).contains(index)) {
            builder.condition(
                    root,
                    null,
                    null,
                    null,
                    "conditionRole",
                    "EXEMPTED",
                    "statementAsWritten",
                    "증빙 시 소득과 자산 검증 생략(p5)");
            return;
        }
        if (index == 9) {
            builder.reference(root, income50);
            return;
        }
        if (index == 11) {
            builder.reference(root, income100);
            return;
        }
        if (java.util.Set.of(1, 4, 5, 7).contains(index)) {
            builder.reference(root, income70);
        }
    }

    private static void scores(RuleSetFixtureBuilder builder, long step) {
        long residentBase = builder.numericGroup("SCORE_BAND", "AGE_AT_REFERENCE", "GTE", 19);
        long residenceUnknown = builder.group("SCORE_BAND", "ALL", "15년 이상 거주");
        builder.condition(residenceUnknown, null, null, null, "inputGapCode", "G02");
        builder.score(
                step, "거주기간", 30, new long[] {residentBase, residenceUnknown}, new int[] {22, 30});
        long[] ages = {
            builder.numericGroup("SCORE_BAND", "AGE_AT_REFERENCE", "GTE", 19),
            builder.numericGroup("SCORE_BAND", "AGE_AT_REFERENCE", "GTE", 40),
            builder.numericGroup("SCORE_BAND", "AGE_AT_REFERENCE", "GTE", 50),
            builder.numericGroup("SCORE_BAND", "AGE_AT_REFERENCE", "GTE", 60)
        };
        builder.score(step, "연령", 25, ages, new int[] {19, 21, 23, 25});
        long[] sizes = {
            builder.numericGroup("SCORE_BAND", "FAMILY_MEMBER_COUNT", "GTE", 1),
            builder.numericGroup("SCORE_BAND", "FAMILY_MEMBER_COUNT", "GTE", 3),
            builder.numericGroup("SCORE_BAND", "FAMILY_MEMBER_COUNT", "GTE", 4),
            builder.numericGroup("SCORE_BAND", "FAMILY_MEMBER_COUNT", "GTE", 5)
        };
        builder.score(step, "세대원 수", 30, sizes, new int[] {24, 26, 28, 30});
        long recipient = builder.group("SCORE_BAND", "ALL", "수급자 가점 유형");
        builder.condition(
                recipient,
                "BASIC_LIVELIHOOD_RECIPIENT",
                "IS_TRUE",
                null,
                "inputMatch",
                "INPUT_BROADER");
        builder.condition(recipient, null, null, null, "inputGapCode", "G17");
        long other = builder.group("SCORE_BAND", "ANY", "비수급자 가점 유형");
        builder.condition(other, null, null, null, "inputGapCode", "G10");
        builder.condition(other, null, null, null, "inputGapCode", "G16");
        builder.condition(other, "VETERAN_TARGET", "IS_TRUE", null, "inputMatch", "UNSETTLED");
        builder.score(step, "가점", 15, new long[] {recipient, other}, new int[] {15, 7});
    }
}

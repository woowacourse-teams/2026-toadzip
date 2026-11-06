package com.toadzip.backend.basiccomparison;

import static com.toadzip.backend.basiccomparison.ComparisonFixtures.*;
import static com.toadzip.backend.basiccomparison.domain.ComparisonContent.*;
import static com.toadzip.backend.basiccomparison.domain.ProfileField.*;

import com.toadzip.backend.basiccomparison.domain.ComparisonContent;
import java.time.LocalDate;
import java.util.List;

/** Partial real-notice examples with fictional users, not full production imports. */
public final class EligibilityFixtures {
    private EligibilityFixtures() {}

    public static ComparisonContent d05() {
        ComparisonContent original = sacheonWithInformation();
        List<BasicCheck> checks = original.paths().getFirst().checks();
        return new ComparisonContent(
                metadata(original, LocalDate.of(2026, 10, 16)), original.documents(), original.evidences(),
                List.of(new SupplyUnit("unit", "사천벌리1 26형", original.supplyUnits().getFirst().housingTypes(),
                        List.of("48240"))),
                List.of(new ComparisonPath("ordinary", "일반입주자 신청자격", PathKind.APPLICATION,
                        checks.subList(3, checks.size()), List.of())), original.options(),
                List.of(guidance("scope", GuidanceKind.REQUIRED_CHECK, "자녀 가산 등 적용 기준 확인"),
                        guidance("history", GuidanceKind.APPLICATION_EXCLUSION,
                                "공공임대주택 불법양도·전대 적발 후 4년이 지나지 않은 세대구성원은 선정 제한")),
                original.quantities(), original.rentOffers(), original.schedules(), checks.subList(0, 3), List.of());
    }

    public static ComparisonContent d11() {
        ComparisonContent base = shPermanent();
        List<Qualification> qualifications = List.of(
                qualification("welfare", "생계·의료급여 수급자", "WELFARE_RECIPIENT"),
                qualification("veteran", "보훈대상자", "VETERAN"),
                qualification("single-parent", "지원대상 한부모가족", "SINGLE_PARENT_FAMILY"));
        return new ComparisonContent(metadata(base, LocalDate.of(2025, 11, 13)), base.documents(), base.evidences(),
                List.of(new SupplyUnit("seoul", "서울 영구임대 신청 단위", List.of(), List.of("11"))),
                qualifications.stream().map(qualification -> new ComparisonPath(qualification.id(),
                        qualification.exactName() + " 신청자격", PathKind.APPLICATION, List.of(),
                        List.of(qualification.id()))).toList(),
                qualifications.stream().map(qualification -> new ComparisonOption(qualification.id(), "seoul",
                        qualification.id(), RecruitmentStatus.RECRUITING)).toList(),
                List.of(), List.of(), List.of(), List.of(),
                List.of(directCheck("residence", region("11")), directCheck("home", equal(FAMILY_OWNS_HOUSE, "false"))),
                qualifications);
    }

    public static ComparisonContent d01() {
        ComparisonContent base = classification("D01", "경기도 경기행복주택");
        return new ComparisonContent(metadata(base, LocalDate.of(2026, 10, 16)), base.documents(), base.evidences(),
                List.of(new SupplyUnit("suji", "용인죽전 36A", List.of(), List.of("41465")),
                        new SupplyUnit("giheung", "용인영덕 17A1", List.of(), List.of("41463"))),
                List.of(new ComparisonPath("newlywed", "신혼부부 신청자격", PathKind.APPLICATION,
                                List.of(directCheck("family", equal(FAMILY_TYPE, "COUPLE_OR_ENGAGED"))), List.of()),
                        new ComparisonPath("youth", "청년 신청자격", PathKind.APPLICATION,
                                List.of(directCheck("family", equal(FAMILY_TYPE, "UNMARRIED")),
                                        directCheck("age", age(BIRTH_DATE, 19, 39))), List.of())),
                List.of(new ComparisonOption("suji-newlywed", "suji", "newlywed", RecruitmentStatus.RECRUITING),
                        new ComparisonOption("giheung-youth", "giheung", "youth", RecruitmentStatus.RECRUITING)),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static BasicCheck directCheck(String id, com.toadzip.backend.basiccomparison.domain.BasicRule rule) {
        return new BasicCheck(id, id, rule, null, null, List.of("source"));
    }

    private static Qualification qualification(String id, String name, String category) {
        return new Qualification(id, name, null, "해당 공고의 법정 자격 및 증명 요건을 확인해 주세요.",
                List.of(category), List.of("source"));
    }

    private static Metadata metadata(ComparisonContent source, LocalDate referenceDate) {
        return new Metadata(referenceDate, ApplicantKind.INDIVIDUAL, AccessMode.APPLICATION_ROUTES,
                ReviewStatus.REVIEWED, source.metadata().classificationEvidenceId(),
                "가상 사용자 기능 검증용 일부 모집 선택지. 원문 전체를 적재한 자료가 아닙니다.");
    }
}

package com.toadzip.backend.announcement.domain;

import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.domain.HousingTypeAreas;
import com.toadzip.backend.housing.domain.HousingTypeNames;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 공고 하나의 기관 준비 줄을 공급표로 바꾼다. 공개·관리 조회의 준비 데이터 표시와 '준비 데이터로 정본 시작' 복사가 이 변환 하나를 쓴다.
 *
 * <ol>
 *   <li>공급행: LH 줄은 (공고 단지, 신청형 키 {@link HousingTypeNames#key})로 묶는다. LH는 같은 신청형을 신청자 묶음마다 여러 줄로
 *       준다("36형 일반(고령자)"·"36형 일반(청년)"). 형명이 빈 줄과 GH 줄은 줄마다 공급행이다. 공급행 형명은 줄 형명을 순서대로 잇는다.</li>
 *   <li>주택타입: 공고 단지의 연결 단지에서 {@link HousingTypeAreas#pick}(이름 우선, 같은 면적이 하나뿐일 때만)으로 임시로 고른다.
 *       면적은 첫 줄 전용면적이다. 고른 주택타입이 있으면 그 이름·면적을, 없으면 줄 면적(전용·공급 각각 처음 값이 있는 줄)을 보이고
 *       정본으로 복사한다. 정본으로 복사하는 주택형(신청형) 이름은 원천 형명이다.</li>
 *   <li>대상: 줄마다 하나(신청 조건 '공고문 참조'). 대상 표기는 원천이 준 표기, 없으면 형명 끝의 신청자 표기다. 신청자 표기가 없으면 한
 *       줄뿐인 공급행은 '전체', 여러 줄이면 형명 원문이고, 같은 표기가 여럿이어도 형명 원문이다. 금액이 없는 한 줄뿐인 공급행은 대상을
 *       두지 않는다('전체' 칸이 모집수를 갖는다).</li>
 *   <li>모집수: 공급행 모집수는 줄 세대수의 합(한 줄이라도 모르면 정하지 않음), 대상 수는 그 줄 세대수다({@link SourceSupplySheet}).</li>
 * </ol>
 */
public final class AgencySupplySheet {

    private static final String ALL = AnnouncementSupplyTarget.EVERYONE;
    /** 원천 대상의 신청 조건. 공고문으로 확인하기 전이라는 뜻이다. */
    private static final String SOURCE_CONDITION = "공고문 참조";
    private static final int MAX_TYPE_NAME = 255;

    private AgencySupplySheet() {
    }

    /**
     * lines는 한 공고의 준비 줄이고, typesOf는 연결 단지의 주택타입이다. 공고 단지가 없거나 연결 단지가 없으면 주택타입을 고르지 않는다.
     */
    public static Converted convert(List<AgencySupplyLine> lines, Function<HousingComplex, List<HousingType>> typesOf) {
        List<SourceSupplySheet.Row> rows = new ArrayList<>();
        Map<String, SupplySheet.Row> display = new LinkedHashMap<>();
        int order = 0;
        for (List<AgencySupplyLine> members : groups(lines)) {
            AgencySupplyLine first = members.getFirst();
            AnnouncementComplex announcementComplex = first.getAnnouncementComplex();
            HousingComplex complex = announcementComplex == null ? null : announcementComplex.linkedComplex();
            BigDecimal area = firstArea(members, AgencySupplyLine::getExclusiveArea);
            HousingType type = complex == null ? null
                    : HousingTypeAreas.pick(typesOf.apply(complex), area, first.getHousingTypeName()).orElse(null);
            BigDecimal exclusiveArea = type == null ? area : type.getExclusiveArea();
            BigDecimal supplyArea = type == null ? firstArea(members, AgencySupplyLine::getSupplyArea)
                    : type.getSupplyArea();
            String joinedName = joinedTypeName(members);
            String displayName = type == null ? joinedName : type.getName();
            String key = "line:" + first.getId();
            SupplySheetDraft.HousingTypeRow housingType = new SupplySheetDraft.HousingTypeRow(key, null,
                    announcementComplex == null ? null : announcementComplex.getId(),
                    complex == null ? null : complex.getId(), type == null ? null : type.getId(), first.getComplexName(),
                    joinedName, exclusiveArea, supplyArea, first.getExpectedMoveInMonth(), null, first.getSupplyCategory(),
                    order++);
            rows.add(new SourceSupplySheet.Row(housingType, false, totalCount(members), targets(members)));
            display.put(key, new SupplySheet.Row(first.getId(), first.getComplexName(), joinedName, displayName, complex,
                    type, first.getExpectedMoveInMonth(), first.getSupplyCategory(),
                    exclusiveArea, supplyArea, false));
        }
        SupplySheetDraft draft = SourceSupplySheet.draft(rows);
        return new Converted(draft, SupplySheet.fromDraft(draft, display));
    }

    /** 묶음에서 처음 값이 있는 줄의 면적. */
    private static BigDecimal firstArea(List<AgencySupplyLine> members, Function<AgencySupplyLine, BigDecimal> area) {
        return members.stream().map(area).filter(Objects::nonNull).findFirst().orElse(null);
    }

    /** 줄을 공급행으로 묶는다. 원천 순서대로 처음 나온 묶음이 앞선다. */
    private static List<List<AgencySupplyLine>> groups(List<AgencySupplyLine> lines) {
        Map<String, List<AgencySupplyLine>> groups = new LinkedHashMap<>();
        lines.stream().sorted(Comparator.comparingInt(AgencySupplyLine::getDisplayOrder)
                        .thenComparing(AgencySupplyLine::getId))
                .forEach(line -> groups.computeIfAbsent(groupKey(line), ignored -> new ArrayList<>()).add(line));
        return List.copyOf(groups.values());
    }

    private static String groupKey(AgencySupplyLine line) {
        String typeKey = HousingTypeNames.key(line.getHousingTypeName());
        if (line.getAgency() != AgencyCode.LH || typeKey.isEmpty()) {
            return "line:" + line.getId();
        }
        String complex = line.getAnnouncementComplex() == null ? "name:" + line.getComplexName()
                : "complex:" + line.getAnnouncementComplex().getId();
        return complex + "\u0000" + typeKey;
    }

    /** 공급행 형명은 줄 형명 원문을 순서대로 잇는다. 신청자 표기만 다른 형명이 여럿이면 모두 보인다. */
    private static String joinedTypeName(List<AgencySupplyLine> members) {
        String joined = members.stream().map(AgencySupplyLine::getHousingTypeName).distinct()
                .collect(Collectors.joining(" · "));
        return joined.length() <= MAX_TYPE_NAME ? joined : joined.substring(0, MAX_TYPE_NAME - 1) + "…";
    }

    /** 공급행 모집수는 줄 세대수의 합이다. 한 줄이라도 모르면 합을 정하지 않는다. */
    private static Integer totalCount(List<AgencySupplyLine> members) {
        int sum = 0;
        for (AgencySupplyLine line : members) {
            if (line.getHouseholdCount() == null) {
                return null;
            }
            sum += line.getHouseholdCount();
        }
        return sum;
    }

    private static List<SourceSupplySheet.Target> targets(List<AgencySupplyLine> members) {
        Map<String, Long> labelCounts = members.stream().collect(Collectors.groupingBy(
                line -> applicantLabel(line, members.size()), Collectors.counting()));
        List<SourceSupplySheet.Target> targets = new ArrayList<>();
        for (AgencySupplyLine line : members) {
            boolean priced = line.getRentalDeposit() != null && line.getMonthlyRent() != null;
            if (line.getTargetLabel() == null && !priced && members.size() == 1) {
                continue;
            }
            String label = line.getTargetLabel() != null ? line.getTargetLabel()
                    : labelCounts.get(applicantLabel(line, members.size())) > 1 ? line.getHousingTypeName()
                    : applicantLabel(line, members.size());
            targets.add(new SourceSupplySheet.Target(label, SOURCE_CONDITION, line.getHouseholdCount(), null,
                    new LeaseTerms.Amounts(line.getRentalDeposit(), line.getMonthlyRent(), null), targets.size() + 1));
        }
        return targets;
    }

    /** 형명 끝의 신청자 표기("36형 일반(고령자)" → "고령자"). 없으면 한 줄뿐인 공급행은 '전체', 여러 줄이면 형명 원문이다. */
    private static String applicantLabel(AgencySupplyLine line, int memberCount) {
        String applicant = HousingTypeNames.applicant(line.getHousingTypeName());
        if (!applicant.isEmpty()) {
            return applicant;
        }
        return memberCount == 1 ? ALL : line.getHousingTypeName();
    }

    /** 정본에 쓸 수 있는 공급표 draft와 같은 공급표의 읽기 모델. */
    public record Converted(SupplySheetDraft draft, SupplySheet sheet) {
    }
}

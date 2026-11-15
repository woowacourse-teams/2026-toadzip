package com.toadzip.backend.announcement.domain;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 공급대상 분류(닫힌 상수). 공급대상 하나는 분류를 여럿 가진다(신혼부부·한부모가족 = 신혼부부 + 한부모). AI 워커가 공고문 표기에서 읽고
 * (ai_worker/target_groups.py, 같은 상수), 관리자가 PDF 보정·공급표 화면에서 고친다. 분류를 정할 수 없는 표기(전체, 청년 창업인)는 분류가
 * 없다. 주거약자(HOUSING_VULNERABLE)는 다른 분류와 함께 붙는 구분이다(신혼부부·한부모가족(주거약자용) = 신혼부부 + 한부모 + 주거약자).
 */
public enum SupplyTargetGroup {
    GENERAL,
    YOUTH,
    UNIVERSITY_STUDENT,
    NEWLYWED,
    PRE_NEWLYWED,
    SINGLE_PARENT,
    ELDERLY,
    HOUSING_BENEFIT_RECIPIENT,
    GA_CLASS,
    NA_CLASS,
    EXISTING_RESIDENT,
    INDUSTRIAL_WORKER,
    HOUSING_VULNERABLE;

    /**
     * 신청자격 경로 분류가 공급대상 분류에 이어지는지(워커 target_groups.linked와 같다). 둘 다 분류가 있고 주거약자 구분이 같으며, 나머지
     * 분류가 하나라도 겹치거나 둘 다 주거약자뿐이어야 한다. 주거약자용 신혼부부 경로는 주거약자용이 아닌 신혼부부 대상에 잇지 않는다.
     */
    public static boolean linked(Collection<SupplyTargetGroup> route, Collection<SupplyTargetGroup> target) {
        if (route.isEmpty() || target.isEmpty()) {
            return false;
        }
        if (route.contains(HOUSING_VULNERABLE) != target.contains(HOUSING_VULNERABLE)) {
            return false;
        }
        Set<SupplyTargetGroup> wanted = withoutHousingVulnerable(route);
        Set<SupplyTargetGroup> offered = withoutHousingVulnerable(target);
        if (wanted.isEmpty() && offered.isEmpty()) {
            return true;
        }
        wanted.retainAll(offered);
        return !wanted.isEmpty();
    }

    /** 상수 순서로 정렬한 분류. null은 분류 없음이다. 같은 분류가 두 번 있으면 거절한다. */
    public static List<SupplyTargetGroup> distinct(Collection<SupplyTargetGroup> groups) {
        if (groups == null) {
            return List.of();
        }
        if (groups.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("공급대상 분류에 빈 값이 있다.");
        }
        Set<SupplyTargetGroup> unique = groups.isEmpty() ? EnumSet.noneOf(SupplyTargetGroup.class) : EnumSet.copyOf(groups);
        if (unique.size() != groups.size()) {
            throw new IllegalArgumentException("같은 공급대상 분류가 두 번 있다.");
        }
        return List.copyOf(unique);
    }

    /** 저장·추출 결과의 분류 이름. 이 서버가 모르는 이름(새 워커 상수)은 버린다. */
    public static List<SupplyTargetGroup> known(Collection<String> names) {
        EnumSet<SupplyTargetGroup> found = EnumSet.noneOf(SupplyTargetGroup.class);
        names.forEach(name -> Arrays.stream(values()).filter(group -> group.name().equals(name)).forEach(found::add));
        return List.copyOf(found);
    }

    private static Set<SupplyTargetGroup> withoutHousingVulnerable(Collection<SupplyTargetGroup> groups) {
        Set<SupplyTargetGroup> result = EnumSet.copyOf(groups);
        result.remove(HOUSING_VULNERABLE);
        return result;
    }
}

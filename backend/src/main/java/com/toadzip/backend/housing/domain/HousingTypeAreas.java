package com.toadzip.backend.housing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 공급행이 단지 주택형을 찾는 규칙. 주택형은 단지 안에서 전용면적(소수 둘째 자리)으로 정한다.
 * 마이홈이 수집한 단지에는 같은 면적 주택형이 여럿인 경우가 있어(101A1~A15가 모두 101.99㎡), 그때는 이름이 같은 주택형,
 * 없으면 가장 먼저 만든 주택형을 고른다. 같은 면적의 주택형은 실제로 같은 평면이 이름만 나뉜 것이라 어느 쪽에 붙여도 모집 정보가 틀리지 않는다.
 */
public final class HousingTypeAreas {

    private static final int SCALE = 2;

    private HousingTypeAreas() {
    }

    /** 공급행·주택형 면적 비교 키. 소수 셋째 자리에서 반올림한다. */
    public static BigDecimal key(BigDecimal exclusiveArea) {
        return exclusiveArea == null ? null : exclusiveArea.setScale(SCALE, RoundingMode.HALF_UP);
    }

    public static boolean sameArea(BigDecimal left, BigDecimal right) {
        return left != null && right != null && key(left).compareTo(key(right)) == 0;
    }

    /** 같은 면적 주택형 중 하나를 고른다. name은 공고 표기이며 없어도 된다. */
    public static Optional<HousingType> pick(Collection<HousingType> types, BigDecimal exclusiveArea, String name) {
        if (exclusiveArea == null) {
            return Optional.empty();
        }
        List<HousingType> sameArea = types.stream().filter(type -> sameArea(type.getExclusiveArea(), exclusiveArea))
                .sorted(Comparator.comparing(HousingType::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        String wanted = normalized(name);
        return sameArea.stream().filter(type -> wanted != null && wanted.equals(normalized(type.getName()))).findFirst()
                .or(() -> sameArea.stream().findFirst());
    }

    private static String normalized(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return name.replaceAll("\\s+", "").replaceFirst("형$", "").toLowerCase();
    }
}

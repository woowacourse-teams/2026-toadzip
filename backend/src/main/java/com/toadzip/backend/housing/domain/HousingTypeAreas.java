package com.toadzip.backend.housing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 공급행이 단지 주택타입을 찾는 규칙. 단지 주택타입은 공고문 표기 이름으로 나눈다(59B-1과 59D-1은 전용면적이 같아도 다르다).
 * 이름이 같은 주택타입({@link HousingTypeNames#typeKey})을 먼저 찾고, 이름이 같은 것이 없을 때 같은 면적 주택타입이 하나뿐이면 그것에
 * 임시로 잇는다({@link #pick}: LH 정제·관리자 단지 연결·면적만 준 편집표 행). 공고문(PDF) 보정은 화면이 공고문 주택타입 이름으로
 * 연결할 주택타입이나 새 주택타입을 정해 보내 이 임시 연결을 바로잡는다.
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

    /**
     * 공고문 확인 전 고르기. 이름이 같은 주택타입, 없으면 같은 면적 주택타입이 하나뿐일 때 그것이다. 같은 면적이 여럿이면 고르지 않는다.
     * name은 원천 표기이며 없어도 된다.
     */
    public static Optional<HousingType> pick(Collection<HousingType> types, BigDecimal exclusiveArea, String name) {
        var named = named(types, name, exclusiveArea);
        if (named.isPresent() || exclusiveArea == null) {
            return named;
        }
        List<HousingType> sameArea = types.stream().filter(type -> sameArea(type.getExclusiveArea(), exclusiveArea)).toList();
        return sameArea.size() == 1 ? Optional.of(sameArea.getFirst()) : Optional.empty();
    }

    /** 이름이 같은 주택타입. 여럿이면 면적이 같은 것, 그다음 먼저 만든 것이다. 이름이 비면 없다. */
    public static Optional<HousingType> named(Collection<HousingType> types, String name, BigDecimal exclusiveArea) {
        if (HousingTypeNames.key(name).isEmpty()) {
            return Optional.empty();
        }
        return types.stream().filter(type -> HousingTypeNames.sameType(type.getName(), type.getExclusiveArea(), name, exclusiveArea))
                .min(Comparator.comparing((HousingType type) -> !sameArea(type.getExclusiveArea(), exclusiveArea))
                        .thenComparing(HousingType::getId, Comparator.nullsLast(Comparator.naturalOrder())));
    }
}

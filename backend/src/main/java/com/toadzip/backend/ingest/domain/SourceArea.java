package com.toadzip.backend.ingest.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 원천 면적의 공통 정규화 규칙이다. 소수 넷째 자리까지 반올림해 비교·저장한다. */
public final class SourceArea {

    /** 비교·저장에 쓰는 소수 자리수다. 원천 조회 SQL의 원천 키도 같은 자리수로 반올림한다. */
    public static final int SCALE = 4;

    private SourceArea() {
    }

    public static BigDecimal normalized(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return value.setScale(SCALE, RoundingMode.HALF_UP);
    }
}

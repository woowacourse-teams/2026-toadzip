package com.toadzip.backend.ingest.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 원천 면적의 공통 정규화 규칙이다. 소수 넷째 자리까지 반올림해 비교·저장한다. */
public final class SourceArea {

    private SourceArea() {
    }

    public static BigDecimal normalized(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return value.setScale(4, RoundingMode.HALF_UP);
    }
}

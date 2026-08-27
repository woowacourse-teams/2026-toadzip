package com.toadzip.backend.ingest.domain;

import java.math.BigDecimal;

public record UtmKCoordinate(BigDecimal x, BigDecimal y) {

    public UtmKCoordinate {
        if (x == null || y == null) {
            throw new IllegalArgumentException("UTM-K 좌표는 필수입니다.");
        }
    }
}

package com.toadzip.backend.housing.domain;

import jakarta.persistence.Embeddable;

@Embeddable
public record RentalPriceRange(Long depositMin, Long depositMax, Long monthlyRentMin, Long monthlyRentMax) {

    public RentalPriceRange {
        requireValidRange(depositMin, depositMax, "보증금");
        requireValidRange(monthlyRentMin, monthlyRentMax, "월임대료");
    }

    private static void requireValidRange(Long min, Long max, String name) {
        if ((min == null) != (max == null) || (min != null && (min < 0 || max < min))) {
            throw new IllegalArgumentException(name + " 범위가 올바르지 않습니다.");
        }
    }
}

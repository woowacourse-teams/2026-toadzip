package com.toadzip.backend.housing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.Instant;

@Embeddable
public record MyHomeRentalCondition(
        @Column(name = "basic_deposit") Long deposit,
        @Column(name = "basic_monthly_rent") Long monthlyRent,
        @Column(name = "rental_condition_collected_at") Instant collectedAt
) {

    public MyHomeRentalCondition {
        if ((deposit != null && deposit < 0) || (monthlyRent != null && monthlyRent < 0)) {
            throw new IllegalArgumentException("기본 임대금액은 음수일 수 없습니다.");
        }
        if ((deposit != null || monthlyRent != null) && collectedAt == null) {
            throw new IllegalArgumentException("기본 임대금액의 수집 시각은 필수입니다.");
        }
    }
}

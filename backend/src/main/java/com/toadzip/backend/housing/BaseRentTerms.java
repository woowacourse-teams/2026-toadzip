package com.toadzip.backend.housing;

import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 주택형 카탈로그에 적용되는 기본 임대 조건.
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BaseRentTerms {

	@Column(name = "base_deposit")
	private Long deposit;

	@Column(name = "base_monthly_rent")
	private Long monthlyRent;

	@Column(name = "base_convertible_deposit_limit")
	private Long convertibleDepositLimit;

	public BaseRentTerms(Long deposit, Long monthlyRent, Long convertibleDepositLimit) {
		this.deposit = deposit;
		this.monthlyRent = monthlyRent;
		this.convertibleDepositLimit = convertibleDepositLimit;
	}

	/**
	 * 세 금액이 모두 같은지 비교한다.
	 */
	public static boolean sameValues(BaseRentTerms left, BaseRentTerms right) {
		if (left == null || right == null) {
			return left == right;
		}
		return Objects.equals(left.deposit, right.deposit) && Objects.equals(left.monthlyRent, right.monthlyRent)
				&& Objects.equals(left.convertibleDepositLimit, right.convertibleDepositLimit);
	}

}

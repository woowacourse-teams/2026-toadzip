package com.toadzip.backend.notice;

import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 공고 공급행에 적용되는 임대 조건이다.
 *
 * <p>
 * 주택형 카탈로그의 기본 임대 조건과 구분한다.
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RentTerms {

	@Column(name = "deposit")
	private Long deposit;

	@Column(name = "down_payment")
	private Long downPayment;

	@Column(name = "balance")
	private Long balance;

	@Column(name = "monthly_rent")
	private Long monthlyRent;

	public RentTerms(Long deposit, Long downPayment, Long balance, Long monthlyRent) {
		this.deposit = deposit;
		this.downPayment = downPayment;
		this.balance = balance;
		this.monthlyRent = monthlyRent;
	}

	/**
	 * 두 임대 조건의 금액을 값으로 비교한다.
	 */
	public static boolean sameValues(RentTerms left, RentTerms right) {
		if (left == null || right == null) {
			return left == right;
		}
		return Objects.equals(left.deposit, right.deposit) && Objects.equals(left.downPayment, right.downPayment)
				&& Objects.equals(left.balance, right.balance) && Objects.equals(left.monthlyRent, right.monthlyRent);
	}

}

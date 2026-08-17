package com.toadzip.backend.notice;

import java.math.BigDecimal;

/**
 * LH 주택형의 공급 정보를 묶은 값이다.
 *
 * @param depositText 숫자 또는 안내 문구로 제공되는 보증금 원문
 * @param monthlyRentText 숫자 또는 안내 문구로 제공되는 월 임대료 원문
 */
public record LhUnitSupplyValues(String complexLabel, String typeName, BigDecimal exclusiveArea, BigDecimal supplyArea,
		Integer totalUnitCount, Integer suppliedUnitCount, String depositText, String monthlyRentText) {

	/**
	 * 모든 공급 값이 없으면 {@code true}를 반환한다.
	 */
	public boolean isEmpty() {
		return complexLabel == null && typeName == null && exclusiveArea == null && supplyArea == null
				&& totalUnitCount == null && suppliedUnitCount == null && depositText == null
				&& monthlyRentText == null;
	}
}

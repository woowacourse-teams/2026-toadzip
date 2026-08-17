package com.toadzip.backend.housing;

import java.math.BigDecimal;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 단지에 속한 주택형 카탈로그다.
 *
 * <p>
 * 소속 단지, 주택형명, 전용면적, 주거공용면적을 자연키로 사용한다.
 */
@Entity
@Table(name = "unit_type",
		uniqueConstraints = @UniqueConstraint(name = "uk_unit_type_natural",
				columnNames = { "housing_complex_id", "type_name", "exclusive_area", "residential_common_area" }))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UnitType {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "housing_complex_id", nullable = false)
	private HousingComplex housingComplex;

	@Column(name = "type_name", nullable = false, length = 50)
	private String typeName;

	/**
	 * 면적은 제곱미터 소수 넷째 자리까지 저장한다.
	 */
	@Column(name = "exclusive_area", precision = 10, scale = 4)
	private BigDecimal exclusiveArea;

	@Column(name = "residential_common_area", precision = 10, scale = 4)
	private BigDecimal residentialCommonArea;

	@Column(name = "total_unit_count")
	private Integer totalUnitCount;

	@Embedded
	private BaseRentTerms baseRentTerms;

	public UnitType(HousingComplex housingComplex, String typeName, BigDecimal exclusiveArea,
			BigDecimal residentialCommonArea) {
		this.housingComplex = housingComplex;
		this.typeName = typeName;
		this.exclusiveArea = exclusiveArea;
		this.residentialCommonArea = residentialCommonArea;
	}

	/**
	 * 기본 임대 조건이 달라진 경우에만 갱신한다.
	 * @return 값이 변경되었으면 {@code true}
	 */
	public boolean updateBaseRentTerms(BaseRentTerms incoming) {
		if (BaseRentTerms.sameValues(this.baseRentTerms, incoming)) {
			return false;
		}
		this.baseRentTerms = incoming;
		return true;
	}

	/**
	 * 전체 세대수가 달라진 경우에만 갱신한다.
	 * @return 값이 변경되었으면 {@code true}
	 */
	public boolean updateTotalUnitCount(Integer incoming) {
		if (Objects.equals(totalUnitCount, incoming)) {
			return false;
		}
		totalUnitCount = incoming;
		return true;
	}

}

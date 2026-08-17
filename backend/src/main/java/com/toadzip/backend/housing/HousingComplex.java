package com.toadzip.backend.housing;

import java.time.LocalDate;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 공급 유형별 주거 단지 카탈로그다.
 *
 * <p>
 * 원천 단지 식별자와 공급 유형명을 자연키로 사용한다.
 */
@Entity
@Table(name = "housing_complex",
		uniqueConstraints = @UniqueConstraint(name = "uk_housing_complex_source",
				columnNames = { "source_complex_id", "supply_type_name" }))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HousingComplex {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String name;

	@Embedded
	private Address address;

	/**
	 * 준공일에서 파생된 연도다.
	 */
	private Integer completionYear;

	@Column(name = "completion_date")
	private LocalDate completionDate;

	@Column(name = "source_complex_id", nullable = false)
	private String sourceComplexId;

	@Column(name = "supply_type_name", nullable = false, length = 50)
	private String supplyTypeName;

	@Column(name = "unit_count")
	private Integer unitCount;

	@Column(name = "supply_institution_name", nullable = false, length = 50)
	private String supplyInstitutionName;

	@Column(name = "heating_type_name", length = 30)
	private String heatingTypeName;

	private Integer parkingSpaces;

	@Column(name = "corridor_type", length = 20)
	private String corridorType;

	@Column(name = "elevator_installation", length = 20)
	private String elevatorInstallation;

	@Column(name = "house_type_name", length = 30)
	private String houseTypeName;

	public HousingComplex(String name, Address address, String sourceComplexId, String supplyTypeName,
			Integer unitCount, String supplyInstitutionName) {
		this.name = name;
		this.address = address;
		this.sourceComplexId = sourceComplexId;
		this.supplyTypeName = requireText(supplyTypeName, "supplyTypeName");
		this.unitCount = unitCount;
		this.supplyInstitutionName = requireText(supplyInstitutionName, "supplyInstitutionName");
	}

	/**
	 * 카탈로그 상세 정보가 달라진 경우에만 갱신한다.
	 * @return 값이 변경되었으면 {@code true}
	 */
	public boolean updateCatalogDetails(CatalogDetails details) {
		if (currentCatalogDetails().equals(details)) {
			return false;
		}
		completionDate = details.completionDate();
		completionYear = completionYearOf(completionDate);
		heatingTypeName = details.heatingTypeName();
		parkingSpaces = details.parkingSpaces();
		corridorType = details.corridorType();
		elevatorInstallation = details.elevatorInstallation();
		houseTypeName = details.houseTypeName();
		return true;
	}

	/**
	 * 공급 정보가 달라진 경우에만 갱신한다.
	 * @return 값이 변경되었으면 {@code true}
	 */
	public boolean updateSupplyDetails(Integer incomingUnitCount, String incomingSupplyInstitutionName) {
		String normalizedInstitutionName = requireText(incomingSupplyInstitutionName, "supplyInstitutionName");
		if (Objects.equals(unitCount, incomingUnitCount)
				&& Objects.equals(supplyInstitutionName, normalizedInstitutionName)) {
			return false;
		}
		unitCount = incomingUnitCount;
		supplyInstitutionName = normalizedInstitutionName;
		return true;
	}

	public CatalogDetails currentCatalogDetails() {
		return new CatalogDetails(completionDate, heatingTypeName, parkingSpaces, corridorType, elevatorInstallation,
				houseTypeName);
	}

	static String requireText(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + "는 필수입니다.");
		}
		return value.strip();
	}

	private Integer completionYearOf(LocalDate date) {
		if (date == null) {
			return null;
		}
		return date.getYear();
	}

}

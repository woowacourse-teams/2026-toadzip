package com.toadzip.backend.ingest.lh.source;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "lh_notice_supply_source", uniqueConstraints = @UniqueConstraint(
		name = "uk_lh_notice_supply_source_key", columnNames = { "pan_id", "source_order" }))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LhNoticeSupplySource {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private Integer sourceOrder;
	private String panId;
	private String complexLabel;
	private String typeName;
	private String exclusiveArea;
	private String supplyArea;
	private String totalUnitCount;
	private String suppliedUnitCount;
	private String depositText;
	private String monthlyRentText;

	public LhNoticeSupplySource(int sourceOrder, String panId, String complexLabel, String typeName,
			String exclusiveArea, String supplyArea, String totalUnitCount, String suppliedUnitCount,
			String depositText, String monthlyRentText) {
		this.sourceOrder = sourceOrder;
		this.panId = trim(panId);
		this.complexLabel = trim(complexLabel);
		this.typeName = trim(typeName);
		this.exclusiveArea = trim(exclusiveArea);
		this.supplyArea = trim(supplyArea);
		this.totalUnitCount = trim(totalUnitCount);
		this.suppliedUnitCount = trim(suppliedUnitCount);
		this.depositText = trim(depositText);
		this.monthlyRentText = trim(monthlyRentText);
	}

	private static String trim(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return value.strip();
	}
}

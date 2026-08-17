package com.toadzip.backend.ingest.lh.source;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.toadzip.backend.ingest.SourceValues;
import com.toadzip.backend.ingest.lh.LhLeaseInfoItem;

/** LH 15059475 카탈로그 원문을 정제해 보존하는 typed source 행이다. */
@Entity
@Table(name = "lh_catalog_source")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LhCatalogSource {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private Integer sourceOrder;

	private String areaName;

	private String supplyTypeName;

	private String complexLabel;

	private String complexTotalUnitCount;

	private String exclusiveArea;

	private String totalUnitCount;

	private String depositText;

	private String monthlyRentText;

	public LhCatalogSource(int sourceOrder, LhLeaseInfoItem item) {
		this.sourceOrder = sourceOrder;
		areaName = SourceValues.trimToNull(item.areaName());
		supplyTypeName = SourceValues.trimToNull(item.supplyTypeName());
		complexLabel = SourceValues.trimToNull(item.complexLabel());
		complexTotalUnitCount = SourceValues.trimToNull(item.complexTotalUnitCount());
		exclusiveArea = SourceValues.trimToNull(item.exclusiveArea());
		totalUnitCount = SourceValues.trimToNull(item.totalUnitCount());
		depositText = SourceValues.trimToNull(item.deposit());
		monthlyRentText = SourceValues.trimToNull(item.monthlyRent());
	}

	public LhCatalogSource(int sourceOrder, String areaName, String supplyTypeName, String complexLabel,
			String complexTotalUnitCount, String exclusiveArea, String totalUnitCount, String depositText,
			String monthlyRentText) {
		this.sourceOrder = sourceOrder;
		this.areaName = SourceValues.trimToNull(areaName);
		this.supplyTypeName = SourceValues.trimToNull(supplyTypeName);
		this.complexLabel = SourceValues.trimToNull(complexLabel);
		this.complexTotalUnitCount = SourceValues.trimToNull(complexTotalUnitCount);
		this.exclusiveArea = SourceValues.trimToNull(exclusiveArea);
		this.totalUnitCount = SourceValues.trimToNull(totalUnitCount);
		this.depositText = SourceValues.trimToNull(depositText);
		this.monthlyRentText = SourceValues.trimToNull(monthlyRentText);
	}

	public LhLeaseInfoItem toItem() {
		return new LhLeaseInfoItem(areaName, supplyTypeName, complexLabel, complexTotalUnitCount, exclusiveArea,
				totalUnitCount, depositText, monthlyRentText);
	}

}

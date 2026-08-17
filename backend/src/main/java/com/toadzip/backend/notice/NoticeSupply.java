package com.toadzip.backend.notice;

import java.math.BigDecimal;
import java.time.YearMonth;

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

import com.toadzip.backend.housing.HousingComplex;
import com.toadzip.backend.housing.UnitType;

/**
 * 공고 한 버전의 공급 정보다.
 *
 * <p>
 * 마이홈 단지 단위 정보와 LH 주택형 단위 정보를 함께 표현한다.
 */
@Entity
@Table(name = "notice_supply",
		uniqueConstraints = @UniqueConstraint(name = "uk_notice_supply_order",
				columnNames = { "notice_id", "display_order" }))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NoticeSupply {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "notice_id", nullable = false)
	private Notice notice;

	@Column(name = "display_order", nullable = false)
	private int displayOrder;

	/**
	 * 마이홈 공급행 식별자다. LH 전용 행에서는 값이 없다.
	 */
	@Column(name = "house_sn")
	private Integer houseSn;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "housing_complex_id")
	private HousingComplex housingComplex;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "unit_type_id")
	private UnitType unitType;

	@Column(name = "unmatched_reason", length = 200)
	private String unmatchedReason;

	@Column(name = "type_name", length = 100)
	private String typeName;

	@Column(name = "exclusive_area", precision = 10, scale = 4)
	private BigDecimal exclusiveArea;

	@Column(name = "supply_area", precision = 10, scale = 4)
	private BigDecimal supplyArea;

	@Column(name = "unit_supply_count")
	private Integer unitSupplyCount;

	@Column(name = "unit_total_count")
	private Integer unitTotalCount;

	/**
	 * 숫자 또는 안내 문구가 올 수 있는 LH 보증금 원문.
	 */
	@Column(name = "lh_deposit_text", length = 100)
	private String lhDepositText;

	/**
	 * 숫자 또는 안내 문구가 올 수 있는 LH 월 임대료 원문.
	 */
	@Column(name = "lh_monthly_rent_text", length = 100)
	private String lhMonthlyRentText;

	@Column(name = "complex_name", length = 200)
	private String complexName;

	@Column(name = "lh_complex_label", length = 200)
	private String lhComplexLabel;

	@Column(name = "supplied_pnu", length = 19)
	private String suppliedPnu;

	@Column(name = "supplied_address", length = 300)
	private String suppliedAddress;

	@Column(name = "complex_supply_count")
	private Integer complexSupplyCount;

	@Column(name = "complex_total_unit_count")
	private Integer complexTotalUnitCount;

	@Column(name = "move_in_year_month", length = 7)
	private YearMonth moveInYearMonth;

	@Embedded
	private RentTerms rentTerms;

	@Column(name = "detail_url", length = 500)
	private String detailUrl;

	@Column(name = "mobile_detail_url", length = 500)
	private String mobileDetailUrl;

	private NoticeSupply(Notice notice, int displayOrder) {
		this.notice = notice;
		this.displayOrder = displayOrder;
	}

	/**
	 * 마이홈 단지 단위 공급행을 만든다.
	 */
	public static NoticeSupply ofComplex(Notice notice, int displayOrder, Integer houseSn, String complexName,
			String suppliedPnu, String suppliedAddress, Integer complexSupplyCount, Integer complexTotalUnitCount,
			RentTerms rentTerms, String detailUrl, String mobileDetailUrl) {
		NoticeSupply supply = new NoticeSupply(notice, displayOrder);
		supply.houseSn = houseSn;
		supply.complexName = complexName;
		supply.suppliedPnu = suppliedPnu;
		supply.suppliedAddress = suppliedAddress;
		supply.complexSupplyCount = complexSupplyCount;
		supply.complexTotalUnitCount = complexTotalUnitCount;
		supply.rentTerms = rentTerms;
		supply.detailUrl = detailUrl;
		supply.mobileDetailUrl = mobileDetailUrl;
		return supply;
	}

	/**
	 * 단지 행을 LH 주택형 행으로 분리한다.
	 */
	public NoticeSupply splitInto(int displayOrder, LhUnitSupplyValues lh) {
		NoticeSupply supply = copyAt(displayOrder);
		supply.applyLh(lh);
		return supply;
	}

	/**
	 * 마이홈 단지 공급행을 새 표시 순서로 복사한다.
	 */
	public NoticeSupply copyAt(int displayOrder) {
		NoticeSupply supply = new NoticeSupply(notice, displayOrder);
		supply.houseSn = houseSn;
		supply.complexName = complexName;
		supply.suppliedPnu = suppliedPnu;
		supply.suppliedAddress = suppliedAddress;
		supply.complexSupplyCount = complexSupplyCount;
		supply.complexTotalUnitCount = complexTotalUnitCount;
		supply.rentTerms = copyOf(rentTerms);
		supply.detailUrl = detailUrl;
		supply.mobileDetailUrl = mobileDetailUrl;
		supply.moveInYearMonth = moveInYearMonth;
		return supply;
	}

	/**
	 * 마이홈 행과 연결되지 않은 LH 주택형 공급행을 만든다.
	 */
	public static NoticeSupply ofLhOnly(Notice notice, int displayOrder, LhUnitSupplyValues lh) {
		NoticeSupply supply = new NoticeSupply(notice, displayOrder);
		supply.applyLh(lh);
		return supply;
	}

	/**
	 * 카탈로그 연결 결과를 기록한다.
	 */
	public void linkCatalog(HousingComplex housingComplex, UnitType unitType, String unmatchedReason) {
		this.housingComplex = housingComplex;
		this.unitType = unitType;
		this.unmatchedReason = unmatchedReason;
		if (unitType != null) {
			this.unmatchedReason = null;
		}
	}

	/**
	 * 입주 예정 연월을 기록한다.
	 */
	public void applyMoveInYearMonth(YearMonth moveInYearMonth) {
		this.moveInYearMonth = moveInYearMonth;
	}

	private void applyLh(LhUnitSupplyValues lh) {
		this.lhComplexLabel = lh.complexLabel();
		this.typeName = lh.typeName();
		this.exclusiveArea = lh.exclusiveArea();
		this.supplyArea = lh.supplyArea();
		this.unitSupplyCount = lh.suppliedUnitCount();
		this.unitTotalCount = lh.totalUnitCount();
		this.lhDepositText = lh.depositText();
		this.lhMonthlyRentText = lh.monthlyRentText();
	}

	/**
	 * 임베디드 값을 두 엔티티가 공유하지 않도록 복사한다.
	 */
	private static RentTerms copyOf(RentTerms source) {
		if (source == null) {
			return null;
		}
		return new RentTerms(source.getDeposit(), source.getDownPayment(), source.getBalance(),
				source.getMonthlyRent());
	}

}

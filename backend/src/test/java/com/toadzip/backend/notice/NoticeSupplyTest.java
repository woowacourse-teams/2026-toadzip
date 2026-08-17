package com.toadzip.backend.notice;

import java.math.BigDecimal;
import java.time.YearMonth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.toadzip.backend.housing.Address;
import com.toadzip.backend.housing.HousingComplex;
import com.toadzip.backend.housing.UnitType;

import static org.assertj.core.api.Assertions.assertThat;

class NoticeSupplyTest {

	@Test
	@DisplayName("단지 공급행을 LH 주택형 공급행으로 분리한다")
	void splitsComplexSupplyIntoLhUnitSupply() {
		NoticeSupply complexSupply = complexSupply(0);
		complexSupply.applyMoveInYearMonth(YearMonth.of(2026, 10));
		LhUnitSupplyValues lhValues = lhValues();

		NoticeSupply split = complexSupply.splitInto(1, lhValues);

		assertThat(split.getDisplayOrder()).isEqualTo(1);
		assertThat(split.getHouseSn()).isEqualTo(10);
		assertThat(split.getComplexName()).isEqualTo("대전 산내");
		assertThat(split.getSuppliedPnu()).isEqualTo("3011013600101900001");
		assertThat(split.getSuppliedAddress()).isEqualTo("대전광역시 동구 산내로 123");
		assertThat(split.getComplexSupplyCount()).isEqualTo(20);
		assertThat(split.getComplexTotalUnitCount()).isEqualTo(100);
		assertThat(split.getMoveInYearMonth()).isEqualTo(YearMonth.of(2026, 10));
		assertThat(split.getDetailUrl()).isEqualTo("https://example.com/detail");
		assertThat(split.getMobileDetailUrl()).isEqualTo("https://m.example.com/detail");
		assertThat(split.getLhComplexLabel()).isEqualTo("대전 산내 1단지");
		assertThat(split.getTypeName()).isEqualTo("36");
		assertThat(split.getExclusiveArea()).isEqualByComparingTo("36.5000");
		assertThat(split.getSupplyArea()).isEqualByComparingTo("51.5000");
		assertThat(split.getUnitSupplyCount()).isEqualTo(20);
		assertThat(split.getUnitTotalCount()).isEqualTo(100);
		assertThat(RentTerms.sameValues(split.getRentTerms(), complexSupply.getRentTerms())).isTrue();
		assertThat(split.getRentTerms()).isNotSameAs(complexSupply.getRentTerms());
	}

	@Test
	@DisplayName("마이홈과 연결되지 않은 LH 공급행을 생성한다")
	void createsLhOnlySupply() {
		Notice notice = notice();

		NoticeSupply supply = NoticeSupply.ofLhOnly(notice, 0, lhValues());

		assertThat(supply.getNotice()).isSameAs(notice);
		assertThat(supply.getHouseSn()).isNull();
		assertThat(supply.getLhComplexLabel()).isEqualTo("대전 산내 1단지");
		assertThat(supply.getLhDepositText()).isEqualTo("10,000,000");
		assertThat(supply.getLhMonthlyRentText()).isEqualTo("100,000");
	}

	@Test
	@DisplayName("주택형을 찾지 못한 이유를 기록한다")
	void recordsUnmatchedReasonWithoutUnitType() {
		HousingComplex housingComplex = housingComplex();
		NoticeSupply supply = complexSupply(0);

		supply.linkCatalog(housingComplex, null, "주택형을 찾지 못함");

		assertThat(supply.getHousingComplex()).isSameAs(housingComplex);
		assertThat(supply.getUnitType()).isNull();
		assertThat(supply.getUnmatchedReason()).isEqualTo("주택형을 찾지 못함");
	}

	@Test
	@DisplayName("주택형을 연결하면 기존 실패 이유를 제거한다")
	void clearsUnmatchedReasonWhenUnitTypeIsLinked() {
		HousingComplex housingComplex = housingComplex();
		UnitType unitType = new UnitType(housingComplex, "36", new BigDecimal("36.5000"), new BigDecimal("15.0000"));
		NoticeSupply supply = complexSupply(0);

		supply.linkCatalog(housingComplex, unitType, "이전 실패 이유");

		assertThat(supply.getHousingComplex()).isSameAs(housingComplex);
		assertThat(supply.getUnitType()).isSameAs(unitType);
		assertThat(supply.getUnmatchedReason()).isNull();
	}

	private NoticeSupply complexSupply(int displayOrder) {
		return NoticeSupply.ofComplex(notice(), displayOrder, 10, "대전 산내", "3011013600101900001", "대전광역시 동구 산내로 123",
				20, 100, new RentTerms(10_000_000L, 1_000_000L, 9_000_000L, 100_000L), "https://example.com/detail",
				"https://m.example.com/detail");
	}

	private LhUnitSupplyValues lhValues() {
		return new LhUnitSupplyValues("대전 산내 1단지", "36", new BigDecimal("36.5000"), new BigDecimal("51.5000"), 100, 20,
				"10,000,000", "100,000");
	}

	private HousingComplex housingComplex() {
		Address address = new Address("대전광역시 동구 산내로 123", "3011013600101900001", "30", "대전광역시", "110", "동구");
		return new HousingComplex("대전 산내", address, "complex-1", "국민임대", 100, "LH대전충남");
	}

	private Notice notice() {
		return Notice.firstVersion("notice-1", null, new NoticeSnapshot("일반공고", null, "첫 공고", "https://example.com",
				null, null, null, "LH", "공동주택", "국민임대", "1600-1004"));
	}

}

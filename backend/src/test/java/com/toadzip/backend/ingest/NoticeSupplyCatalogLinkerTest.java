package com.toadzip.backend.ingest;

import java.math.BigDecimal;
import java.util.List;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;

import com.toadzip.backend.housing.Address;
import com.toadzip.backend.housing.HousingComplex;
import com.toadzip.backend.housing.HousingComplexRepository;
import com.toadzip.backend.housing.UnitType;
import com.toadzip.backend.housing.UnitTypeRepository;
import com.toadzip.backend.notice.LhUnitSupplyValues;
import com.toadzip.backend.notice.Notice;
import com.toadzip.backend.notice.NoticeRepository;
import com.toadzip.backend.notice.NoticeSnapshot;
import com.toadzip.backend.notice.NoticeSupply;
import com.toadzip.backend.notice.NoticeSupplyRepository;
import com.toadzip.backend.notice.RentTerms;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(NoticeSupplyCatalogLinker.class)
class NoticeSupplyCatalogLinkerTest {

	private static final String PNU = "4131010500108520000";

	private static final String ADDRESS = "경기도 구리시 체육관로74번길 67";

	@Autowired
	private NoticeRepository noticeRepository;

	@Autowired
	private NoticeSupplyRepository supplyRepository;

	@Autowired
	private HousingComplexRepository complexRepository;

	@Autowired
	private UnitTypeRepository unitTypeRepository;

	@Autowired
	private EntityManager entityManager;

	@Autowired
	private NoticeSupplyCatalogLinker linker;

	private Notice notice;

	private HousingComplex complex;

	@BeforeEach
	void setUp() {
		supplyRepository.deleteAll();
		noticeRepository.deleteAll();
		unitTypeRepository.deleteAll();
		complexRepository.deleteAll();
		notice = noticeRepository.save(Notice.firstVersion("20989", null,
				new NoticeSnapshot("일반공고", null, "행복주택 모집", "https://apply.lh.or.kr/?panId=1", null, null,
						null, "LH", "아파트", "행복주택", null)));
		complex = complexRepository.save(new HousingComplex("구리수택 행복주택", address(PNU), "31500001", "행복주택",
				394, "LH경기북부"));
	}

	@Test
	@DisplayName("PNU와 공급유형 및 허용오차 안의 전용면적으로 공급행을 연결한다")
	void linksComplexAndUnitType() {
		UnitType expected = unitTypeRepository.save(new UnitType(complex, "26", new BigDecimal("26.7000"),
				new BigDecimal("10.0000")));
		supplyRepository.save(unitRow("26.7400", "36.80"));

		List<NoticeSupply> linked = linkAndReload();

		assertThat(linked).singleElement().satisfies(supply -> {
			assertThat(supply.getHousingComplex().getId()).isEqualTo(complex.getId());
			assertThat(supply.getUnitType().getId()).isEqualTo(expected.getId());
			assertThat(supply.getUnmatchedReason()).isNull();
		});
	}

	@Test
	@DisplayName("허용오차 후보가 모호하면 공급면적으로도 가르지 못한 이유를 기록한다")
	void recordsAmbiguousUnitType() {
		unitTypeRepository.save(new UnitType(complex, "26A", new BigDecimal("26.6900"),
				new BigDecimal("10.0000")));
		unitTypeRepository.save(new UnitType(complex, "26B", new BigDecimal("26.7100"),
				new BigDecimal("10.0000")));
		supplyRepository.save(unitRow("26.7000", "36.80"));

		assertThat(linkAndReload()).singleElement().satisfies(supply -> {
			assertThat(supply.getHousingComplex().getId()).isEqualTo(complex.getId());
			assertThat(supply.getUnitType()).isNull();
			assertThat(supply.getUnmatchedReason()).contains("후보 2건");
		});
	}

	@Test
	@DisplayName("전용면적이 같은 후보는 주거공용을 더한 공급면적으로 하나를 고른다")
	void resolvesBySupplyArea() {
		UnitType expected = unitTypeRepository.save(new UnitType(complex, "26", new BigDecimal("26.3700"),
				new BigDecimal("12.1700")));
		unitTypeRepository.save(new UnitType(complex, "26", new BigDecimal("26.3700"),
				new BigDecimal("13.5600")));
		supplyRepository.save(unitRow("26.3700", "38.5400"));

		assertThat(linkAndReload()).singleElement()
				.satisfies(supply -> assertThat(supply.getUnitType().getId()).isEqualTo(expected.getId()));
	}

	@Test
	@DisplayName("카탈로그가 늦게 저장돼도 다시 실행하면 공급행을 연결한다")
	void relinksAfterCatalogArrives() {
		supplyRepository.save(unitRow("26.7000", "36.80"));
		assertThat(linkAndReload()).singleElement().satisfies(supply -> assertThat(supply.getUnitType()).isNull());

		UnitType expected = unitTypeRepository.save(new UnitType(complex, "26", new BigDecimal("26.7000"),
				new BigDecimal("10.0000")));

		assertThat(linkAndReload()).singleElement()
				.satisfies(supply -> assertThat(supply.getUnitType().getId()).isEqualTo(expected.getId()));
	}

	private NoticeSupply unitRow(String exclusiveArea, String supplyArea) {
		NoticeSupply complexRow = NoticeSupply.ofComplex(notice, 0, 1, "구리수택", PNU, ADDRESS, 50, 394,
				new RentTerms(37_224_000L, 1_862_000L, 35_362_000L, 156_000L), null, null);
		return complexRow.splitInto(0, new LhUnitSupplyValues("구리수택 행복주택", "26",
				new BigDecimal(exclusiveArea), new BigDecimal(supplyArea), 394, 30, "공고문 참조", "공고문 참조"));
	}

	private Address address(String pnu) {
		return new Address(ADDRESS, pnu, "41", "경기도", "310", "구리시");
	}

	private List<NoticeSupply> linkAndReload() {
		linker.link(notice.getId());
		entityManager.flush();
		entityManager.clear();
		return supplyRepository.findByNoticeIdOrderByDisplayOrder(notice.getId());
	}
}

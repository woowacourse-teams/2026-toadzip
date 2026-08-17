package com.toadzip.backend.ingest.lh;

import java.math.BigDecimal;
import java.util.List;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.toadzip.backend.housing.Address;
import com.toadzip.backend.housing.BaseRentTerms;
import com.toadzip.backend.housing.HousingComplex;
import com.toadzip.backend.housing.HousingComplexRepository;
import com.toadzip.backend.housing.UnitType;
import com.toadzip.backend.housing.UnitTypeRepository;
import com.toadzip.backend.ingest.IngestReport;
import com.toadzip.backend.ingest.lh.source.LhCatalogSource;
import com.toadzip.backend.ingest.lh.source.LhCatalogSourceRepository;
import com.toadzip.backend.ingest.lh.source.LhCatalogSourceStore;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class LhLeaseInfoIngestServiceTest {

	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	@Autowired
	private HousingComplexRepository complexRepository;

	@Autowired
	private UnitTypeRepository unitTypeRepository;

	@Autowired
	private LhCatalogSourceRepository sourceRepository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private EntityManager entityManager;

	private UnitType matched;

	private LhLeaseInfoIngestService service;

	@BeforeEach
	void setUp() {
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);
		transaction.executeWithoutResult(status -> {
			sourceRepository.deleteAllInBatch();
			unitTypeRepository.deleteAllInBatch();
			complexRepository.deleteAllInBatch();
		});
		HousingComplex complex = complexRepository.save(new HousingComplex("강릉교동 행복주택",
				new Address("강원특별자치도 강릉시 가상로 1", "4215010100100010001", "42", "강원특별자치도", "150",
						"강릉시"),
				"10001", "행복주택", 180, "LH"));
		matched = unitTypeRepository.save(new UnitType(complex, "36", new BigDecimal("36.9700"),
				new BigDecimal("20.1000")));
		matched.updateBaseRentTerms(new BaseRentTerms(10_000_000L, 100_000L, 3_000_000L));
		service = new LhLeaseInfoIngestService(null, MAPPER, complexRepository, unitTypeRepository,
				new LhCatalogSourceStore(sourceRepository), transactionManager);
	}

	@Test
	@DisplayName("지역·단지명·공급유형과 세대수·전용면적이 유일하면 임대조건을 보정한다")
	void projectsUniqueCatalogRow() throws Exception {
		IngestReport report = service.applyPages(List.of(MAPPER.readTree(response("72", "19546000", "195460"))));
		entityManager.flush();
		entityManager.clear();

		UnitType actual = unitTypeRepository.findById(matched.getId()).orElseThrow();
		assertThat(report.failed()).isZero();
		assertThat(actual.getTotalUnitCount()).isEqualTo(72);
		assertThat(actual.getBaseRentTerms().getDeposit()).isEqualTo(19_546_000L);
		assertThat(actual.getBaseRentTerms().getMonthlyRent()).isEqualTo(195_460L);
		assertThat(actual.getBaseRentTerms().getConvertibleDepositLimit()).isEqualTo(3_000_000L);
	}

	@Test
	@DisplayName("빈 source projection은 기존 주택형 값을 삭제하지 않는다")
	void keepsDomainValuesForEmptySource() {
		matched.updateTotalUnitCount(72);
		IngestReport report = service.applySources(List.of());
		entityManager.flush();
		entityManager.clear();

		UnitType actual = unitTypeRepository.findById(matched.getId()).orElseThrow();
		assertThat(report.failed()).isZero();
		assertThat(actual.getTotalUnitCount()).isEqualTo(72);
		assertThat(actual.getBaseRentTerms().getConvertibleDepositLimit()).isEqualTo(3_000_000L);
	}

	@Test
	@DisplayName("typed source는 sourceOrder 순으로 교체 저장하고 모든 필드를 보존한다")
	void replacesTypedSourceSnapshotInOrder() {
		service.replaceSources(List.of(
				new LhLeaseInfoItem(" 지역 ", " 행복주택 ", " 단지 ", "180", "36.97", "72", "100", "10"),
				new LhLeaseInfoItem("지역2", "국민임대", "단지2", "90", "26.95", "36", "200", "20")));
		entityManager.flush();
		entityManager.clear();

		assertThat(sourceRepository.findAllByOrderBySourceOrderAscIdAsc()).extracting(LhCatalogSource::getSourceOrder)
				.containsExactly(0, 1);
		LhCatalogSource first = sourceRepository.findAllByOrderBySourceOrderAscIdAsc().getFirst();
		assertThat(first.getAreaName()).isEqualTo("지역");
		assertThat(first.getSupplyTypeName()).isEqualTo("행복주택");
		assertThat(first.getComplexLabel()).isEqualTo("단지");
		assertThat(first.getComplexTotalUnitCount()).isEqualTo("180");
		assertThat(first.getExclusiveArea()).isEqualTo("36.97");
		assertThat(first.getTotalUnitCount()).isEqualTo("72");
		assertThat(first.getDepositText()).isEqualTo("100");
		assertThat(first.getMonthlyRentText()).isEqualTo("10");
	}

	private String response(String totalUnitCount, String deposit, String monthlyRent) {
		return """
				[{"resHeader":[{"SS_CODE":"Y"}]},
				 {"dsList":[{"SUM_HSH_CNT":"180","HSH_CNT":"%s","ARA_NM":"강원특별자치도 강릉시",
				 "AIS_TP_CD_NM":"행복주택","SBD_LGO_NM":"강릉교동 행복주택","DDO_AR":"36.97",
				 "LS_GMY":"%s","RFE":"%s"}]}]
				 """.formatted(totalUnitCount, deposit, monthlyRent);
	}

}

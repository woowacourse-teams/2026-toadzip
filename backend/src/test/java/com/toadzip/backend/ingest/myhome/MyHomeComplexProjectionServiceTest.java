package com.toadzip.backend.ingest.myhome;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.toadzip.backend.housing.HousingComplexRepository;
import com.toadzip.backend.housing.UnitTypeRepository;
import com.toadzip.backend.ingest.ConstructionRentalPolicy;
import com.toadzip.backend.ingest.IngestRejectionReason;
import com.toadzip.backend.ingest.IngestReport;
import com.toadzip.backend.ingest.myhome.source.MyHomeComplexSourceRepository;
import com.toadzip.backend.ingest.myhome.source.MyHomeComplexSourceStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DataJpaTest
@Import(MyHomeComplexSourceStore.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MyHomeComplexProjectionServiceTest {

	@Autowired
	private MyHomeComplexSourceStore sourceStore;

	@Autowired
	private MyHomeComplexSourceRepository sourceRepository;

	@Autowired
	private HousingComplexRepository complexRepository;

	@Autowired
	private UnitTypeRepository unitTypeRepository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	private MyHomeComplexProjectionService service;

	@BeforeEach
	void setUp() {
		unitTypeRepository.deleteAll();
		complexRepository.deleteAll();
		sourceRepository.deleteAll();
		service = new MyHomeComplexProjectionService(sourceRepository, complexRepository, unitTypeRepository,
				new ConstructionRentalPolicy(), transactionManager);
	}

	@Test
	@DisplayName("staging 행을 공급유형별 단지와 주택형으로 생성한다")
	void createsComplexAndUnitTypesFromStagingRows() {
		sourceStore.store(List.of(item(123L, "46A", "LH"), item(123L, "59A", "LH")));

		IngestReport report = service.projectAll();

		assertThat(report.created()).isOne();
		assertThat(complexRepository.findAll()).singleElement().satisfies(complex -> {
			assertThat(complex.getSourceComplexId()).isEqualTo("123");
			assertThat(complex.getSupplyTypeName()).isEqualTo("국민임대");
			assertThat(complex.getSupplyInstitutionName()).isEqualTo("LH");
			assertThat(complex.getCompletionYear()).isEqualTo(2020);
		});
		assertThat(unitTypeRepository.findAll()).extracting("typeName").containsExactlyInAnyOrder("46A", "59A");
	}

	@Test
	@DisplayName("같은 staging을 다시 투영하면 도메인 행을 변경하지 않는다")
	void keepsUnchangedDomainRows() {
		sourceStore.store(List.of(item(123L, "46A", "LH")));
		service.projectAll();

		IngestReport report = service.projectAll();

		assertThat(report.unchanged()).isOne();
		assertThat(complexRepository.count()).isOne();
		assertThat(unitTypeRepository.count()).isOne();
	}

	@Test
	@DisplayName("주택형명이 같고 면적이 다른 staging 행을 각각 투영한다")
	void projectsSameNamedUnitTypesWithDifferentAreas() {
		MyHomeComplexSourceItem first = item(123L, "46A", "LH", "39.9541");
		MyHomeComplexSourceItem second = item(123L, "46A", "LH", "40.0000");
		sourceStore.store(List.of(first, second));

		IngestReport report = service.projectAll();

		assertThat(report.created()).isOne();
		assertThat(unitTypeRepository.findAll()).extracting("exclusiveArea")
			.containsExactlyInAnyOrder(new BigDecimal("39.9541"), new BigDecimal("40.0000"));
	}

	@Test
	@DisplayName("staging 값이 달라지면 기존 도메인 행을 갱신한다")
	void updatesChangedDomainRows() {
		sourceStore.store(List.of(item(123L, "46A", "LH")));
		service.projectAll();
		sourceStore.store(List.of(item(123L, "46A", "LH 서울지역본부")));

		IngestReport report = service.projectAll();

		assertThat(report.updated()).isOne();
		assertThat(complexRepository.findAll()).singleElement()
			.extracting("supplyInstitutionName")
			.isEqualTo("LH 서울지역본부");
	}

	@Test
	@DisplayName("공급기관이 없는 aggregate는 도메인을 만들기 전에 제외한다")
	void rejectsAggregateWithoutInstitution() {
		sourceStore.store(List.of(item(123L, "46A", " ")));

		IngestReport report = service.projectAll();

		assertThat(report.rejectedByReason()).containsEntry(IngestRejectionReason.MISSING_IDENTITY, 1);
		assertThat(complexRepository.count()).isZero();
	}

	@Test
	@DisplayName("한 aggregate 저장 실패는 다른 aggregate 저장을 막지 않는다")
	void continuesAfterOneAggregateFails() {
		sourceStore.store(List.of(item(123L, "46A", "LH"), item(456L, "59A", "LH")));
		UnitTypeRepository failingRepository = failingForComplex("123");
		MyHomeComplexProjectionService partiallyFailingService = new MyHomeComplexProjectionService(sourceRepository,
				complexRepository, failingRepository, new ConstructionRentalPolicy(), transactionManager);

		IngestReport report = partiallyFailingService.projectAll();

		assertThat(report.failed()).isOne();
		assertThat(report.created()).isOne();
		assertThat(complexRepository.findBySourceComplexIdAndSupplyTypeName("123", "국민임대")).isEmpty();
		assertThat(complexRepository.findBySourceComplexIdAndSupplyTypeName("456", "국민임대")).isPresent();
	}

	private UnitTypeRepository failingForComplex(String sourceComplexId) {
		UnitTypeRepository repository = mock(UnitTypeRepository.class);
		when(repository.findByHousingComplexAndTypeNameAndExclusiveAreaAndResidentialCommonArea(any(), any(), any(),
				any()))
			.thenReturn(Optional.empty());
		when(repository.save(argThat(unitType -> unitType != null
				&& sourceComplexId.equals(unitType.getHousingComplex().getSourceComplexId()))))
			.thenThrow(new IllegalStateException("주택형 저장 실패"));
		when(repository.save(argThat(unitType -> unitType != null
				&& !sourceComplexId.equals(unitType.getHousingComplex().getSourceComplexId()))))
			.thenAnswer(invocation -> unitTypeRepository.save(invocation.getArgument(0)));
		return repository;
	}

	private MyHomeComplexSourceItem item(Long complexId, String styleName, String institutionName) {
		return item(complexId, styleName, institutionName, "46.8");
	}

	private MyHomeComplexSourceItem item(Long complexId, String styleName, String institutionName,
			String exclusiveArea) {
		String pnu = "1111010100100010000";
		if (!complexId.equals(123L)) {
			pnu = "1111010100100020000";
		}
		return new MyHomeComplexSourceItem(complexId, institutionName, "11", "서울특별시", "110", "종로구",
				"테스트 단지 " + complexId, "서울특별시 종로구 테스트로 " + complexId, pnu, "20200101", 100, "국민임대", styleName,
				new BigDecimal(exclusiveArea), new BigDecimal("20.2"), "아파트", "지역난방", "복도식", "전체동 설치", 80, 10_000_000L,
				200_000L, 20_000_000L);
	}

}

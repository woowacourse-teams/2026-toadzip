package com.toadzip.backend.ingest.myhome.source;

import java.math.BigDecimal;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import com.toadzip.backend.ingest.IngestReport;
import com.toadzip.backend.ingest.myhome.MyHomeComplexSourceItem;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class MyHomeComplexSourceStoreTest {

	@Autowired
	private MyHomeComplexSourceRepository repository;

	@Autowired
	private EntityManager entityManager;

	private MyHomeComplexSourceStore store;

	@BeforeEach
	void setUp() {
		store = new MyHomeComplexSourceStore(repository);
	}

	@Test
	@DisplayName("새 원천 행은 typed staging 컬럼으로 저장한다")
	void storesNewSourceRow() {
		IngestReport report = store.store(java.util.List.of(item("LH", "46A")));
		flushAndClear();

		assertThat(report.created()).isOne();
		assertThat(repository.findAll()).singleElement().satisfies(source -> {
			assertThat(source.getHsmpSn()).isEqualTo(123L);
			assertThat(source.getInsttNm()).isEqualTo("LH");
			assertThat(source.getHsmpNm()).isEqualTo("테스트 단지");
			assertThat(source.getSuplyTyNm()).isEqualTo("국민임대");
			assertThat(source.getStyleNm()).isEqualTo("46A");
			assertThat(source.getSuplyPrvuseAr()).isEqualByComparingTo("46.8");
		});
	}

	@Test
	@DisplayName("같은 자연키와 값인 원천 행은 중복 저장하지 않는다")
	void skipsUnchangedSourceRow() {
		store.store(java.util.List.of(item("LH", "46A")));
		IngestReport report = store.store(java.util.List.of(item("LH", "46A")));
		flushAndClear();

		assertThat(report.unchanged()).isOne();
		assertThat(repository.count()).isOne();
	}

	@Test
	@DisplayName("자연키가 같고 값이 달라지면 기존 원천 행을 갱신한다")
	void updatesChangedSourceRow() {
		store.store(java.util.List.of(item("LH", "46A")));
		IngestReport report = store.store(java.util.List.of(item("LH 서울지역본부", "46A")));
		flushAndClear();

		assertThat(report.updated()).isOne();
		assertThat(repository.findAll()).singleElement()
			.extracting(MyHomeComplexSource::getInsttNm)
			.isEqualTo("LH 서울지역본부");
	}

	@Test
	@DisplayName("주택형명이 다르면 별도 원천 행으로 저장한다")
	void storesDifferentUnitTypesSeparately() {
		IngestReport report = store.store(java.util.List.of(item("LH", "46A"), item("LH", "59A")));
		flushAndClear();

		assertThat(report.created()).isEqualTo(2);
		assertThat(repository.count()).isEqualTo(2);
	}

	private MyHomeComplexSourceItem item(String institutionName, String styleName) {
		return new MyHomeComplexSourceItem(123L, institutionName, "11", "서울특별시", "110", "종로구", " 테스트 단지 ",
				"서울특별시 종로구 테스트로 1", "1111010100100010000", "20200101", 100, "국민임대", styleName, new BigDecimal("46.8"),
				new BigDecimal("20.2"), "아파트", "지역난방", "복도식", "전체동 설치", 80, 10_000_000L, 200_000L, 20_000_000L);
	}

	private void flushAndClear() {
		entityManager.flush();
		entityManager.clear();
	}

}

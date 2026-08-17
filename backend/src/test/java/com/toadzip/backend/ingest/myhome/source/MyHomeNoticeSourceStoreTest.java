package com.toadzip.backend.ingest.myhome.source;

import java.util.List;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import com.toadzip.backend.ingest.IngestRejectionReason;
import com.toadzip.backend.ingest.IngestReport;
import com.toadzip.backend.ingest.myhome.MyHomeNoticeSourceItem;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class MyHomeNoticeSourceStoreTest {

	@Autowired
	private MyHomeNoticeSourceRepository repository;

	@Autowired
	private EntityManager entityManager;

	private MyHomeNoticeSourceStore store;

	@BeforeEach
	void setUp() {
		store = new MyHomeNoticeSourceStore(repository);
	}

	@Test
	@DisplayName("새 공고 원천 행은 입력 순서와 typed staging 컬럼으로 저장한다")
	void storesNewSourceRowsInSourceOrder() {
		IngestReport report = store.store(List.of(item("200", 2, "두 번째"), item("100", 1, "첫 번째")));
		flushAndClear();

		assertThat(report.created()).isEqualTo(2);
		assertThat(repository.findAllByOrderBySourceOrderAsc()).extracting(MyHomeNoticeSource::getPblancId)
			.containsExactly("200", "100");
		assertThat(repository.findAllByOrderBySourceOrderAsc()).first().satisfies(source -> {
			assertThat(source.getSourceOrder()).isZero();
			assertThat(source.getHouseSn()).isEqualTo(2);
			assertThat(source.getPblancNm()).isEqualTo("두 번째");
			assertThat(source.getSuplyInsttNm()).isEqualTo("LH");
			assertThat(source.getTotHshldCo()).isEqualTo("100");
			assertThat(source.getRentGtn()).isEqualTo(10_000_000L);
		});
	}

	@Test
	@DisplayName("공고 ID가 없는 원천 행은 저장하지 않고 누락 사유로 보고한다")
	void rejectsMissingNoticeIdentity() {
		IngestReport report = store.store(List.of(item("  ", 1, "식별자 없음")));

		assertThat(report.rejectedByReason()).containsEntry(IngestRejectionReason.MISSING_IDENTITY, 1);
		assertThat(repository.count()).isZero();
	}

	@Test
	@DisplayName("같은 배치의 자연키와 값이 같은 원천 행은 한 행만 저장한다")
	void collapsesExactDuplicatesInBatch() {
		MyHomeNoticeSourceItem row = item("100", 1, "같은 공고");

		IngestReport report = store.store(List.of(row, row));
		flushAndClear();

		assertThat(report.created()).isOne();
		assertThat(repository.count()).isOne();
	}

	@Test
	@DisplayName("같은 배치의 자연키 내용이 갈리면 해당 공고 전체를 저장하지 않는다")
	void rejectsWholeNoticeForConflictingBatchRows() {
		List<MyHomeNoticeSourceItem> rows = List.of(item("100", 1, "충돌 A"), item("100", 1, "충돌 B"),
				item("100", 2, "같은 공고의 다른 행"), item("200", 1, "정상 공고"));

		IngestReport report = store.store(rows);
		flushAndClear();

		assertThat(report.rejectedByReason()).containsEntry(IngestRejectionReason.INVALID_SOURCE_ROW, 1);
		assertThat(repository.findAll()).singleElement()
			.extracting(MyHomeNoticeSource::getPblancId)
			.isEqualTo("200");
	}

	@Test
	@DisplayName("주택 일련번호가 없는 같은 공고 행의 충돌을 별도 키처럼 숨기지 않는다")
	void detectsConflictWhenHouseSerialNumberIsMissing() {
		IngestReport report = store.store(List.of(item("100", null, "충돌 A"), item("100", null, "충돌 B")));

		assertThat(report.rejectedByReason()).containsEntry(IngestRejectionReason.INVALID_SOURCE_ROW, 1);
		assertThat(repository.count()).isZero();
	}

	@Test
	@DisplayName("같은 자연키와 값인 기존 원천 행은 변경 없이 유지한다")
	void keepsUnchangedHistoricalSource() {
		store.store(List.of(item("100", 1, "기존 공고")));

		IngestReport report = store.store(List.of(item("100", 1, "기존 공고")));
		flushAndClear();

		assertThat(report.unchanged()).isOne();
		assertThat(repository.count()).isOne();
	}

	@Test
	@DisplayName("기존 자연키와 다른 내용이 오면 기존 공고 원천 전체를 유지한다")
	void preservesWholeHistoricalNoticeForChangedNaturalKey() {
		store.store(List.of(item("100", 1, "기존 공고")));
		flushAndClear();

		IngestReport report = store.store(List.of(item("100", 1, "변경 공고"), item("100", 2, "신규 공급행")));
		flushAndClear();

		assertThat(report.rejectedByReason()).containsEntry(IngestRejectionReason.INVALID_SOURCE_ROW, 1);
		assertThat(repository.findAll()).singleElement().satisfies(source -> {
			assertThat(source.getPblancNm()).isEqualTo("기존 공고");
			assertThat(source.getHouseSn()).isEqualTo(1);
		});
	}

	@Test
	@DisplayName("새 배치에 없는 과거 공고 원천을 삭제하지 않는다")
	void preservesHistoricalRowsMissingFromNextBatch() {
		store.store(List.of(item("100", 1, "과거 공고")));

		IngestReport report = store.store(List.of(item("200", 1, "새 공고")));
		flushAndClear();

		assertThat(report.created()).isOne();
		assertThat(repository.findAll()).extracting(MyHomeNoticeSource::getPblancId)
			.containsExactlyInAnyOrder("100", "200");
	}

	private MyHomeNoticeSourceItem item(String noticeId, Integer houseSerialNumber, String noticeName) {
		return new MyHomeNoticeSourceItem(noticeId, houseSerialNumber, "일반공고", noticeName, "LH", "아파트", "행복주택",
				null, "20260801", "20261001", "20260810", "20260812", "1600-1004", "https://example.com",
				"https://example.com/pc", "https://example.com/mobile", "테스트 단지", "서울특별시", "강남구",
				"서울특별시 강남구 테스트로 1", "테스트로", "테스트동", "1168010600100000000", "지역난방", "100",
				10, 10_000_000L, 1_000_000L, 9_000_000L, 100_000L);
	}

	private void flushAndClear() {
		entityManager.flush();
		entityManager.clear();
	}

}

package com.toadzip.backend.ingest.myhome;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.toadzip.backend.ingest.ConstructionRentalPolicy;
import com.toadzip.backend.ingest.IngestRejectionReason;
import com.toadzip.backend.ingest.IngestReport;
import com.toadzip.backend.ingest.myhome.source.MyHomeNoticeSourceRepository;
import com.toadzip.backend.ingest.myhome.source.MyHomeNoticeSourceStore;
import com.toadzip.backend.notice.NoticeRepository;
import com.toadzip.backend.notice.NoticeSupplyRepository;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(MyHomeNoticeSourceStore.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MyHomeNoticeProjectionServiceTest {

	@Autowired
	private MyHomeNoticeSourceRepository sourceRepository;

	@Autowired
	private MyHomeNoticeSourceStore sourceStore;

	@Autowired
	private NoticeRepository noticeRepository;

	@Autowired
	private NoticeSupplyRepository noticeSupplyRepository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	private MyHomeNoticeProjectionService service;

	@BeforeEach
	void setUp() {
		noticeSupplyRepository.deleteAll();
		noticeRepository.deleteAll();
		sourceRepository.deleteAll();
		service = new MyHomeNoticeProjectionService(sourceRepository, noticeRepository, noticeSupplyRepository,
				new ConstructionRentalPolicy(), transactionManager);
	}

	@Test
	@DisplayName("typed staging 행을 공고와 공급행으로 투영한다")
	void projectsNoticeAndSupplyRows() {
		sourceStore.storeBatch(List.of(item("100", 1, "행복주택"), item("100", 2, "행복주택")));

		IngestReport report = service.projectAll();

		assertThat(report.created()).isOne();
		assertThat(noticeRepository.findBySourceNoticeId("100")).isPresent();
		assertThat(noticeSupplyRepository.count()).isEqualTo(2);
	}

	@Test
	@DisplayName("같은 staging을 다시 투영하면 변경 없이 유지한다")
	void keepsIdempotentProjection() {
		sourceStore.storeBatch(List.of(item("100", 1, "행복주택")));
		service.projectAll();

		IngestReport report = service.projectAll();

		assertThat(report.unchanged()).isOne();
		assertThat(noticeRepository.count()).isOne();
		assertThat(noticeSupplyRepository.count()).isOne();
	}

	@Test
	@DisplayName("공고의 공급유형이 갈리면 공고 전체를 제외한다")
	void rejectsConflictingSupplyTypes() {
		sourceStore.storeBatch(List.of(item("100", 1, "행복주택"), item("100", 2, "국민임대")));

		IngestReport report = service.projectAll();

		assertThat(report.rejectedByReason()).containsEntry(IngestRejectionReason.INVALID_SOURCE_ROW, 1);
		assertThat(noticeRepository.count()).isZero();
	}

	@Test
	@DisplayName("houseSn이 없는 공급행만 있으면 공고를 제외한다")
	void rejectsMissingHouseSerialNumber() {
		sourceStore.storeBatch(List.of(item("100", null, "행복주택")));

		IngestReport report = service.projectAll();

		assertThat(report.rejectedByReason()).containsEntry(IngestRejectionReason.INVALID_SOURCE_ROW, 1);
		assertThat(noticeRepository.count()).isZero();
	}

	private MyHomeNoticeSourceItem item(String noticeId, Integer houseSn, String supplyType) {
		return new MyHomeNoticeSourceItem(noticeId, houseSn, "일반공고", "공고 " + noticeId, "LH", "아파트",
				supplyType, null, "20260801", "20260802", "20260803", "20260804", "문의", "https://all",
				"https://pc", "https://mobile", "단지", "서울", "강남구", "서울 강남구 주소", "도로", "법정동",
				"1111010100100010000", "지역난방", "100", 10, 100L, 10L, 90L, 1L);
	}

}

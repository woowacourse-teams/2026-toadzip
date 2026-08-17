package com.toadzip.backend.notice;

import java.time.YearMonth;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class NoticeSupplyRepositoryTest {

	@Autowired
	private NoticeRepository noticeRepository;

	@Autowired
	private NoticeSupplyRepository noticeSupplyRepository;

	@Autowired
	private EntityManager entityManager;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	@DisplayName("입주 예정 연월을 YYYY-MM 문자열로 저장하고 복원한다")
	void persistsYearMonthAsTextAndRestoresIt() {
		Notice notice = this.noticeRepository.save(notice());
		NoticeSupply supply = NoticeSupply.ofComplex(notice, 0, 10, "대전 산내", "3011013600101900001", "대전광역시 동구 산내로 123",
				20, 100, null, null, null);
		supply.applyMoveInYearMonth(YearMonth.of(2026, 10));
		this.noticeSupplyRepository.saveAndFlush(supply);

		String stored = this.jdbcTemplate.queryForObject("select move_in_year_month from notice_supply where id = ?",
				String.class, supply.getId());
		this.entityManager.clear();
		NoticeSupply reloaded = this.noticeSupplyRepository.findById(supply.getId()).orElseThrow();

		assertThat(stored).isEqualTo("2026-10");
		assertThat(reloaded.getMoveInYearMonth()).isEqualTo(YearMonth.of(2026, 10));
	}

	@Test
	@DisplayName("공급행을 표시 순서대로 조회한다")
	void findsSuppliesInDisplayOrder() {
		Notice notice = this.noticeRepository.save(notice());
		NoticeSupply second = this.noticeSupplyRepository.save(supply(notice, 1));
		NoticeSupply first = this.noticeSupplyRepository.save(supply(notice, 0));

		assertThat(this.noticeSupplyRepository.findByNoticeIdOrderByDisplayOrder(notice.getId())).containsExactly(first,
				second);
		assertThat(this.noticeSupplyRepository.findByNoticeOrderByDisplayOrder(notice)).containsExactly(first, second);
	}

	@Test
	@DisplayName("공고 식별자로 공급행을 일괄 삭제한다")
	void deletesSuppliesByNoticeId() {
		Notice notice = this.noticeRepository.save(notice());
		this.noticeSupplyRepository.save(supply(notice, 0));
		this.noticeSupplyRepository.saveAndFlush(supply(notice, 1));

		this.noticeSupplyRepository.deleteByNoticeId(notice.getId());
		this.entityManager.flush();

		assertThat(this.noticeSupplyRepository.count()).isZero();
	}

	private NoticeSupply supply(Notice notice, int displayOrder) {
		return NoticeSupply.ofComplex(notice, displayOrder, 10, "대전 산내", "3011013600101900001", "대전광역시 동구 산내로 123", 20,
				100, null, null, null);
	}

	private Notice notice() {
		return Notice.firstVersion("notice-1", null, new NoticeSnapshot("일반공고", null, "첫 공고", "https://example.com",
				null, null, null, "LH", "공동주택", "국민임대", "1600-1004"));
	}

}

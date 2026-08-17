package com.toadzip.backend.notice;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class NoticeRepositoryTest {

	@Autowired
	private NoticeRepository noticeRepository;

	@Autowired
	private NoticeScheduleRepository noticeScheduleRepository;

	@Autowired
	private ReceptionPlaceRepository receptionPlaceRepository;

	@Autowired
	private NoticeAttachmentRepository noticeAttachmentRepository;

	@Autowired
	private EntityManager entityManager;

	@Test
	@DisplayName("원천 식별자와 상세 URL로 공고를 조회한다")
	void findsNoticeBySourceKeysAndDetailUrl() {
		Notice notice = Notice.firstVersion("notice-1", null, snapshot("첫 공고"));
		notice.prepareLhRequest("pan-1", "05");
		this.noticeRepository.save(notice);

		assertThat(this.noticeRepository.findBySourceNoticeId("notice-1")).contains(notice);
		assertThat(this.noticeRepository.existsBySourceNoticeId("notice-1")).isTrue();
		assertThat(this.noticeRepository.findBySourcePanId("pan-1")).contains(notice);
		assertThat(this.noticeRepository.existsBySourcePanId("pan-1")).isTrue();
		assertThat(this.noticeRepository.findByDetailUrlContaining("panId")).containsExactly(notice);
	}

	@Test
	@DisplayName("공고 버전 체인을 순서대로 조회한다")
	void findsVersionChainInOrder() {
		Notice first = this.noticeRepository.save(Notice.firstVersion("notice-1", null, snapshot("첫 공고")));
		Notice next = this.noticeRepository.save(first.nextVersion("notice-2", "notice-1", snapshot("정정 공고")));

		assertThat(this.noticeRepository.findByBeforeSourceNoticeId("notice-1")).containsExactly(next);
		assertThat(this.noticeRepository.findByRootSourceNoticeIdOrderByVersionNumber("notice-1"))
			.containsExactly(first, next);
	}

	@Test
	@DisplayName("공고 하위 정보를 표시 순서대로 저장하고 조회한다")
	void persistsChildrenInDisplayOrder() {
		Notice notice = Notice.firstVersion("notice-1", null, snapshot("첫 공고"));
		notice.addSchedule("첫 일정", null, null, null, null, null, null);
		notice.addSchedule("두 번째 일정", null, null, null, null, null, null);
		notice.addReceptionPlace("첫 접수처", null, null, null, null, null);
		notice.addAttachment("공고문", "공고문.pdf", "https://example.com/notice.pdf", null);
		this.noticeRepository.saveAndFlush(notice);
		this.entityManager.clear();

		Notice reloaded = this.noticeRepository.findBySourceNoticeId("notice-1").orElseThrow();

		assertThat(reloaded.getSchedules()).extracting(NoticeSchedule::getDisplayOrder).containsExactly(0, 1);
		assertThat(reloaded.getReceptionPlaces()).extracting(ReceptionPlace::getAddress).containsExactly("첫 접수처");
		assertThat(reloaded.getAttachments()).extracting(NoticeAttachment::getName).containsExactly("공고문.pdf");
	}

	@Test
	@DisplayName("공고 식별자로 하위 정보를 일괄 삭제한다")
	void deletesChildrenByNoticeId() {
		Notice notice = Notice.firstVersion("notice-1", null, snapshot("첫 공고"));
		notice.addSchedule("첫 일정", null, null, null, null, null, null);
		notice.addReceptionPlace("첫 접수처", null, null, null, null, null);
		notice.addAttachment("공고문", "공고문.pdf", "https://example.com/notice.pdf", null);
		this.noticeRepository.saveAndFlush(notice);

		this.noticeScheduleRepository.deleteByNoticeId(notice.getId());
		this.receptionPlaceRepository.deleteByNoticeId(notice.getId());
		this.noticeAttachmentRepository.deleteByNoticeId(notice.getId());
		this.entityManager.flush();

		assertThat(this.noticeScheduleRepository.count()).isZero();
		assertThat(this.receptionPlaceRepository.count()).isZero();
		assertThat(this.noticeAttachmentRepository.count()).isZero();
	}

	@Test
	@DisplayName("컬렉션을 비우면 저장된 하위 정보도 삭제한다")
	void removesPersistedChildrenWhenCollectionsAreCleared() {
		Notice notice = Notice.firstVersion("notice-1", null, snapshot("첫 공고"));
		notice.addSchedule("첫 일정", null, null, null, null, null, null);
		notice.addReceptionPlace("첫 접수처", null, null, null, null, null);
		notice.addAttachment("공고문", "공고문.pdf", "https://example.com/notice.pdf", null);
		this.noticeRepository.saveAndFlush(notice);

		notice.clearLhChildren();
		this.entityManager.flush();
		this.entityManager.clear();

		assertThat(this.noticeScheduleRepository.count()).isZero();
		assertThat(this.receptionPlaceRepository.count()).isZero();
		assertThat(this.noticeAttachmentRepository.count()).isZero();
	}

	private NoticeSnapshot snapshot(String title) {
		return new NoticeSnapshot("일반공고", LocalDateTime.of(2026, 8, 1, 9, 0), title, "https://apply.lh.or.kr/?panId=1",
				LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 12), LocalDate.of(2026, 8, 20), "LH", "공동주택", "국민임대",
				"1600-1004");
	}

}

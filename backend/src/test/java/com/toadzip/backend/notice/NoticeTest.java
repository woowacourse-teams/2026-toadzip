package com.toadzip.backend.notice;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NoticeTest {

	@Test
	void createsNextVersionWithoutChangingPreviousVersion() {
		Notice first = Notice.firstVersion("notice-1", null, snapshot("첫 공고"));

		Notice next = first.nextVersion("notice-2", "notice-1", snapshot("정정 공고"));

		assertThat(first.getRootSourceNoticeId()).isEqualTo("notice-1");
		assertThat(first.getVersionNumber()).isEqualTo(1);
		assertThat(first.getSupersedesNotice()).isNull();
		assertThat(next.getRootSourceNoticeId()).isEqualTo("notice-1");
		assertThat(next.getVersionNumber()).isEqualTo(2);
		assertThat(next.getSupersedesNotice()).isSameAs(first);
		assertThat(next.currentSnapshot()).isEqualTo(snapshot("정정 공고"));
	}

	@Test
	void rebasesVersionOntoEarlierNotice() {
		Notice previous = Notice.firstVersion("notice-1", null, snapshot("첫 공고"));
		Notice correction = Notice.firstVersion("notice-2", "notice-1", snapshot("정정 공고"));

		correction.rebaseOnto(previous);

		assertThat(correction.getSupersedesNotice()).isSameAs(previous);
		assertThat(correction.getRootSourceNoticeId()).isEqualTo("notice-1");
		assertThat(correction.getVersionNumber()).isEqualTo(2);
	}

	@Test
	void comparesCurrentContentWithSnapshot() {
		Notice notice = Notice.firstVersion("notice-1", null, snapshot("첫 공고"));

		assertThat(notice.hasSameContentAs(snapshot("첫 공고"))).isTrue();
		assertThat(notice.hasSameContentAs(snapshot("제목 변경"))).isFalse();
	}

	@Test
	void exposesReadOnlyChildLists() {
		Notice notice = Notice.firstVersion("notice-1", null, snapshot("첫 공고"));
		notice.addSchedule("대전 산내", "2026.08.01~2026.08.03", null, null, null, null, null);
		notice.addReceptionPlace("대전광역시 동구", null, "09:00", "18:00", "042-000-0000", null);
		notice.addAttachment("공고문", "공고문.pdf", "https://example.com/notice.pdf", null);

		assertThatThrownBy(() -> notice.getSchedules().clear()).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> notice.getReceptionPlaces().clear()).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> notice.getAttachments().clear()).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void clearsLhChildren() {
		Notice notice = Notice.firstVersion("notice-1", null, snapshot("첫 공고"));
		notice.addSchedule("대전 산내", "2026.08.01~2026.08.03", null, null, null, null, null);
		notice.addReceptionPlace("대전광역시 동구", null, "09:00", "18:00", "042-000-0000", null);
		notice.addAttachment("공고문", "공고문.pdf", "https://example.com/notice.pdf", null);

		notice.clearLhChildren();

		assertThat(notice.getSchedules()).isEmpty();
		assertThat(notice.getReceptionPlaces()).isEmpty();
		assertThat(notice.getAttachments()).isEmpty();
	}

	private NoticeSnapshot snapshot(String title) {
		return new NoticeSnapshot("일반공고", LocalDateTime.of(2026, 8, 1, 9, 0), title, "https://apply.lh.or.kr/?panId=1",
				LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 12), LocalDate.of(2026, 8, 20), "LH", "공동주택", "국민임대",
				"1600-1004");
	}

}

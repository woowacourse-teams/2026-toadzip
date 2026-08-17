package com.toadzip.backend.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IngestReportTest {

	@Test
	@DisplayName("적재 결과와 제외 사유를 합산한다")
	void addsResultsAndRejectionReasons() {
		IngestReport report = IngestReport.oneCreated()
			.plus(IngestReport.oneUpdated())
			.plus(IngestReport.oneUnchanged())
			.plus(IngestReport.oneFailed())
			.plus(IngestReport.oneRejected(IngestRejectionReason.UNKNOWN_SUPPLY_TYPE))
			.plus(IngestReport.oneRejected(IngestRejectionReason.UNKNOWN_SUPPLY_TYPE))
			.plus(IngestReport.oneRejected(IngestRejectionReason.INVALID_SOURCE_ROW));

		assertThat(report.created()).isOne();
		assertThat(report.updated()).isOne();
		assertThat(report.unchanged()).isOne();
		assertThat(report.failed()).isOne();
		assertThat(report.rejected()).isEqualTo(3);
		assertThat(report.rejectedByReason()).containsEntry(IngestRejectionReason.UNKNOWN_SUPPLY_TYPE, 2)
			.containsEntry(IngestRejectionReason.INVALID_SOURCE_ROW, 1);
	}

	@Test
	@DisplayName("제외 사유별 집계는 외부에서 변경할 수 없다")
	void keepsRejectionCountsImmutable() {
		IngestReport report = IngestReport.oneRejected(IngestRejectionReason.MISSING_IDENTITY);

		assertThatThrownBy(() -> report.rejectedByReason().put(IngestRejectionReason.INVALID_SOURCE_ROW, 1))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	@DisplayName("음수인 적재 결과 개수는 허용하지 않는다")
	void rejectsNegativeResultCounts() {
		assertThatThrownBy(() -> new IngestReport(-1, 0, 0, 0, null)).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("적재 결과 개수는 음수일 수 없습니다.");
	}

}

package com.toadzip.backend.ingest.myhome;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.toadzip.backend.ingest.IngestReport;
import com.toadzip.backend.ingest.myhome.source.MyHomeNoticeSourceStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MyHomeNoticeIngestServiceTest {

	@Mock
	private MyHomeNoticeSourceClient sourceClient;

	@Mock
	private MyHomeNoticeSourceStore sourceStore;

	@Test
	@DisplayName("공급유형별 완전한 페이지를 한 번에 원천 저장한다")
	void storesAllCompleteSupplyTypeRowsBeforeProjection() {
		when(sourceClient.fetch(any())).thenReturn(List.of());
		when(sourceClient.fetch(new MyHomeNoticePageRequest(MyHomeNoticeSupplyType.PERMANENT_RENTAL, 1, 2)))
			.thenReturn(List.of(item("1", 1)));
		when(sourceStore.storeBatch(any())).thenReturn(IngestReport.oneCreated());

		MyHomeNoticeIngestResult result = service().ingest(2, 5);

		ArgumentCaptor<List<MyHomeNoticeSourceItem>> rows = ArgumentCaptor.captor();
		verify(sourceStore).storeBatch(rows.capture());
		assertThat(rows.getValue()).extracting(MyHomeNoticeSourceItem::pblancId).containsExactly("1");
		assertThat(result.staging().created()).isOne();
		assertThat(result.staging().created()).isOne();
	}

	@Test
	@DisplayName("최대 페이지까지 가득 찬 공급유형은 부분 행을 저장하지 않고 실패로 보고한다")
	void doesNotStoreIncompleteSupplyTypeRows() {
		when(sourceClient.fetch(any())).thenReturn(List.of(item("other", 1), item("other", 2)));
		when(sourceStore.storeBatch(List.of())).thenReturn(IngestReport.empty());

		MyHomeNoticeIngestResult result = service().ingest(2, 1);

		verify(sourceStore).storeBatch(List.of());
		assertThat(result.staging().failed()).isEqualTo(MyHomeNoticeSupplyType.values().length);
	}

	@Test
	@DisplayName("잘못된 페이지 범위는 조회 전에 거부한다")
	void rejectsInvalidPaging() {
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> service().ingest(0, 1))
			.isInstanceOf(IllegalArgumentException.class);
		verify(sourceClient, never()).fetch(any());
	}

	@Test
	@DisplayName("마이홈 공고 원천 적재는 도메인 투영을 호출하지 않는다")
	void storesSourceOnly() {
		when(sourceClient.fetch(any())).thenReturn(List.of());
		when(sourceStore.storeBatch(List.of())).thenReturn(IngestReport.empty());

		service().ingest(10, 1);

	}

	private MyHomeNoticeIngestService service() {
		return new MyHomeNoticeIngestService(sourceClient, sourceStore);
	}

	private MyHomeNoticeSourceItem item(String noticeId, Integer houseSn) {
		return new MyHomeNoticeSourceItem(noticeId, houseSn, "일반공고", "공고", "LH", "아파트", "행복주택",
				null, "20260801", "20260802", "20260803", "20260804", null, "https://example.com",
				"https://example.com/pc", "https://example.com/mobile", "단지", "서울", "강남구", "주소", null,
				null, "1111010100100010000", null, "100", 10, 100L, 10L, 90L, 1L);
	}

}

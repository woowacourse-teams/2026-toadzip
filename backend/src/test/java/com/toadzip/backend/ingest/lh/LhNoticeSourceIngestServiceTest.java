package com.toadzip.backend.ingest.lh;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.toadzip.backend.ingest.IngestReport;
import com.toadzip.backend.ingest.lh.source.LhNoticeDetailSourceRepository;
import com.toadzip.backend.ingest.lh.source.LhNoticeSourceStore;
import com.toadzip.backend.ingest.lh.source.LhNoticeSupplySourceRepository;
import com.toadzip.backend.ingest.myhome.MyHomeNoticeSourceItem;
import com.toadzip.backend.ingest.myhome.source.MyHomeNoticeSource;
import com.toadzip.backend.ingest.myhome.source.MyHomeNoticeSourceRepository;
import com.toadzip.backend.ingest.openapi.DataGoKrOpenApiClient;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LhNoticeSourceIngestServiceTest {

	private static final String PAN_ID = "2015122300020536";

	@Mock
	private DataGoKrOpenApiClient apiClient;

	@Mock
	private MyHomeNoticeSourceRepository myHomeNoticeSourceRepository;

	@Mock
	private LhNoticeSourceNormalizer normalizer;

	@Mock
	private LhNoticeSourceStore sourceStore;

	@Mock
	private LhNoticeDetailSourceRepository detailSourceRepository;

	@Mock
	private LhNoticeSupplySourceRepository supplySourceRepository;

	@Test
	@DisplayName("마이홈 공고 원본 데이터만으로 LH 상세·공급행을 공고당 한 번 적재한다")
	void ingestsLhSourcesOncePerMyHomeNotice() {
		MyHomeNoticeSource first = source(1);
		MyHomeNoticeSource second = source(2);
		JsonNode response = mock(JsonNode.class);
		LhNoticeSourceNormalizer.Rows rows = new LhNoticeSourceNormalizer.Rows(List.of(), List.of());
		when(myHomeNoticeSourceRepository.findAllByOrderBySourceOrderAscIdAsc()).thenReturn(List.of(first, second));
		when(apiClient.getRaw(anyString(), any())).thenReturn(response);
		when(normalizer.normalize(PAN_ID, response, response)).thenReturn(rows);
		when(sourceStore.replaceSnapshot(PAN_ID, rows.details(), rows.supplies())).thenReturn(IngestReport.oneCreated());

		IngestReport report = service().ingest(false);

		assertThat(report.created()).isOne();
		verify(apiClient).getRaw("lhLeaseNoticeDtlInfo1/getLeaseNoticeDtlInfo1", requestParams());
		verify(apiClient).getRaw("lhLeaseNoticeSplInfo1/getLeaseNoticeSplInfo1", requestParams());
	}

	@Test
	@DisplayName("저장된 LH 공고 원본 데이터는 외부 API를 다시 호출하지 않는다")
	void skipsStoredLhNoticeSources() {
		when(myHomeNoticeSourceRepository.findAllByOrderBySourceOrderAscIdAsc()).thenReturn(List.of(source(1)));
		when(detailSourceRepository.existsByPanId(PAN_ID)).thenReturn(true);

		IngestReport report = service().ingest(false);

		assertThat(report.unchanged()).isOne();
		verify(apiClient, never()).getRaw(anyString(), any());
	}

	@Test
	@DisplayName("새로고침 요청은 저장된 LH 공고 원본 데이터를 다시 적재한다")
	void refreshesStoredLhNoticeSources() {
		JsonNode response = mock(JsonNode.class);
		LhNoticeSourceNormalizer.Rows rows = new LhNoticeSourceNormalizer.Rows(List.of(), List.of());
		when(myHomeNoticeSourceRepository.findAllByOrderBySourceOrderAscIdAsc()).thenReturn(List.of(source(1)));
		when(apiClient.getRaw(anyString(), any())).thenReturn(response);
		when(normalizer.normalize(PAN_ID, response, response)).thenReturn(rows);
		when(sourceStore.replaceSnapshot(PAN_ID, rows.details(), rows.supplies())).thenReturn(IngestReport.oneUpdated());

		IngestReport report = service().ingest(true);

		assertThat(report.updated()).isOne();
		verify(apiClient, times(2)).getRaw(anyString(), any());
		verify(detailSourceRepository, never()).existsByPanId(anyString());
	}

	private LhNoticeSourceIngestService service() {
		return new LhNoticeSourceIngestService(apiClient, myHomeNoticeSourceRepository,
				new LhSupplyInfoTypeResolver(), normalizer, sourceStore, detailSourceRepository, supplySourceRepository);
	}

	private MyHomeNoticeSource source(int houseSn) {
		String detailUrl = "https://apply.lh.or.kr/apply?panId=" + PAN_ID
				+ "&ccrCnntSysDsCd=03&uppAisTpCd=06&aisTpCd=10";
		MyHomeNoticeSourceItem item = new MyHomeNoticeSourceItem(PAN_ID, houseSn, "일반공고", "공고", "LH", "아파트",
				"행복주택", null, null, null, null, null, null, detailUrl, null, null, "단지", null, null, null,
				null, null, null, null, null, null, null, null, null, null);
		return MyHomeNoticeSource.from(houseSn, item);
	}

	private org.springframework.util.MultiValueMap<String, String> requestParams() {
		return new LhNoticeRequest(PAN_ID, "03", "06", "10", "063").toParams();
	}
}

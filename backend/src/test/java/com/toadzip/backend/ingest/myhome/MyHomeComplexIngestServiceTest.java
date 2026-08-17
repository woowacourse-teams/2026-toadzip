package com.toadzip.backend.ingest.myhome;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.toadzip.backend.ingest.IngestReport;
import com.toadzip.backend.ingest.myhome.source.MyHomeComplexSourceStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MyHomeComplexIngestServiceTest {

	@Mock
	private MyHomeComplexSourceClient sourceClient;

	@Mock
	private MyHomeComplexSourceStore sourceStore;

	@Mock
	private MyHomeComplexProjectionService projectionService;

	@Mock
	private MyHomeRegionCatalog regionCatalog;

	private MyHomeComplexIngestService service;

	@BeforeEach
	void setUp() {
		service = new MyHomeComplexIngestService(sourceClient, sourceStore, projectionService, regionCatalog);
	}

	@Test
	@DisplayName("지역의 마지막 페이지까지 받은 뒤 staging과 도메인에 적재한다")
	void ingestsCompleteRegionPagesAndProjectsStaging() {
		MyHomeRegion region = new MyHomeRegion("11", "110", "서울특별시", "종로구");
		when(regionCatalog.all()).thenReturn(List.of(region));
		when(sourceClient.fetch(new MyHomeComplexPageRequest("11", "110", 1, 2)))
			.thenReturn(List.of(item(1L), item(2L)));
		when(sourceClient.fetch(new MyHomeComplexPageRequest("11", "110", 2, 2))).thenReturn(List.of(item(3L)));
		when(sourceStore.store(any())).thenReturn(new IngestReport(3, 0, 0, 0, null));
		when(projectionService.projectAll()).thenReturn(IngestReport.oneCreated());

		MyHomeComplexIngestResult result = service.ingestNationwide(2, 10);

		ArgumentCaptor<List<MyHomeComplexSourceItem>> rows = ArgumentCaptor.captor();
		verify(sourceStore).store(rows.capture());
		assertThat(rows.getValue()).extracting(MyHomeComplexSourceItem::hsmpSn).containsExactly(1L, 2L, 3L);
		assertThat(result.staging().created()).isEqualTo(3);
		assertThat(result.projection().created()).isOne();
	}

	@Test
	@DisplayName("지역 페이지 조회가 실패하면 그 지역의 불완전한 행은 저장하지 않는다")
	void skipsIncompleteRegionAfterPageFailure() {
		MyHomeRegion region = new MyHomeRegion("11", "110", "서울특별시", "종로구");
		when(regionCatalog.all()).thenReturn(List.of(region));
		when(sourceClient.fetch(new MyHomeComplexPageRequest("11", "110", 1, 2)))
			.thenReturn(List.of(item(1L), item(2L)));
		when(sourceClient.fetch(new MyHomeComplexPageRequest("11", "110", 2, 2)))
			.thenThrow(new IllegalStateException("원천 호출 실패"));
		when(projectionService.projectAll()).thenReturn(IngestReport.empty());

		MyHomeComplexIngestResult result = service.ingestNationwide(2, 10);

		verify(sourceStore, never()).store(any());
		assertThat(result.staging().failed()).isOne();
	}

	@Test
	@DisplayName("페이지 크기와 최대 페이지 수는 양수여야 한다")
	void rejectsInvalidPagingConditions() {
		assertThatThrownBy(() -> service.ingestNationwide(0, 10)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.ingestNationwide(10, 0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.ingestNationwide(10, 1_001)).isInstanceOf(IllegalArgumentException.class);
	}

	private MyHomeComplexSourceItem item(Long complexId) {
		return new MyHomeComplexSourceItem(complexId, "LH", "11", "서울특별시", "110", "종로구", "테스트 단지", "서울특별시 종로구 테스트로 1",
				"1111010100100010000", "20200101", 100, "국민임대", "46A", null, null, "아파트", null, null, null, null, null,
				null, null);
	}

}

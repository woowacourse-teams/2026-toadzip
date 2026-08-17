package com.toadzip.backend.ingest.myhome;

import java.util.ArrayList;
import java.util.List;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.toadzip.backend.ingest.IngestReport;
import com.toadzip.backend.ingest.myhome.source.MyHomeComplexSourceStore;

@Slf4j
@Service
public class MyHomeComplexIngestService {

	private static final int MAX_PAGE_SIZE = 1_000;

	private static final int MAX_PAGES = 1_000;

	private final MyHomeComplexSourceClient sourceClient;

	private final MyHomeComplexSourceStore sourceStore;

	private final MyHomeComplexProjectionService projectionService;

	private final MyHomeRegionCatalog regionCatalog;

	public MyHomeComplexIngestService(MyHomeComplexSourceClient sourceClient, MyHomeComplexSourceStore sourceStore,
			MyHomeComplexProjectionService projectionService, MyHomeRegionCatalog regionCatalog) {
		this.sourceClient = sourceClient;
		this.sourceStore = sourceStore;
		this.projectionService = projectionService;
		this.regionCatalog = regionCatalog;
	}

	public MyHomeComplexIngestResult ingestNationwide(int pageSize, int maxPages) {
		validatePaging(pageSize, maxPages);
		IngestReport staging = IngestReport.empty();
		for (MyHomeRegion region : regionCatalog.all()) {
			staging = staging.plus(ingestRegion(region, pageSize, maxPages));
		}
		IngestReport projection = projectionService.projectAll();
		return new MyHomeComplexIngestResult(staging, projection);
	}

	private IngestReport ingestRegion(MyHomeRegion region, int pageSize, int maxPages) {
		try {
			List<MyHomeComplexSourceItem> rows = fetchCompleteRegion(region, pageSize, maxPages);
			return sourceStore.replaceRegionSnapshot(region, rows);
		}
		catch (RuntimeException exception) {
			log.warn("마이홈 단지 지역 적재 실패: region={}", region.fullCode(), exception);
			return IngestReport.oneFailed();
		}
	}

	private List<MyHomeComplexSourceItem> fetchCompleteRegion(MyHomeRegion region, int pageSize, int maxPages) {
		List<MyHomeComplexSourceItem> rows = new ArrayList<>();
		for (int page = 1; page <= maxPages; page++) {
			MyHomeComplexPageRequest request = new MyHomeComplexPageRequest(region.provinceCode(),
					region.districtCode(), page, pageSize);
			List<MyHomeComplexSourceItem> pageRows = sourceClient.fetch(request);
			rows.addAll(pageRows);
			if (pageRows.size() < pageSize) {
				return rows;
			}
		}
		throw new IllegalStateException("최대 페이지 안에 지역 조회가 끝나지 않았습니다.");
	}

	private void validatePaging(int pageSize, int maxPages) {
		if (pageSize < 1 || pageSize > MAX_PAGE_SIZE || maxPages < 1 || maxPages > MAX_PAGES) {
			throw new IllegalArgumentException("페이지 크기와 최대 페이지 수는 1~1000이어야 합니다.");
		}
	}

}

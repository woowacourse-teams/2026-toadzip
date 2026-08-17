package com.toadzip.backend.ingest.myhome;

import java.util.ArrayList;
import java.util.List;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.toadzip.backend.ingest.IngestReport;
import com.toadzip.backend.ingest.myhome.source.MyHomeNoticeSourceStore;

@Slf4j
@Service
public class MyHomeNoticeIngestService {

	private static final int MAX_PAGE_SIZE = 1_000;

	private static final int MAX_PAGES = 1_000;

	private final MyHomeNoticeSourceClient sourceClient;

	private final MyHomeNoticeSourceStore sourceStore;

	public MyHomeNoticeIngestService(MyHomeNoticeSourceClient sourceClient, MyHomeNoticeSourceStore sourceStore) {
		this.sourceClient = sourceClient;
		this.sourceStore = sourceStore;
	}

	public MyHomeNoticeIngestResult ingest(int pageSize, int maxPages) {
		validatePaging(pageSize, maxPages);
		List<MyHomeNoticeSourceItem> completeRows = new ArrayList<>();
		IngestReport staging = IngestReport.empty();
		for (MyHomeNoticeSupplyType supplyType : MyHomeNoticeSupplyType.values()) {
			try {
				completeRows.addAll(fetchComplete(supplyType, pageSize, maxPages));
			}
			catch (RuntimeException exception) {
				log.warn("마이홈 공고 공급유형 조회 실패: supplyType={}", supplyType, exception);
				staging = staging.plus(IngestReport.oneFailed());
			}
		}
		staging = staging.plus(storeBatch(completeRows));
		return new MyHomeNoticeIngestResult(staging);
	}

	private List<MyHomeNoticeSourceItem> fetchComplete(MyHomeNoticeSupplyType supplyType, int pageSize,
			int maxPages) {
		List<MyHomeNoticeSourceItem> rows = new ArrayList<>();
		for (int page = 1; page <= maxPages; page++) {
			MyHomeNoticePageRequest request = new MyHomeNoticePageRequest(supplyType, page, pageSize);
			List<MyHomeNoticeSourceItem> pageRows = sourceClient.fetch(request);
			if (pageRows.isEmpty()) {
				return rows;
			}
			rows.addAll(pageRows);
			if (pageRows.size() < pageSize) {
				return rows;
			}
		}
		throw new IllegalStateException("최대 페이지 안에 공급유형 조회가 끝나지 않았습니다.");
	}

	private IngestReport storeBatch(List<MyHomeNoticeSourceItem> rows) {
		try {
			return sourceStore.storeBatch(rows);
		}
		catch (RuntimeException exception) {
			log.warn("마이홈 공고 원천 일괄 저장 실패", exception);
			return IngestReport.oneFailed();
		}
	}

	private void validatePaging(int pageSize, int maxPages) {
		if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
			throw new IllegalArgumentException("페이지 크기는 1~1000이어야 합니다.");
		}
		if (maxPages < 1 || maxPages > MAX_PAGES) {
			throw new IllegalArgumentException("최대 페이지 수는 1~1000이어야 합니다.");
		}
	}

}

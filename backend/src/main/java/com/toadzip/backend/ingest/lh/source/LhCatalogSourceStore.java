package com.toadzip.backend.ingest.lh.source;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.toadzip.backend.ingest.IngestReport;
import com.toadzip.backend.ingest.lh.LhLeaseInfoItem;

/** 완성된 LH 전국 응답을 typed source 스냅샷으로 교체한다. */
@Service
public class LhCatalogSourceStore {

	private final LhCatalogSourceRepository repository;

	public LhCatalogSourceStore(LhCatalogSourceRepository repository) {
		this.repository = repository;
	}

	@Transactional
	public IngestReport replaceSnapshot(List<LhLeaseInfoItem> items) {
		List<LhCatalogSource> rows = new java.util.ArrayList<>();
		for (int sourceOrder = 0; sourceOrder < items.size(); sourceOrder++) {
			rows.add(new LhCatalogSource(sourceOrder, items.get(sourceOrder)));
		}
		return replaceSnapshotRows(rows);
	}

	@Transactional
	public IngestReport replaceSnapshotRows(List<LhCatalogSource> rows) {
		repository.deleteAllInBatch();
		repository.flush();
		repository.saveAll(rows);
		return new IngestReport(rows.size(), 0, 0, 0, java.util.Map.of());
	}

}

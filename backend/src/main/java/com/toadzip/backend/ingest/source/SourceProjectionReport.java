package com.toadzip.backend.ingest.source;

import com.toadzip.backend.ingest.IngestReport;

/** 저장된 typed source를 공통 도메인으로 재투영한 단계별 결과다. */
public record SourceProjectionReport(
		long myHomeComplexSourceRows,
		long myHomeNoticeSourceRows,
		long lhCatalogSourceRows,
		long lhNoticeDetailSourceRows,
		long lhNoticeSupplySourceRows,
		IngestReport myHomeComplexProjection,
		IngestReport myHomeNoticeProjection,
		IngestReport lhCatalogProjection,
		IngestReport lhNoticeProjection,
		IngestReport catalogLinking) {

	public IngestReport total() {
		return myHomeComplexProjection.plus(myHomeNoticeProjection)
				.plus(lhCatalogProjection)
				.plus(lhNoticeProjection)
				.plus(catalogLinking);
	}
}

package com.toadzip.backend.ingest.source;

import org.springframework.stereotype.Service;

import com.toadzip.backend.ingest.IngestReport;
import com.toadzip.backend.ingest.NoticeSupplyCatalogLinker;
import com.toadzip.backend.ingest.lh.LhLeaseInfoIngestService;
import com.toadzip.backend.ingest.lh.LhNoticeProjectionService;
import com.toadzip.backend.ingest.lh.source.LhCatalogSourceRepository;
import com.toadzip.backend.ingest.lh.source.LhNoticeDetailSourceRepository;
import com.toadzip.backend.ingest.lh.source.LhNoticeSupplySourceRepository;
import com.toadzip.backend.ingest.myhome.MyHomeComplexProjectionService;
import com.toadzip.backend.ingest.myhome.MyHomeNoticeProjectionService;
import com.toadzip.backend.ingest.myhome.source.MyHomeComplexSourceRepository;
import com.toadzip.backend.ingest.myhome.source.MyHomeNoticeSourceRepository;

/** 외부 API를 호출하지 않고 저장된 source snapshot만 순서대로 재투영한다. */
@Service
public class SourceProjectionService {

	private final MyHomeComplexProjectionService myHomeComplexProjectionService;

	private final MyHomeNoticeProjectionService myHomeNoticeProjectionService;

	private final LhLeaseInfoIngestService lhLeaseInfoIngestService;

	private final LhNoticeProjectionService lhNoticeProjectionService;

	private final NoticeSupplyCatalogLinker catalogLinker;

	private final MyHomeComplexSourceRepository myHomeComplexSourceRepository;

	private final MyHomeNoticeSourceRepository myHomeNoticeSourceRepository;

	private final LhCatalogSourceRepository lhCatalogSourceRepository;

	private final LhNoticeDetailSourceRepository lhNoticeDetailSourceRepository;

	private final LhNoticeSupplySourceRepository lhNoticeSupplySourceRepository;

	public SourceProjectionService(MyHomeComplexProjectionService myHomeComplexProjectionService,
			MyHomeNoticeProjectionService myHomeNoticeProjectionService,
			LhLeaseInfoIngestService lhLeaseInfoIngestService, LhNoticeProjectionService lhNoticeProjectionService,
			NoticeSupplyCatalogLinker catalogLinker,
			MyHomeComplexSourceRepository myHomeComplexSourceRepository,
			MyHomeNoticeSourceRepository myHomeNoticeSourceRepository,
			LhCatalogSourceRepository lhCatalogSourceRepository,
			LhNoticeDetailSourceRepository lhNoticeDetailSourceRepository,
			LhNoticeSupplySourceRepository lhNoticeSupplySourceRepository) {
		this.myHomeComplexProjectionService = myHomeComplexProjectionService;
		this.myHomeNoticeProjectionService = myHomeNoticeProjectionService;
		this.lhLeaseInfoIngestService = lhLeaseInfoIngestService;
		this.lhNoticeProjectionService = lhNoticeProjectionService;
		this.catalogLinker = catalogLinker;
		this.myHomeComplexSourceRepository = myHomeComplexSourceRepository;
		this.myHomeNoticeSourceRepository = myHomeNoticeSourceRepository;
		this.lhCatalogSourceRepository = lhCatalogSourceRepository;
		this.lhNoticeDetailSourceRepository = lhNoticeDetailSourceRepository;
		this.lhNoticeSupplySourceRepository = lhNoticeSupplySourceRepository;
	}

	public SourceProjectionReport projectAll() {
		IngestReport complex = myHomeComplexProjectionService.projectAll();
		IngestReport notice = myHomeNoticeProjectionService.projectAll();
		IngestReport catalog = lhLeaseInfoIngestService.projectAll();
		IngestReport lhNotice = lhNoticeProjectionService.projectAll();
		IngestReport links = catalogLinker.linkAll();
		return new SourceProjectionReport(myHomeComplexSourceRepository.count(), myHomeNoticeSourceRepository.count(),
				lhCatalogSourceRepository.count(), lhNoticeDetailSourceRepository.count(),
				lhNoticeSupplySourceRepository.count(), complex, notice, catalog, lhNotice, links);
	}

	/** 재투영 endpoint와 기존 호출부가 함께 사용할 수 있는 짧은 별칭이다. */
	public SourceProjectionReport project() {
		return projectAll();
	}
}

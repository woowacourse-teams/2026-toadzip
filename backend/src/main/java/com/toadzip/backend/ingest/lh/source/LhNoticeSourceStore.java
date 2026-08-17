package com.toadzip.backend.ingest.lh.source;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.toadzip.backend.ingest.IngestReport;

@Service
public class LhNoticeSourceStore {

	private final LhNoticeDetailSourceRepository detailRepository;

	private final LhNoticeSupplySourceRepository supplyRepository;

	public LhNoticeSourceStore(LhNoticeDetailSourceRepository detailRepository,
			LhNoticeSupplySourceRepository supplyRepository) {
		this.detailRepository = detailRepository;
		this.supplyRepository = supplyRepository;
	}

	@Transactional
	public IngestReport replaceSnapshot(String panId, List<LhNoticeDetailSource> details,
			List<LhNoticeSupplySource> supplies) {
		detailRepository.deleteByPanId(panId);
		supplyRepository.deleteByPanId(panId);
		detailRepository.flush();
		supplyRepository.flush();
		detailRepository.saveAll(details);
		supplyRepository.saveAll(supplies);
		return new IngestReport(details.size() + supplies.size(), 0, 0, 0, java.util.Map.of());
	}
}

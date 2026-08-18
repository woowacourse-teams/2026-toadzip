package com.toadzip.backend.ingest.lh;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.toadzip.backend.ingest.IngestRejectionReason;
import com.toadzip.backend.ingest.IngestReport;
import com.toadzip.backend.ingest.SourceValues;
import com.toadzip.backend.ingest.lh.source.LhNoticeDetailSourceRepository;
import com.toadzip.backend.ingest.lh.source.LhNoticeSourceStore;
import com.toadzip.backend.ingest.lh.source.LhNoticeSupplySourceRepository;
import com.toadzip.backend.ingest.myhome.source.MyHomeNoticeSource;
import com.toadzip.backend.ingest.myhome.source.MyHomeNoticeSourceRepository;
import com.toadzip.backend.ingest.openapi.DataGoKrOpenApiClient;

import tools.jackson.databind.JsonNode;

@Slf4j
@Service
public class LhNoticeSourceIngestService {

	private static final String DETAIL_PATH = "lhLeaseNoticeDtlInfo1/getLeaseNoticeDtlInfo1";

	private static final String SUPPLY_PATH = "lhLeaseNoticeSplInfo1/getLeaseNoticeSplInfo1";

	private final DataGoKrOpenApiClient apiClient;

	private final MyHomeNoticeSourceRepository myHomeNoticeSourceRepository;

	private final LhSupplyInfoTypeResolver supplyTypeResolver;

	private final LhNoticeSourceNormalizer normalizer;

	private final LhNoticeSourceStore sourceStore;

	private final LhNoticeDetailSourceRepository detailSourceRepository;

	private final LhNoticeSupplySourceRepository supplySourceRepository;

	public LhNoticeSourceIngestService(@Qualifier("lhApiClient") DataGoKrOpenApiClient apiClient,
			MyHomeNoticeSourceRepository myHomeNoticeSourceRepository, LhSupplyInfoTypeResolver supplyTypeResolver,
			LhNoticeSourceNormalizer normalizer, LhNoticeSourceStore sourceStore,
			LhNoticeDetailSourceRepository detailSourceRepository,
			LhNoticeSupplySourceRepository supplySourceRepository) {
		this.apiClient = apiClient;
		this.myHomeNoticeSourceRepository = myHomeNoticeSourceRepository;
		this.supplyTypeResolver = supplyTypeResolver;
		this.normalizer = normalizer;
		this.sourceStore = sourceStore;
		this.detailSourceRepository = detailSourceRepository;
		this.supplySourceRepository = supplySourceRepository;
	}

	public IngestReport ingest() {
		return ingest(false);
	}

	public IngestReport ingest(boolean refresh) {
		IngestReport report = IngestReport.empty();
		for (MyHomeNoticeSource source : distinctNoticeSources()) {
			report = report.plus(applyOne(source, refresh));
		}
		return report;
	}

	private IngestReport applyOne(MyHomeNoticeSource source, boolean refresh) {
		Optional<LhNoticeRequest> request = requestFor(source);
		if (request.isEmpty()) {
			return rejectionFor(source);
		}
		LhNoticeRequest resolved = request.orElseThrow();
		if (!refresh && hasStoredSnapshot(resolved.panId())) {
			return IngestReport.oneUnchanged();
		}
		try {
			JsonNode details = apiClient.getRaw(DETAIL_PATH, resolved.toParams());
			JsonNode supplies = apiClient.getRaw(SUPPLY_PATH, resolved.toParams());
			LhNoticeSourceNormalizer.Rows rows = normalizer.normalize(resolved.panId(), details, supplies);
			return sourceStore.replaceSnapshot(resolved.panId(), rows.details(), rows.supplies());
		}
		catch (RuntimeException exception) {
			log.warn("LH 공고 상세·공급 원본 적재 실패: pblancId={}", source.getPblancId(), exception);
			return IngestReport.oneFailed();
		}
	}

	private List<MyHomeNoticeSource> distinctNoticeSources() {
		Map<String, MyHomeNoticeSource> byNoticeId = new LinkedHashMap<>();
		for (MyHomeNoticeSource source : myHomeNoticeSourceRepository.findAllByOrderBySourceOrderAscIdAsc()) {
			String noticeId = SourceValues.trimToNull(source.getPblancId());
			String key = noticeId == null ? "source:" + source.getSourceKey() : "notice:" + noticeId;
			byNoticeId.putIfAbsent(key, source);
		}
		return new ArrayList<>(byNoticeId.values());
	}

	private boolean hasStoredSnapshot(String panId) {
		return detailSourceRepository.existsByPanId(panId) || supplySourceRepository.existsByPanId(panId);
	}

	private Optional<LhNoticeRequest> requestFor(MyHomeNoticeSource source) {
		Optional<String> code = supplyInfoTypeCodeOf(source);
		if (code.isEmpty() || source.getUrl() == null) {
			return Optional.empty();
		}
		try {
			return LhNoticeRequest.from(URI.create(source.getUrl()), code.orElseThrow());
		}
		catch (IllegalArgumentException exception) {
			return Optional.empty();
		}
	}

	private Optional<String> supplyInfoTypeCodeOf(MyHomeNoticeSource source) {
		try {
			return supplyTypeResolver.resolve(source.getSuplyTyNm());
		}
		catch (IllegalArgumentException exception) {
			return Optional.empty();
		}
	}

	private IngestReport rejectionFor(MyHomeNoticeSource source) {
		if (supplyInfoTypeCodeOf(source).isEmpty()) {
			return IngestReport.oneRejected(IngestRejectionReason.UNSUPPORTED_LH_SUPPLEMENT_TYPE);
		}
		return IngestReport.oneRejected(IngestRejectionReason.INVALID_SOURCE_ROW);
	}
}

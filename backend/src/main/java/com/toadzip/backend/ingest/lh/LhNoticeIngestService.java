package com.toadzip.backend.ingest.lh;

import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.toadzip.backend.ingest.IngestRejectionReason;
import com.toadzip.backend.ingest.IngestReport;
import com.toadzip.backend.ingest.SourceValues;
import com.toadzip.backend.ingest.lh.source.LhNoticeDetailSource;
import com.toadzip.backend.ingest.lh.source.LhNoticeDetailSourceRepository;
import com.toadzip.backend.ingest.lh.source.LhNoticeSourceStore;
import com.toadzip.backend.ingest.lh.source.LhNoticeSupplySource;
import com.toadzip.backend.ingest.lh.source.LhNoticeSupplySourceRepository;
import com.toadzip.backend.ingest.openapi.DataGoKrOpenApiClient;
import com.toadzip.backend.notice.LhUnitSupplyValues;
import com.toadzip.backend.notice.Notice;
import com.toadzip.backend.notice.NoticeAttachmentRepository;
import com.toadzip.backend.notice.NoticeRepository;
import com.toadzip.backend.notice.NoticeScheduleRepository;
import com.toadzip.backend.notice.NoticeSupply;
import com.toadzip.backend.notice.NoticeSupplyRepository;
import com.toadzip.backend.notice.ReceptionPlaceRepository;

import tools.jackson.databind.JsonNode;

@Slf4j
@Service
public class LhNoticeIngestService {

	private static final String DETAIL_PATH = "lhLeaseNoticeDtlInfo1/getLeaseNoticeDtlInfo1";

	private static final String SUPPLY_PATH = "lhLeaseNoticeSplInfo1/getLeaseNoticeSplInfo1";

	private final DataGoKrOpenApiClient apiClient;

	private final NoticeRepository noticeRepository;

	private final NoticeSupplyRepository supplyRepository;

	private final LhSupplyInfoTypeResolver supplyTypeResolver;

	private final TransactionTemplate transactionTemplate;

	private final Clock clock;

	private final LhNoticeSourceNormalizer normalizer;

	private final LhNoticeSourceStore sourceStore;

	private final LhNoticeDetailSourceRepository detailSourceRepository;

	private final LhNoticeSupplySourceRepository supplySourceRepository;

	private final NoticeScheduleRepository scheduleRepository;

	private final ReceptionPlaceRepository receptionPlaceRepository;

	private final NoticeAttachmentRepository attachmentRepository;

	public LhNoticeIngestService(@Qualifier("lhApiClient") DataGoKrOpenApiClient apiClient,
			NoticeRepository noticeRepository, NoticeSupplyRepository supplyRepository,
			LhSupplyInfoTypeResolver supplyTypeResolver, PlatformTransactionManager transactionManager, Clock clock,
			LhNoticeSourceNormalizer normalizer, LhNoticeSourceStore sourceStore,
			LhNoticeDetailSourceRepository detailSourceRepository,
			LhNoticeSupplySourceRepository supplySourceRepository, NoticeScheduleRepository scheduleRepository,
			ReceptionPlaceRepository receptionPlaceRepository, NoticeAttachmentRepository attachmentRepository) {
		this.apiClient = apiClient;
		this.noticeRepository = noticeRepository;
		this.supplyRepository = supplyRepository;
		this.supplyTypeResolver = supplyTypeResolver;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.clock = clock;
		this.normalizer = normalizer;
		this.sourceStore = sourceStore;
		this.detailSourceRepository = detailSourceRepository;
		this.supplySourceRepository = supplySourceRepository;
		this.scheduleRepository = scheduleRepository;
		this.receptionPlaceRepository = receptionPlaceRepository;
		this.attachmentRepository = attachmentRepository;
	}

	public IngestReport ingest() {
		return ingest(false);
	}

	public IngestReport ingest(boolean refresh) {
		IngestReport report = IngestReport.empty();
		for (Notice notice : noticeRepository.findByDetailUrlContaining("panId")) {
			report = report.plus(applyOne(notice, refresh));
		}
		return report;
	}

	IngestReport applyOne(Notice notice, boolean refresh) {
		if (!refresh && notice.getLhFetchedAt() != null) {
			return IngestReport.oneUnchanged();
		}
		Optional<LhNoticeRequest> request = requestFor(notice);
		if (request.isEmpty()) {
			return rejectionFor(notice);
		}
		LhNoticeRequest resolved = request.orElseThrow();
		try {
			JsonNode details = apiClient.getRaw(DETAIL_PATH, resolved.toParams());
			JsonNode supplies = apiClient.getRaw(SUPPLY_PATH, resolved.toParams());
			LhNoticeSourceNormalizer.Rows rows = normalizer.normalize(resolved.panId(), details, supplies);
			sourceStore.replaceSnapshot(resolved.panId(), rows.details(), rows.supplies());
			return applyFromSources(notice, resolved, rows.details(), rows.supplies(), refresh);
		}
		catch (RuntimeException exception) {
			log.warn("LH 공고 상세·공급 적재 실패: sourceNoticeId={}", notice.getSourceNoticeId(), exception);
			return IngestReport.oneFailed();
		}
	}

	public IngestReport applyFromSources(Notice notice, List<LhNoticeDetailSource> details,
			List<LhNoticeSupplySource> supplies) {
		Optional<LhNoticeRequest> request = requestFor(notice);
		if (request.isEmpty()) {
			return rejectionFor(notice);
		}
		return applyFromSources(notice, request.orElseThrow(), details, supplies, true);
	}

	public IngestReport projectAll() {
		IngestReport report = IngestReport.empty();
		for (Notice notice : noticeRepository.findByDetailUrlContaining("panId")) {
			Optional<LhNoticeRequest> request = requestFor(notice);
			if (request.isEmpty()) {
				report = report.plus(rejectionFor(notice));
				continue;
			}
			String panId = request.orElseThrow().panId();
			report = report.plus(applyFromSources(notice, panId,
					detailSourceRepository.findByPanIdOrderBySourceOrderAscIdAsc(panId),
					supplySourceRepository.findByPanIdOrderBySourceOrderAscIdAsc(panId)));
		}
		return report;
	}

	private IngestReport applyFromSources(Notice notice, LhNoticeRequest request,
			List<LhNoticeDetailSource> details, List<LhNoticeSupplySource> supplies, boolean refresh) {
		if (!refresh && notice.getLhFetchedAt() != null) {
			return IngestReport.oneUnchanged();
		}
		try {
			IngestReport report = transactionTemplate.execute(status -> saveProjection(notice.getId(), request, details,
					supplies));
			return Objects.requireNonNull(report);
		}
		catch (RuntimeException exception) {
			log.warn("LH typed source 투영 실패: sourceNoticeId={}", notice.getSourceNoticeId(), exception);
			return IngestReport.oneFailed();
		}
	}

	private IngestReport applyFromSources(Notice notice, String panId, List<LhNoticeDetailSource> details,
			List<LhNoticeSupplySource> supplies) {
		Optional<String> code = supplyInfoTypeCodeOf(notice);
		if (code.isEmpty()) {
			return IngestReport.oneRejected(IngestRejectionReason.UNSUPPORTED_LH_SUPPLEMENT_TYPE);
		}
		LhNoticeRequest request = new LhNoticeRequest(panId, "", "", null, code.orElseThrow());
		return applyFromSources(notice, request, details, supplies, true);
	}

	private IngestReport saveProjection(Long noticeId, LhNoticeRequest request, List<LhNoticeDetailSource> details,
			List<LhNoticeSupplySource> supplies) {
		Notice notice = noticeRepository.findById(noticeId)
				.orElseThrow(() -> new IllegalArgumentException("존재하지 않는 공고입니다: " + noticeId));
		rebuildSupplies(notice, complexRows(details), supplyRows(supplies));
		deleteChildren(notice);
		noticeRepository.flush();
		addSchedules(notice, details);
		addReceptionPlaces(notice, details);
		addAttachments(notice, details);
		notice.markLhFetched(request.panId(), request.supplyInfoTypeCode(), correctionReason(details),
				LocalDateTime.now(clock));
		noticeRepository.save(notice);
		return IngestReport.oneCreated();
	}

	private void deleteChildren(Notice notice) {
		scheduleRepository.deleteByNoticeId(notice.getId());
		receptionPlaceRepository.deleteByNoticeId(notice.getId());
		attachmentRepository.deleteByNoticeId(notice.getId());
		notice.clearLhChildren();
	}

	private List<LhComplexRow> complexRows(List<LhNoticeDetailSource> details) {
		List<LhComplexRow> rows = new ArrayList<>();
		for (LhNoticeDetailSource source : details) {
			if (!"COMPLEX".equals(source.getDatasetType())) {
				continue;
			}
			LhComplexRow row = new LhComplexRow(source.getSourceOrder(), SourceValues.trimToNull(source.getComplexName()),
					SourceValues.trimToNull(source.getAddress()), SourceValues.trimToNull(source.getDetailAddress()),
					SourceValues.toInt(source.getTotalUnitCount()), SourceValues.toYearMonth(source.getExpectedMoveInYearMonth()));
			if (!row.isEmpty()) {
				rows.add(row);
			}
		}
		return rows;
	}

	private List<LhUnitSupplyValues> supplyRows(List<LhNoticeSupplySource> sources) {
		List<LhUnitSupplyValues> rows = new ArrayList<>();
		for (LhNoticeSupplySource source : sources) {
			LhUnitSupplyValues row = new LhUnitSupplyValues(SourceValues.trimToNull(source.getComplexLabel()),
					SourceValues.trimToNull(source.getTypeName()), SourceValues.toDecimal(source.getExclusiveArea()),
					SourceValues.toDecimal(source.getSupplyArea()), SourceValues.toInt(source.getTotalUnitCount()),
					SourceValues.toInt(source.getSuppliedUnitCount()), SourceValues.trimToNull(source.getDepositText()),
					SourceValues.trimToNull(source.getMonthlyRentText()));
			if (!row.isEmpty()) {
				rows.add(row);
			}
		}
		return rows;
	}

	private void rebuildSupplies(Notice notice, List<LhComplexRow> complexRows,
			List<LhUnitSupplyValues> supplyRows) {
		Map<Integer, NoticeSupply> byHouseSn = new LinkedHashMap<>();
		for (NoticeSupply supply : supplyRepository.findByNoticeIdOrderByDisplayOrder(notice.getId())) {
			if (supply.getHouseSn() != null) {
				byHouseSn.putIfAbsent(supply.getHouseSn(), supply);
			}
		}
		List<NoticeSupply> myHomeRows = new ArrayList<>(byHouseSn.values());
		Map<LhComplexRow, NoticeSupply> matches = matchByAddress(myHomeRows, complexRows);
		List<NoticeSupply> rebuilt = new ArrayList<>();
		Set<NoticeSupply> split = new LinkedHashSet<>();
		for (LhUnitSupplyValues values : supplyRows) {
			LhComplexRow complex = soleByLabel(complexRows, values.complexLabel());
			NoticeSupply matched = matchedSupply(matches, complex);
			NoticeSupply row = supplyOf(notice, rebuilt.size(), values, matched);
			if (matched != null) {
				split.add(matched);
			}
			if (complex != null) {
				row.applyMoveInYearMonth(complex.moveInYearMonth());
			}
			rebuilt.add(row);
		}
		for (NoticeSupply row : myHomeRows) {
			if (!split.contains(row)) {
				rebuilt.add(row.copyAt(rebuilt.size()));
			}
		}
		supplyRepository.deleteByNoticeId(notice.getId());
		supplyRepository.flush();
		supplyRepository.saveAll(rebuilt);
	}

	private NoticeSupply matchedSupply(Map<LhComplexRow, NoticeSupply> matches, LhComplexRow complex) {
		if (complex == null) {
			return null;
		}
		return matches.get(complex);
	}

	private NoticeSupply supplyOf(Notice notice, int displayOrder, LhUnitSupplyValues values,
			NoticeSupply matched) {
		if (matched == null) {
			return NoticeSupply.ofLhOnly(notice, displayOrder, values);
		}
		return matched.splitInto(displayOrder, values);
	}

	private Map<LhComplexRow, NoticeSupply> matchByAddress(List<NoticeSupply> myHomeRows,
			List<LhComplexRow> complexRows) {
		Map<NoticeSupply, List<LhComplexRow>> candidates = new LinkedHashMap<>();
		Map<LhComplexRow, List<NoticeSupply>> reverse = new LinkedHashMap<>();
		for (LhComplexRow complex : complexRows) {
			reverse.put(complex, new ArrayList<>());
		}
		for (NoticeSupply myHome : myHomeRows) {
			List<LhComplexRow> hits = new ArrayList<>();
			for (LhComplexRow complex : complexRows) {
				if (isAddressCandidate(myHome, complex)) {
					hits.add(complex);
					reverse.get(complex).add(myHome);
				}
			}
			candidates.put(myHome, hits);
		}
		Map<LhComplexRow, NoticeSupply> confirmed = new LinkedHashMap<>();
		for (NoticeSupply myHome : myHomeRows) {
			List<LhComplexRow> hits = candidates.get(myHome);
			if (hits.size() != 1) {
				continue;
			}
			LhComplexRow complex = hits.getFirst();
			if (reverse.get(complex).size() == 1 && !conflictingUnitCount(myHome, complex)) {
				confirmed.put(complex, myHome);
			}
		}
		return confirmed;
	}

	private boolean isAddressCandidate(NoticeSupply myHome, LhComplexRow complex) {
		String noticeAddress = normalize(myHome.getSuppliedAddress());
		String lhAddress = normalize(complex.fullAddress());
		return noticeAddress != null && lhAddress != null && lhAddress.startsWith(noticeAddress)
				|| lhAddress != null && noticeAddress != null && noticeAddress.startsWith(lhAddress);
	}

	private boolean conflictingUnitCount(NoticeSupply myHome, LhComplexRow complex) {
		Integer noticeCount = myHome.getComplexTotalUnitCount();
		Integer lhCount = complex.totalUnitCount();
		return noticeCount != null && lhCount != null && !noticeCount.equals(lhCount);
	}

	private LhComplexRow soleByLabel(List<LhComplexRow> rows, String label) {
		String normalized = normalize(label);
		if (normalized == null) {
			return null;
		}
		List<LhComplexRow> hits = rows.stream().filter(row -> normalized.equals(normalize(row.complexLabel()))).toList();
		if (hits.size() != 1) {
			return null;
		}
		return hits.getFirst();
	}

	static String normalize(String value) {
		if (value == null) {
			return null;
		}
		return value.strip().replace(" ", "");
	}

	private void addSchedules(Notice notice, List<LhNoticeDetailSource> details) {
		for (LhNoticeDetailSource source : details) {
			if (!"SCHEDULE".equals(source.getDatasetType()) || isEmptySchedule(source)) {
				continue;
			}
			notice.addSchedule(SourceValues.trimToNull(source.getComplexName()),
					SourceValues.trimToNull(source.getApplicationPeriod()), SourceValues.toDate(source.getDocumentTargetAnnouncementDate()),
					SourceValues.toDate(source.getDocumentSubmissionBeginDate()), SourceValues.toDate(source.getDocumentSubmissionEndDate()),
					SourceValues.toDate(source.getContractBeginDate()), SourceValues.toDate(source.getContractEndDate()));
		}
	}

	private boolean isEmptySchedule(LhNoticeDetailSource source) {
		return source.getComplexName() == null && source.getApplicationPeriod() == null
				&& source.getDocumentTargetAnnouncementDate() == null && source.getDocumentSubmissionBeginDate() == null
				&& source.getDocumentSubmissionEndDate() == null && source.getContractBeginDate() == null
				&& source.getContractEndDate() == null;
	}

	private void addReceptionPlaces(Notice notice, List<LhNoticeDetailSource> details) {
		for (LhNoticeDetailSource source : details) {
			if (!"RECEPTION".equals(source.getDatasetType()) || isEmptyReception(source)) {
				continue;
			}
			notice.addReceptionPlace(source.getReceptionAddress(), source.getReceptionDetailAddress(), source.getOperationBegin(),
					source.getOperationEnd(), source.getPhone(), source.getReceptionGuidance());
		}
	}

	private boolean isEmptyReception(LhNoticeDetailSource source) {
		return source.getReceptionAddress() == null && source.getReceptionDetailAddress() == null
				&& source.getOperationBegin() == null && source.getOperationEnd() == null && source.getPhone() == null
				&& source.getReceptionGuidance() == null;
	}

	private void addAttachments(Notice notice, List<LhNoticeDetailSource> details) {
		for (LhNoticeDetailSource source : details) {
			if (("NOTICE_FILE".equals(source.getDatasetType()) || "COMPLEX_IMAGE".equals(source.getDatasetType()))
					&& isHttpUrl(source.getUrl()) && source.getKind() != null && source.getName() != null) {
				notice.addAttachment(source.getKind(), source.getName(), source.getUrl(), source.getAttachmentComplexName());
			}
		}
	}

	private boolean isHttpUrl(String raw) {
		if (raw == null) {
			return false;
		}
		try {
			URI uri = URI.create(raw);
			return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
					&& uri.getHost() != null;
		}
		catch (IllegalArgumentException exception) {
			return false;
		}
	}

	private String correctionReason(List<LhNoticeDetailSource> details) {
		for (LhNoticeDetailSource source : details) {
			String correction = SourceValues.trimToNull(source.getCorrectionReason());
			if (correction != null) {
				return correction;
			}
		}
		return null;
	}

	private Optional<LhNoticeRequest> requestFor(Notice notice) {
		Optional<String> code = supplyInfoTypeCodeOf(notice);
		if (code.isEmpty() || notice.getDetailUrl() == null) {
			return Optional.empty();
		}
		try {
			return LhNoticeRequest.from(URI.create(notice.getDetailUrl()), code.orElseThrow());
		}
		catch (IllegalArgumentException exception) {
			return Optional.empty();
		}
	}

	private Optional<String> supplyInfoTypeCodeOf(Notice notice) {
		String prepared = SourceValues.trimToNull(notice.getLhSupplyInfoTypeCode());
		if (prepared != null) {
			return Optional.of(prepared);
		}
		try {
			return supplyTypeResolver.resolve(notice.getSupplyTypeName());
		}
		catch (IllegalArgumentException exception) {
			return Optional.empty();
		}
	}

	private IngestReport rejectionFor(Notice notice) {
		if (supplyInfoTypeCodeOf(notice).isEmpty()) {
			return IngestReport.oneRejected(IngestRejectionReason.UNSUPPORTED_LH_SUPPLEMENT_TYPE);
		}
		return IngestReport.oneRejected(IngestRejectionReason.INVALID_SOURCE_ROW);
	}

	private record LhComplexRow(int sourceOrder, String complexLabel, String address, String detailAddress,
			Integer totalUnitCount, YearMonth moveInYearMonth) {

		String fullAddress() {
			if (address == null) {
				return detailAddress;
			}
			if (detailAddress == null) {
				return address;
			}
			return address + " " + detailAddress;
		}

		boolean isEmpty() {
			return complexLabel == null && address == null && detailAddress == null && totalUnitCount == null
					&& moveInYearMonth == null;
		}
	}
}

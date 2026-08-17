package com.toadzip.backend.ingest.myhome;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.toadzip.backend.housing.Address;
import com.toadzip.backend.ingest.ConstructionRentalPolicy;
import com.toadzip.backend.ingest.IngestRejectionReason;
import com.toadzip.backend.ingest.IngestReport;
import com.toadzip.backend.ingest.SourceValues;
import com.toadzip.backend.ingest.myhome.source.MyHomeNoticeSource;
import com.toadzip.backend.ingest.myhome.source.MyHomeNoticeSourceRepository;
import com.toadzip.backend.notice.Notice;
import com.toadzip.backend.notice.NoticeRepository;
import com.toadzip.backend.notice.NoticeSnapshot;
import com.toadzip.backend.notice.NoticeSupply;
import com.toadzip.backend.notice.NoticeSupplyRepository;
import com.toadzip.backend.notice.RentTerms;

@Slf4j
@Service
public class MyHomeNoticeProjectionService {

	private final MyHomeNoticeSourceRepository sourceRepository;

	private final NoticeRepository noticeRepository;

	private final NoticeSupplyRepository noticeSupplyRepository;

	private final ConstructionRentalPolicy rentalPolicy;

	private final TransactionTemplate transactionTemplate;

	public MyHomeNoticeProjectionService(MyHomeNoticeSourceRepository sourceRepository,
			NoticeRepository noticeRepository, NoticeSupplyRepository noticeSupplyRepository,
			ConstructionRentalPolicy rentalPolicy, PlatformTransactionManager transactionManager) {
		this.sourceRepository = sourceRepository;
		this.noticeRepository = noticeRepository;
		this.noticeSupplyRepository = noticeSupplyRepository;
		this.rentalPolicy = rentalPolicy;
		transactionTemplate = new TransactionTemplate(transactionManager);
		transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	public IngestReport projectAll() {
		List<MyHomeNoticeSourceItem> items = sourceRepository.findAllByOrderBySourceOrderAsc()
			.stream()
			.map(MyHomeNoticeSource::toItem)
			.toList();
		return project(items);
	}

	private IngestReport project(List<MyHomeNoticeSourceItem> items) {
		IngestReport report = IngestReport.empty();
		Map<String, List<MyHomeNoticeSourceItem>> groups = new LinkedHashMap<>();
		Map<NoticeSupplyKey, MyHomeNoticeSourceItem> unique = new LinkedHashMap<>();
		Set<String> conflictedNoticeIds = new HashSet<>();
		for (MyHomeNoticeSourceItem item : items) {
			String noticeId = SourceValues.trimToNull(item.pblancId());
			if (noticeId == null) {
				report = report.plus(IngestReport.oneRejected(IngestRejectionReason.MISSING_IDENTITY));
				continue;
			}
			NoticeSupplyKey key = new NoticeSupplyKey(noticeId, item.houseSn());
			MyHomeNoticeSourceItem previous = unique.putIfAbsent(key, item);
			if (isConflict(previous, item)) {
				conflictedNoticeIds.add(noticeId);
			}
		}
		for (MyHomeNoticeSourceItem item : unique.values()) {
			String noticeId = SourceValues.trimToNull(item.pblancId());
			if (!conflictedNoticeIds.contains(noticeId)) {
				groups.computeIfAbsent(noticeId, ignored -> new ArrayList<>()).add(item);
			}
		}
		for (String ignored : conflictedNoticeIds) {
			report = report.plus(IngestReport.oneRejected(IngestRejectionReason.INVALID_SOURCE_ROW));
		}
		ChainResolution chain = resolveChainOrder(groups);
		for (String ignored : chain.excludedIds()) {
			report = report.plus(IngestReport.oneRejected(IngestRejectionReason.INVALID_SOURCE_ROW));
		}
		for (String noticeId : chain.order()) {
			report = report.plus(projectNotice(noticeId, groups.get(noticeId)));
		}
		return report;
	}

	private boolean isConflict(MyHomeNoticeSourceItem previous, MyHomeNoticeSourceItem incoming) {
		if (previous == null) {
			return false;
		}
		return !MyHomeNoticeSource.hasSameValues(previous, incoming);
	}

	private ChainResolution resolveChainOrder(Map<String, List<MyHomeNoticeSourceItem>> groups) {
		Map<String, String> beforeIds = beforeIds(groups);
		Set<String> branchExcluded = branchExcluded(beforeIds);
		Set<String> remaining = new LinkedHashSet<>(groups.keySet());
		remaining.removeAll(branchExcluded);
		Map<String, List<String>> children = new LinkedHashMap<>();
		Map<String, Integer> dependencies = new HashMap<>();
		for (String noticeId : remaining) {
			String beforeId = beforeIds.get(noticeId);
			if (beforeId != null && remaining.contains(beforeId)) {
				children.computeIfAbsent(beforeId, ignored -> new ArrayList<>()).add(noticeId);
				dependencies.put(noticeId, 1);
				continue;
			}
			dependencies.put(noticeId, 0);
		}
		Deque<String> ready = new ArrayDeque<>();
		for (String noticeId : remaining) {
			if (dependencies.get(noticeId) == 0) {
				ready.add(noticeId);
			}
		}
		List<String> order = new ArrayList<>();
		while (!ready.isEmpty()) {
			String current = ready.poll();
			order.add(current);
			for (String child : children.getOrDefault(current, List.of())) {
				if (dependencies.merge(child, -1, Integer::sum) == 0) {
					ready.add(child);
				}
			}
		}
		Set<String> excluded = new LinkedHashSet<>(branchExcluded);
		Set<String> cycles = new LinkedHashSet<>(remaining);
		cycles.removeAll(order);
		excluded.addAll(cycles);
		return new ChainResolution(order, excluded);
	}

	private Map<String, String> beforeIds(Map<String, List<MyHomeNoticeSourceItem>> groups) {
		Map<String, String> beforeIds = new LinkedHashMap<>();
		for (Map.Entry<String, List<MyHomeNoticeSourceItem>> entry : groups.entrySet()) {
			beforeIds.put(entry.getKey(), SourceValues.trimToNull(entry.getValue().get(0).beforePblancId()));
		}
		return beforeIds;
	}

	private Set<String> branchExcluded(Map<String, String> beforeIds) {
		Map<String, List<String>> claimants = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : beforeIds.entrySet()) {
			if (entry.getValue() != null) {
				claimants.computeIfAbsent(entry.getValue(), ignored -> new ArrayList<>()).add(entry.getKey());
			}
		}
		Set<String> excluded = new LinkedHashSet<>();
		for (List<String> children : claimants.values()) {
			if (children.size() > 1) {
				excluded.addAll(children);
			}
		}
		return excluded;
	}

	private IngestReport projectNotice(String noticeId, List<MyHomeNoticeSourceItem> rows) {
		try {
			IngestReport report = transactionTemplate.execute(status -> projectNoticeInTransaction(noticeId, rows));
			return Objects.requireNonNull(report);
		}
		catch (RuntimeException exception) {
			log.warn("마이홈 공고 투영 실패: pblancId={}", noticeId, exception);
			return IngestReport.oneFailed();
		}
	}

	private IngestReport projectNoticeInTransaction(String noticeId, List<MyHomeNoticeSourceItem> rows) {
		MyHomeNoticeSourceItem head = rows.get(0);
		String supplyType = SourceValues.trimToNull(head.suplyTyNm());
		if (rows.stream().map(item -> SourceValues.trimToNull(item.suplyTyNm()))
				.anyMatch(label -> !Objects.equals(label, supplyType))) {
			return IngestReport.oneRejected(IngestRejectionReason.INVALID_SOURCE_ROW);
		}
		Optional<IngestRejectionReason> supplyRejection = rentalPolicy.rejectSupplyType(supplyType);
		if (supplyRejection.isPresent()) {
			return IngestReport.oneRejected(supplyRejection.orElseThrow());
		}
		List<MyHomeNoticeSourceItem> validRows = new ArrayList<>();
		IngestReport rejectedRows = IngestReport.empty();
		for (MyHomeNoticeSourceItem row : rows) {
			if (validSupplyLine(row)) {
				validRows.add(row);
				continue;
			}
			rejectedRows = rejectedRows.plus(IngestReport.oneRejected(IngestRejectionReason.INVALID_SOURCE_ROW));
		}
		if (validRows.isEmpty()) {
			return rejectedRows;
		}
		return upsertNotice(noticeId, validRows).plus(rejectedRows);
	}

	private boolean validSupplyLine(MyHomeNoticeSourceItem row) {
		return row.houseSn() != null && row.houseSn() > 0;
	}

	private IngestReport upsertNotice(String noticeId, List<MyHomeNoticeSourceItem> rows) {
		MyHomeNoticeSourceItem head = rows.get(0);
		Optional<Notice> stored = noticeRepository.findBySourceNoticeId(noticeId);
		if (stored.isPresent()) {
			return reportForExisting(stored.orElseThrow(), rows);
		}
		String beforeId = SourceValues.trimToNull(head.beforePblancId());
		Optional<Notice> previous = findPrevious(head, beforeId);
		Notice notice = firstOrNext(noticeId, beforeId, head, previous);
		noticeRepository.save(notice);
		saveSupplies(notice, rows);
		rebaseFollowers(notice);
		if (previous.isPresent()) {
			return IngestReport.oneUpdated();
		}
		return IngestReport.oneCreated();
	}

	private IngestReport reportForExisting(Notice existing, List<MyHomeNoticeSourceItem> rows) {
		MyHomeNoticeSourceItem head = rows.get(0);
		if (!existing.hasSameContentAs(snapshotOf(head)) || !hasSameSupplyContent(existing, rows)) {
			return IngestReport.oneRejected(IngestRejectionReason.INVALID_SOURCE_ROW);
		}
		return IngestReport.oneUnchanged();
	}

	private Notice firstOrNext(String noticeId, String beforeId, MyHomeNoticeSourceItem head,
			Optional<Notice> previous) {
		if (previous.isPresent()) {
			return previous.orElseThrow().nextVersion(noticeId, beforeId, snapshotOf(head));
		}
		return Notice.firstVersion(noticeId, beforeId, snapshotOf(head));
	}

	private Optional<Notice> findPrevious(MyHomeNoticeSourceItem head, String beforeId) {
		if (beforeId == null) {
			return Optional.empty();
		}
		Optional<Notice> previous = noticeRepository.findBySourceNoticeId(beforeId);
		if (previous.isEmpty()) {
			log.warn("정정공고 {}의 이전 공고 {}를 찾지 못해 새 체인으로 시작합니다.", head.pblancId(), beforeId);
		}
		return previous;
	}

	private boolean hasSameSupplyContent(Notice existing, List<MyHomeNoticeSourceItem> rows) {
		Map<Integer, NoticeSupply> stored = noticeSupplyRepository.findByNoticeOrderByDisplayOrder(existing)
			.stream()
			.filter(supply -> supply.getHouseSn() != null)
			.collect(Collectors.toMap(NoticeSupply::getHouseSn, supply -> supply,
					(first, second) -> first, LinkedHashMap::new));
		Set<Integer> incoming = rows.stream().map(MyHomeNoticeSourceItem::houseSn)
			.collect(Collectors.toCollection(LinkedHashSet::new));
		if (!stored.keySet().equals(incoming)) {
			return false;
		}
		for (MyHomeNoticeSourceItem row : rows) {
			if (!sameSupplyValues(stored.get(row.houseSn()), row)) {
				return false;
			}
		}
		return true;
	}

	private boolean sameSupplyValues(NoticeSupply stored, MyHomeNoticeSourceItem row) {
		if (stored == null) {
			return false;
		}
		return Objects.equals(stored.getComplexSupplyCount(), row.sumSuplyCo())
				&& Objects.equals(stored.getComplexName(), SourceValues.trimToNull(row.hsmpNm()))
				&& Objects.equals(stored.getSuppliedPnu(), Address.normalizePnu(row.pnu()))
				&& Objects.equals(stored.getSuppliedAddress(), SourceValues.trimToNull(row.fullAdres()))
				&& Objects.equals(stored.getComplexTotalUnitCount(), SourceValues.toInt(row.totHshldCo()))
				&& RentTerms.sameValues(stored.getRentTerms(), rentTermsOf(row))
				&& Objects.equals(stored.getDetailUrl(), SourceValues.trimToNull(row.pcUrl()))
				&& Objects.equals(stored.getMobileDetailUrl(), SourceValues.trimToNull(row.mobileUrl()));
	}

	private void saveSupplies(Notice notice, List<MyHomeNoticeSourceItem> rows) {
		for (int order = 0; order < rows.size(); order++) {
			MyHomeNoticeSourceItem row = rows.get(order);
			noticeSupplyRepository.save(NoticeSupply.ofComplex(notice, order, row.houseSn(),
					SourceValues.trimToNull(row.hsmpNm()), Address.normalizePnu(row.pnu()),
					SourceValues.trimToNull(row.fullAdres()), row.sumSuplyCo(), SourceValues.toInt(row.totHshldCo()),
					rentTermsOf(row), SourceValues.trimToNull(row.pcUrl()), SourceValues.trimToNull(row.mobileUrl())));
		}
	}

	private NoticeSnapshot snapshotOf(MyHomeNoticeSourceItem item) {
		LocalDate publishedOn = SourceValues.toDate(item.rcritPblancDe());
		return new NoticeSnapshot(SourceValues.trimToNull(item.sttusNm()),
				publishedAt(publishedOn), SourceValues.trimToNull(item.pblancNm()),
				SourceValues.trimToNull(item.url()), SourceValues.toDate(item.beginDe()), SourceValues.toDate(item.endDe()),
				SourceValues.toDate(item.przwnerPresnatnDe()), SourceValues.trimToNull(item.suplyInsttNm()),
				SourceValues.trimToNull(item.houseTyNm()), SourceValues.trimToNull(item.suplyTyNm()),
				SourceValues.trimToNull(item.refrnc()));
	}

	private LocalDateTime publishedAt(LocalDate publishedOn) {
		if (publishedOn == null) {
			return null;
		}
		return publishedOn.atStartOfDay();
	}

	private RentTerms rentTermsOf(MyHomeNoticeSourceItem row) {
		return new RentTerms(row.rentGtn(), row.enty(), row.surlus(), row.mtRntchrg());
	}

	private void rebaseFollowers(Notice notice) {
		for (Notice follower : noticeRepository.findByBeforeSourceNoticeId(notice.getSourceNoticeId())) {
			if (Objects.equals(follower.getId(), notice.getId())) {
				continue;
			}
			follower.rebaseOnto(notice);
			noticeRepository.save(follower);
			rebaseFollowers(follower);
		}
	}

	private record NoticeSupplyKey(String noticeId, Integer houseSn) {
	}

	private record ChainResolution(List<String> order, Set<String> excludedIds) {
	}

}

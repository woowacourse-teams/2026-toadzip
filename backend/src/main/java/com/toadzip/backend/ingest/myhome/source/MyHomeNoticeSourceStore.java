package com.toadzip.backend.ingest.myhome.source;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.toadzip.backend.ingest.IngestRejectionReason;
import com.toadzip.backend.ingest.IngestReport;
import com.toadzip.backend.ingest.SourceValues;
import com.toadzip.backend.ingest.myhome.MyHomeNoticeSourceItem;

@Service
public class MyHomeNoticeSourceStore {

	private final MyHomeNoticeSourceRepository repository;

	public MyHomeNoticeSourceStore(MyHomeNoticeSourceRepository repository) {
		this.repository = repository;
	}

	@Transactional
	public IngestReport store(List<MyHomeNoticeSourceItem> items) {
		return storeBatch(items);
	}

	@Transactional
	public IngestReport storeBatch(List<MyHomeNoticeSourceItem> items) {
		IdentifiedRows identified = identify(items);
		DeduplicatedRows deduplicated = deduplicate(identified.rows());
		IngestReport report = identified.report().plus(conflictReport(deduplicated.conflictedNoticeIds()));
		Map<String, List<OrderedItem>> notices = groupByNotice(deduplicated.accepted());
		for (List<OrderedItem> notice : notices.values()) {
			report = report.plus(storeNotice(notice));
		}
		return report;
	}

	private IdentifiedRows identify(List<MyHomeNoticeSourceItem> items) {
		List<OrderedItem> rows = new ArrayList<>();
		IngestReport report = IngestReport.empty();
		for (int sourceOrder = 0; sourceOrder < items.size(); sourceOrder++) {
			MyHomeNoticeSourceItem item = items.get(sourceOrder);
			if (SourceValues.trimToNull(item.pblancId()) == null) {
				report = report.plus(IngestReport.oneRejected(IngestRejectionReason.MISSING_IDENTITY));
				continue;
			}
			rows.add(new OrderedItem(sourceOrder, item));
		}
		return new IdentifiedRows(rows, report);
	}

	private DeduplicatedRows deduplicate(List<OrderedItem> rows) {
		Map<String, OrderedItem> unique = new LinkedHashMap<>();
		Set<String> conflictedNoticeIds = new HashSet<>();
		for (OrderedItem row : rows) {
			String sourceKey = MyHomeNoticeSource.sourceKeyOf(row.item());
			OrderedItem previous = unique.putIfAbsent(sourceKey, row);
			if (isConflict(previous, row)) {
				conflictedNoticeIds.add(noticeId(row.item()));
			}
		}
		List<OrderedItem> accepted = unique.values()
			.stream()
			.filter(row -> !conflictedNoticeIds.contains(noticeId(row.item())))
			.toList();
		return new DeduplicatedRows(accepted, Set.copyOf(conflictedNoticeIds));
	}

	private boolean isConflict(OrderedItem previous, OrderedItem incoming) {
		if (previous == null) {
			return false;
		}
		return !MyHomeNoticeSource.hasSameValues(previous.item(), incoming.item());
	}

	private Map<String, List<OrderedItem>> groupByNotice(List<OrderedItem> rows) {
		Map<String, List<OrderedItem>> notices = new LinkedHashMap<>();
		for (OrderedItem row : rows) {
			notices.computeIfAbsent(noticeId(row.item()), ignored -> new ArrayList<>()).add(row);
		}
		return notices;
	}

	private IngestReport storeNotice(List<OrderedItem> rows) {
		Map<String, Optional<MyHomeNoticeSource>> storedByKey = findStored(rows);
		if (containsChangedRow(rows, storedByKey)) {
			return IngestReport.oneRejected(IngestRejectionReason.INVALID_SOURCE_ROW);
		}
		IngestReport report = IngestReport.empty();
		for (OrderedItem row : rows) {
			String sourceKey = MyHomeNoticeSource.sourceKeyOf(row.item());
			Optional<MyHomeNoticeSource> stored = storedByKey.get(sourceKey);
			if (stored.isPresent()) {
				report = report.plus(IngestReport.oneUnchanged());
				continue;
			}
			repository.save(MyHomeNoticeSource.from(row.sourceOrder(), row.item()));
			report = report.plus(IngestReport.oneCreated());
		}
		return report;
	}

	private Map<String, Optional<MyHomeNoticeSource>> findStored(List<OrderedItem> rows) {
		Map<String, Optional<MyHomeNoticeSource>> storedByKey = new LinkedHashMap<>();
		for (OrderedItem row : rows) {
			String sourceKey = MyHomeNoticeSource.sourceKeyOf(row.item());
			storedByKey.put(sourceKey, repository.findBySourceKey(sourceKey));
		}
		return storedByKey;
	}

	private boolean containsChangedRow(List<OrderedItem> rows,
			Map<String, Optional<MyHomeNoticeSource>> storedByKey) {
		for (OrderedItem row : rows) {
			String sourceKey = MyHomeNoticeSource.sourceKeyOf(row.item());
			Optional<MyHomeNoticeSource> stored = storedByKey.get(sourceKey);
			if (stored.isPresent() && !stored.orElseThrow().hasSameValues(row.item())) {
				return true;
			}
		}
		return false;
	}

	private IngestReport conflictReport(Set<String> conflictedNoticeIds) {
		IngestReport report = IngestReport.empty();
		for (String ignored : conflictedNoticeIds) {
			report = report.plus(IngestReport.oneRejected(IngestRejectionReason.INVALID_SOURCE_ROW));
		}
		return report;
	}

	private String noticeId(MyHomeNoticeSourceItem item) {
		return SourceValues.trimToNull(item.pblancId());
	}

	private record OrderedItem(int sourceOrder, MyHomeNoticeSourceItem item) {
	}

	private record IdentifiedRows(List<OrderedItem> rows, IngestReport report) {
	}

	private record DeduplicatedRows(List<OrderedItem> accepted, Set<String> conflictedNoticeIds) {
	}

}

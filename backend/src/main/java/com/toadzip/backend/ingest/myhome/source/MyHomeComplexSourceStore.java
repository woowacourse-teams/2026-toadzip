package com.toadzip.backend.ingest.myhome.source;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.toadzip.backend.ingest.IngestReport;
import com.toadzip.backend.ingest.myhome.MyHomeComplexSourceItem;
import com.toadzip.backend.ingest.myhome.MyHomeRegion;

@Service
public class MyHomeComplexSourceStore {

	private final MyHomeComplexSourceRepository repository;

	public MyHomeComplexSourceStore(MyHomeComplexSourceRepository repository) {
		this.repository = repository;
	}

	@Transactional
	public IngestReport store(List<MyHomeComplexSourceItem> items) {
		IngestReport report = IngestReport.empty();
		if (items.isEmpty()) {
			return report;
		}

		Set<String> sourceKeys = items.stream()
			.map(MyHomeComplexSource::sourceKeyOf)
			.collect(Collectors.toCollection(LinkedHashSet::new));
		Map<String, MyHomeComplexSource> storedByKey = repository.findAllBySourceKeyIn(sourceKeys)
			.stream()
			.collect(Collectors.toMap(MyHomeComplexSource::getSourceKey, source -> source, (first, second) -> first,
					LinkedHashMap::new));
		List<MyHomeComplexSource> newSources = new ArrayList<>();
		for (MyHomeComplexSourceItem item : items) {
			String sourceKey = MyHomeComplexSource.sourceKeyOf(item);
			MyHomeComplexSource stored = storedByKey.get(sourceKey);
			if (stored == null) {
				stored = MyHomeComplexSource.from(item);
				storedByKey.put(sourceKey, stored);
				newSources.add(stored);
				report = report.plus(IngestReport.oneCreated());
			}
			else if (stored.replaceWith(item)) {
				report = report.plus(IngestReport.oneUpdated());
			}
			else {
				report = report.plus(IngestReport.oneUnchanged());
			}
		}
		if (!newSources.isEmpty()) {
			repository.saveAll(newSources);
		}
		return report;
	}

	@Transactional
	public IngestReport replaceRegionSnapshot(MyHomeRegion region, List<MyHomeComplexSourceItem> items) {
		validateRegion(region, items);
		IngestReport report = store(items);
		Set<String> incomingKeys = items.stream()
			.map(MyHomeComplexSource::sourceKeyOf)
			.collect(Collectors.toSet());
		List<MyHomeComplexSource> staleRows = repository
			.findAllByBrtcCodeAndSignguCode(region.provinceCode(), region.districtCode())
			.stream()
			.filter(source -> !incomingKeys.contains(source.getSourceKey()))
			.toList();
		repository.deleteAll(staleRows);
		for (MyHomeComplexSource ignored : staleRows) {
			report = report.plus(IngestReport.oneUpdated());
		}
		return report;
	}

	private void validateRegion(MyHomeRegion region, List<MyHomeComplexSourceItem> items) {
		boolean containsOtherRegion = items.stream()
			.anyMatch(item -> !region.provinceCode().equals(item.brtcCode())
					|| !region.districtCode().equals(item.signguCode()));
		if (containsOtherRegion) {
			throw new IllegalArgumentException("지역 스냅샷에 다른 지역의 원천 행이 포함되어 있습니다.");
		}
	}

}

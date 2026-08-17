package com.toadzip.backend.ingest.myhome.source;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.toadzip.backend.ingest.IngestReport;
import com.toadzip.backend.ingest.myhome.MyHomeComplexSourceItem;

@Service
public class MyHomeComplexSourceStore {

	private final MyHomeComplexSourceRepository repository;

	public MyHomeComplexSourceStore(MyHomeComplexSourceRepository repository) {
		this.repository = repository;
	}

	@Transactional
	public IngestReport store(List<MyHomeComplexSourceItem> items) {
		IngestReport report = IngestReport.empty();
		for (MyHomeComplexSourceItem item : items) {
			report = report.plus(store(item));
		}
		return report;
	}

	private IngestReport store(MyHomeComplexSourceItem item) {
		String sourceKey = MyHomeComplexSource.sourceKeyOf(item);
		Optional<MyHomeComplexSource> stored = repository.findBySourceKey(sourceKey);
		if (stored.isEmpty()) {
			repository.save(MyHomeComplexSource.from(item));
			return IngestReport.oneCreated();
		}
		if (stored.orElseThrow().replaceWith(item)) {
			return IngestReport.oneUpdated();
		}
		return IngestReport.oneUnchanged();
	}

}

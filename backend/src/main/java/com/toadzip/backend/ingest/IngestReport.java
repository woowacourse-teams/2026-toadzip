package com.toadzip.backend.ingest;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

public record IngestReport(int created, int updated, int unchanged, int failed,
		Map<IngestRejectionReason, Integer> rejectedByReason) {

	public IngestReport {
		if (created < 0 || updated < 0 || unchanged < 0 || failed < 0) {
			throw new IllegalArgumentException("적재 결과 개수는 음수일 수 없습니다.");
		}

		EnumMap<IngestRejectionReason, Integer> copy = new EnumMap<>(IngestRejectionReason.class);
		if (rejectedByReason != null) {
			rejectedByReason.forEach((reason, count) -> {
				if (reason == null || count == null || count < 1) {
					throw new IllegalArgumentException("제외 사유와 개수는 필수이며 개수는 1 이상이어야 합니다.");
				}
				copy.put(reason, count);
			});
		}
		rejectedByReason = Collections.unmodifiableMap(copy);
	}

	public static IngestReport empty() {
		return new IngestReport(0, 0, 0, 0, Map.of());
	}

	public static IngestReport oneCreated() {
		return new IngestReport(1, 0, 0, 0, Map.of());
	}

	public static IngestReport oneUpdated() {
		return new IngestReport(0, 1, 0, 0, Map.of());
	}

	public static IngestReport oneUnchanged() {
		return new IngestReport(0, 0, 1, 0, Map.of());
	}

	public static IngestReport oneFailed() {
		return new IngestReport(0, 0, 0, 1, Map.of());
	}

	public static IngestReport oneRejected(IngestRejectionReason reason) {
		return new IngestReport(0, 0, 0, 0, Map.of(reason, 1));
	}

	public int rejected() {
		return rejectedByReason.values().stream().mapToInt(Integer::intValue).sum();
	}

	public IngestReport plus(IngestReport other) {
		EnumMap<IngestRejectionReason, Integer> rejections = new EnumMap<>(IngestRejectionReason.class);
		rejectedByReason.forEach(rejections::put);
		other.rejectedByReason.forEach((reason, count) -> rejections.merge(reason, count, Integer::sum));
		return new IngestReport(created + other.created, updated + other.updated, unchanged + other.unchanged,
				failed + other.failed, rejections);
	}

}

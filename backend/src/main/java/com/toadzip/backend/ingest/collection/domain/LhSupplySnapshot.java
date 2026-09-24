package com.toadzip.backend.ingest.collection.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class LhSupplySnapshot {

    private LhSupplySnapshot() {
    }

    public static long missingRowCount(
            List<LhAnnouncementSupplySource> previous,
            List<LhAnnouncementSupplySource> incoming
    ) {
        Map<RowIdentity, Long> previousCounts = countsOf(previous);
        Map<RowIdentity, Long> incomingCounts = countsOf(incoming);
        return previousCounts.entrySet().stream()
                .mapToLong(entry -> Math.max(0, entry.getValue() - incomingCounts.getOrDefault(entry.getKey(), 0L)))
                .sum();
    }

    private static Map<RowIdentity, Long> countsOf(List<LhAnnouncementSupplySource> sources) {
        return sources.stream()
                .map(RowIdentity::of)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    }

    private record RowIdentity(String complexName, String typeName, String exclusiveArea, String supplyArea) {

        private static RowIdentity of(LhAnnouncementSupplySource source) {
            return new RowIdentity(
                    source.getComplexLabel(), source.getTypeName(),
                    normalizedArea(source.getExclusiveArea()), normalizedArea(source.getSupplyArea())
            );
        }

        private static String normalizedArea(String value) {
            if (value == null) {
                return null;
            }
            String normalized = value.replace(",", "").replace("㎡", "").strip();
            if (!normalized.matches("[0-9]+(?:\\.[0-9]+)?")) {
                return value;
            }
            return new BigDecimal(normalized).stripTrailingZeros().toPlainString();
        }
    }
}

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
        Map<RowIdentity, List<LhAnnouncementSupplySource>> previousGroups = groupsOf(previous);
        Map<RowIdentity, List<LhAnnouncementSupplySource>> incomingGroups = groupsOf(incoming);
        return previousGroups.entrySet().stream()
                .mapToLong(entry -> missingCount(entry.getValue(), incomingGroups.getOrDefault(entry.getKey(), List.of())))
                .sum();
    }

    private static Map<RowIdentity, List<LhAnnouncementSupplySource>> groupsOf(
            List<LhAnnouncementSupplySource> sources
    ) {
        return sources.stream()
                .collect(Collectors.groupingBy(RowIdentity::of));
    }

    private static long missingCount(
            List<LhAnnouncementSupplySource> previous,
            List<LhAnnouncementSupplySource> incoming
    ) {
        Map<RowValues, Long> previousCounts = countsOf(previous);
        if (previousCounts.size() == 1) {
            return Math.max(0, previous.size() - incoming.size());
        }
        Map<RowValues, Long> incomingCounts = countsOf(incoming);
        return previousCounts.entrySet().stream()
                .mapToLong(entry -> Math.max(0, entry.getValue() - incomingCounts.getOrDefault(entry.getKey(), 0L)))
                .sum();
    }

    private static Map<RowValues, Long> countsOf(List<LhAnnouncementSupplySource> sources) {
        return sources.stream()
                .map(RowValues::of)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    }

    private record RowValues(
            String totalUnitCount,
            String suppliedUnitCount,
            String depositText,
            String monthlyRentText
    ) {

        private static RowValues of(LhAnnouncementSupplySource source) {
            return new RowValues(
                    source.getTotalUnitCount(), source.getSuppliedUnitCount(),
                    source.getDepositText(), source.getMonthlyRentText()
            );
        }
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
